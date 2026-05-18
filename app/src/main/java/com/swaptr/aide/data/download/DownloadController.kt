package com.swaptr.aide.data.download

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
import com.swaptr.aide.data.catalog.ModelSpec
import com.swaptr.aide.data.speech.SpeechAssetSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import java.util.concurrent.TimeUnit

// Pause = cancel work, keep .part. Resume = re-enqueue KEEP; engine resumes via Range header.
// Cancel = wipe everything on disk for this asset.
class DownloadController(
    appContext: Context,
    private val sources: Map<String, @JvmSuppressWildcards AssetSource>,
) {
    private val context = appContext.applicationContext
    private val wm get() = WorkManager.getInstance(context)

    fun enqueue(handle: AssetHandle, authToken: String? = null): String {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
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
                    ModelDownloadWorker.KEY_HANDLE_KIND to handle.kind,
                    ModelDownloadWorker.KEY_HANDLE_ID to handle.id,
                    ModelDownloadWorker.KEY_AUTH_TOKEN to authToken,
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
        val source = sources[handle.kind] ?: return
        val asset = source.resolve(handle.id) ?: return
        source.delete(asset)
    }

    // Re-evaluates on source.changes so deletions (no WorkManager state change) still flip rows.
    fun observe(handle: AssetHandle): Flow<DownloadStatus> {
        val source = sources[handle.kind] ?: return flowOf(DownloadStatus.Idle(handle.id))
        val asset = source.resolve(handle.id) ?: return flowOf(DownloadStatus.Idle(handle.id))
        val work = wm.getWorkInfosForUniqueWorkFlow(handle.uniqueWorkName)
        val ticks = source.changes.onStart { emit(Unit) }
        return combine(work, ticks) { infos, _ -> computeStatus(infos, source, asset) }
    }

    // On-disk install state is authoritative; nothing in WorkManager can revoke a downloaded file.
    private fun computeStatus(
        infos: List<WorkInfo>,
        source: AssetSource,
        asset: DownloadAsset,
    ): DownloadStatus {
        if (source.isInstalled(asset)) return DownloadStatus.Completed(asset.handle.id)

        val active = infos.firstOrNull { !it.state.isFinished }
        if (active != null) return mapActiveWorkInfo(active, asset)

        val partLen = asset.partFile.takeIf { it.exists() }?.length() ?: -1L
        if (partLen >= 0) {
            return DownloadStatus.Paused(asset.handle.id, partLen, asset.sizeBytes ?: -1L)
        }

        val terminal = infos.firstOrNull()
            ?: return DownloadStatus.Idle(asset.handle.id)
        return when (terminal.state) {
            // SUCCEEDED records outlive their files; surface Idle so the row re-arms.
            WorkInfo.State.SUCCEEDED -> DownloadStatus.Idle(asset.handle.id)
            WorkInfo.State.FAILED -> {
                val err = terminal.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "unknown"
                DownloadStatus.Failed(asset.handle.id, err)
            }
            WorkInfo.State.CANCELLED -> DownloadStatus.Cancelled(asset.handle.id)
            else -> DownloadStatus.Idle(asset.handle.id)
        }
    }

    private fun mapActiveWorkInfo(info: WorkInfo, asset: DownloadAsset): DownloadStatus {
        return when (info.state) {
            WorkInfo.State.RUNNING -> {
                // Surface post-download stage (Verifying/Extracting/Installing) so UI doesn't
                // stick at 100%; stage set via setProgress() inside the worker.
                val stage = info.progress.getString(ModelDownloadWorker.KEY_STAGE)
                if (stage != null) {
                    return mapStageToFinalizing(asset.handle.id, stage)
                }
                val partLen = asset.partFile.takeIf { it.exists() }?.length() ?: 0L
                val d = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED, partLen)
                val t = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL, asset.sizeBytes ?: -1L)
                val bps = info.progress.getLong(ModelDownloadWorker.KEY_BYTES_PER_SEC, 0L)
                DownloadStatus.InProgress(asset.handle.id, d, t, bps)
            }
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                // Mid-retry hiccup: existing bytes → keep UI in-flight, else show queued.
                val part = asset.partFile
                if (part.exists() && part.length() > 0) {
                    DownloadStatus.InProgress(asset.handle.id, part.length(), asset.sizeBytes ?: -1L, 0L)
                } else {
                    DownloadStatus.Queued(asset.handle.id)
                }
            }
            else -> DownloadStatus.Queued(asset.handle.id)
        }
    }

    fun enqueue(spec: ModelSpec, authToken: String? = null): String =
        enqueue(AssetHandle(ModelAssetSource.KIND, spec.id), authToken)

    fun pause(modelId: String) =
        pause(AssetHandle(ModelAssetSource.KIND, modelId))

    fun cancel(modelId: String, spec: ModelSpec? = null) =
        cancel(AssetHandle(ModelAssetSource.KIND, modelId))

    fun observe(spec: ModelSpec): Flow<DownloadStatus> =
        observe(AssetHandle(ModelAssetSource.KIND, spec.id))

    fun enqueueSpeech(spec: SpeechAssetSpec, authToken: String? = null): String =
        enqueue(AssetHandle(SpeechAssetSource.KIND, spec.id), authToken)

    fun pauseSpeech(assetId: String) =
        pause(AssetHandle(SpeechAssetSource.KIND, assetId))

    fun cancelSpeech(spec: SpeechAssetSpec) =
        cancel(AssetHandle(SpeechAssetSource.KIND, spec.id))

    fun observeSpeech(spec: SpeechAssetSpec): Flow<DownloadStatus> =
        observe(AssetHandle(SpeechAssetSource.KIND, spec.id))

    private fun mapStageToFinalizing(id: String, raw: String): DownloadStatus {
        val stage = when (raw) {
            ModelDownloadWorker.STAGE_VERIFYING -> DownloadStatus.Finalizing.Stage.Verifying
            ModelDownloadWorker.STAGE_EXTRACTING -> DownloadStatus.Finalizing.Stage.Extracting
            ModelDownloadWorker.STAGE_INSTALLING -> DownloadStatus.Finalizing.Stage.Installing
            else -> DownloadStatus.Finalizing.Stage.Verifying
        }
        return DownloadStatus.Finalizing(id, stage)
    }

    companion object {
        private const val TAG_DOWNLOAD = "asset_download"
    }
}
