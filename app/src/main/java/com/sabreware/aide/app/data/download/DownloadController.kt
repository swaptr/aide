package com.sabreware.aide.app.data.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import com.sabreware.aide.core.domain.download.DownloadStatus
import com.sabreware.aide.core.domain.download.AssetHandle
import com.sabreware.aide.core.domain.download.AssetSource
import com.sabreware.aide.core.domain.download.AssetSourceRegistry
import com.sabreware.aide.core.domain.download.DownloadAsset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import okio.FileSystem

// Pause = cancel work, keep .part. Resume = re-enqueue KEEP; engine resumes via Range header.
// Cancel = wipe everything on disk for this asset.
class DownloadController(
    appContext: Context,
    private val sources: AssetSourceRegistry,
    private val scope: CoroutineScope,
    /**
     * Where the disk work runs. Resolving a job stats files and creates directories, and [observe]'s ticker
     * re-checks the installed state on every emission — twice a second per active download, which used to
     * happen on whichever dispatcher the collector was on (the UI's).
     */
    private val ioDispatcher: CoroutineDispatcher,
    private val fs: FileSystem = FileSystem.SYSTEM,
) {
    private val context = appContext.applicationContext
    private val wm get() = WorkManager.getInstance(context)

    fun enqueue(handle: AssetHandle): String {
        val request = OneTimeWorkRequestBuilder<AssetDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            // Linear backoff for retry() (e.g. FGS start blocked from background).
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .setInputData(
                workDataOf(
                    AssetDownloadWorker.KEY_HANDLE_KIND to handle.kind,
                    AssetDownloadWorker.KEY_HANDLE_ID to handle.id,
                )
            )
            .addTag(TAG_DOWNLOAD)
            .addTag(handle.tag)
            .build()
        wm.enqueueUniqueWork(handle.uniqueWorkName, ExistingWorkPolicy.KEEP, request)
        return handle.id
    }

    /** Cancel the running work; `.part` stays so the next enqueue resumes via Range. */
    fun pause(handle: AssetHandle) {
        wm.cancelUniqueWork(handle.uniqueWorkName)
    }

    /** Cancel + ask the source to wipe everything on disk for this asset. */
    fun cancel(handle: AssetHandle) {
        wm.cancelUniqueWork(handle.uniqueWorkName)
        scope.launch {
            val (source, asset) = sources.resolve(handle) ?: return@launch
            source.delete(asset)
        }
    }

    // Re-evaluates on source.changes so deletions (no WorkManager state change) still flip rows.
    fun observe(handle: AssetHandle): Flow<DownloadStatus> = flow {
        val resolved = sources.resolve(handle)
        if (resolved == null) {
            emit(DownloadStatus.Idle(handle.id))
            return@flow
        }
        val (source, asset) = resolved
        val work = wm.getWorkInfosForUniqueWorkFlow(handle.uniqueWorkName)
        val ticks = source.changes.onStart { emit(Unit) }
        emitAll(combine(work, ticks) { infos, _ -> computeStatus(infos, source, asset) })
        // The repo's own rule: a flow whose emissions do I/O carries its dispatcher, rather than trusting
        // whoever collects it.
    }.flowOn(ioDispatcher)

    // On-disk install state is authoritative; nothing in WorkManager can revoke a downloaded file.
    private suspend fun computeStatus(
        infos: List<WorkInfo>,
        source: AssetSource,
        asset: DownloadAsset,
    ): DownloadStatus {
        if (source.isInstalled(asset)) return DownloadStatus.Completed(asset.handle.id)

        val active = infos.firstOrNull { !it.state.isFinished }
        if (active != null) return mapActiveWorkInfo(active, asset)

        val partLen = fs.metadataOrNull(asset.partFile)?.size ?: -1L
        if (partLen >= 0) {
            return DownloadStatus.Paused(asset.handle.id, partLen, asset.sizeBytes ?: -1L)
        }

        val terminal = infos.firstOrNull()
            ?: return DownloadStatus.Idle(asset.handle.id)
        return when (terminal.state) {
            // SUCCEEDED records outlive their files; surface Idle so the row re-arms.
            WorkInfo.State.SUCCEEDED -> DownloadStatus.Idle(asset.handle.id)
            // A zero-progress failure (auth/HTTP/network) isn't kept as an error. WorkManager persists
            // FAILED WorkInfo across restarts, which made rows stick orange ("Failed: HTTP 401") long
            // after the transient cause was gone. Re-arm to Idle — the row reads as "not downloaded" and
            // the user can just tap Download again. (A partial download that failed shows Paused above.)
            WorkInfo.State.FAILED -> DownloadStatus.Idle(asset.handle.id)
            WorkInfo.State.CANCELLED -> DownloadStatus.Cancelled(asset.handle.id)
            else -> DownloadStatus.Idle(asset.handle.id)
        }
    }

    private fun mapActiveWorkInfo(info: WorkInfo, asset: DownloadAsset): DownloadStatus {
        return when (info.state) {
            WorkInfo.State.RUNNING -> {
                // Surface post-download stage (Verifying/Extracting/Installing) so UI doesn't
                // stick at 100%; stage set via setProgress() inside the worker.
                val stage = info.progress.getString(AssetDownloadWorker.KEY_STAGE)
                if (stage != null) {
                    return mapStageToFinalizing(asset.handle.id, stage)
                }
                val partLen = fs.metadataOrNull(asset.partFile)?.size ?: 0L
                val d = info.progress.getLong(AssetDownloadWorker.KEY_DOWNLOADED, partLen)
                val t = info.progress.getLong(AssetDownloadWorker.KEY_TOTAL, asset.sizeBytes ?: -1L)
                val bps = info.progress.getLong(AssetDownloadWorker.KEY_BYTES_PER_SEC, 0L)
                DownloadStatus.InProgress(asset.handle.id, d, t, bps)
            }
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                // Mid-retry hiccup: existing bytes → keep UI in-flight, else show queued.
                val partBytes = fs.metadataOrNull(asset.partFile)?.size ?: 0L
                if (partBytes > 0) {
                    DownloadStatus.InProgress(asset.handle.id, partBytes, asset.sizeBytes ?: -1L, 0L)
                } else {
                    DownloadStatus.Queued(asset.handle.id)
                }
            }
            else -> DownloadStatus.Queued(asset.handle.id)
        }
    }

    private fun mapStageToFinalizing(id: String, raw: String): DownloadStatus {
        val stage = when (raw) {
            AssetDownloadWorker.STAGE_VERIFYING -> DownloadStatus.Finalizing.Stage.Verifying
            AssetDownloadWorker.STAGE_EXTRACTING -> DownloadStatus.Finalizing.Stage.Extracting
            AssetDownloadWorker.STAGE_INSTALLING -> DownloadStatus.Finalizing.Stage.Installing
            else -> DownloadStatus.Finalizing.Stage.Verifying
        }
        return DownloadStatus.Finalizing(id, stage)
    }

    companion object {
        private const val TAG_DOWNLOAD = "asset_download"
    }
}
