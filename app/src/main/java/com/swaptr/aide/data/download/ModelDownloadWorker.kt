package com.swaptr.aide.data.download

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

// Name kept (not just-models) for WorkManager schedule continuity; renaming orphans
// in-flight work records on user devices.
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun downloadEngine(): DownloadEngine
        fun assetSources(): Map<String, @JvmSuppressWildcards AssetSource>
    }

    private val entry = EntryPointAccessors.fromApplication(
        applicationContext,
        WorkerEntryPoint::class.java,
    )
    private val downloadEngine: DownloadEngine = entry.downloadEngine()
    private val sources: Map<String, AssetSource> = entry.assetSources()

    private fun resolve(): Pair<AssetSource, DownloadAsset>? {
        val kind = inputData.getString(KEY_HANDLE_KIND)
            ?: inputData.getString(KEY_LEGACY_MODEL_ID)?.let { ModelAssetSource.KIND }
            ?: return null
        val id = inputData.getString(KEY_HANDLE_ID)
            ?: inputData.getString(KEY_LEGACY_MODEL_ID)
            ?: return null
        val source = sources[kind] ?: return null
        val asset = source.resolve(id) ?: return null
        return source to asset
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val resolved = resolve()
        val displayName = resolved?.second?.displayName ?: "download"
        val id = resolved?.second?.handle?.id.orEmpty()
        return DownloadNotifications.foregroundInfo(
            context = applicationContext,
            notificationId = notificationIdFor(id),
            workId = this.id,
            title = "Downloading $displayName",
            text = "Preparing…",
            progress = 0,
            total = 100,
            indeterminate = true,
            pauseModelId = id.ifBlank { null },
        )
    }

    override suspend fun doWork(): Result {
        val resolved = resolve() ?: return Result.failure(
            workDataOf(KEY_ERROR to "Unknown asset handle"),
        )
        val (source, asset) = resolved
        val authToken = inputData.getString(KEY_AUTH_TOKEN)
        val notifId = notificationIdFor(asset.handle.id)

        // FGS start can fail from background (process replacement/boot); retry preserves work.
        val initialFg = trySetForeground(
            notifId, asset.displayName, asset.handle.id,
            text = "Starting…", progress = 0, indeterminate = true,
        )
        if (initialFg is FgResult.NotAllowed) {
            Log.w(TAG, "FGS not allowed at start for ${asset.handle.id}; deferring via Result.retry()")
            return Result.retry()
        }

        return try {
            downloadEngine.downloadFile(
                url = asset.downloadUrl,
                authToken = authToken,
                partFile = asset.partFile,
                finalFile = asset.finalFile,
            ) { downloaded, total, bps ->
                setProgress(
                    workDataOf(
                        KEY_DOWNLOADED to downloaded,
                        KEY_TOTAL to total,
                        KEY_BYTES_PER_SEC to bps,
                    )
                )
                if (total > 0) {
                    val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                    trySetForeground(
                        notifId, asset.displayName, asset.handle.id,
                        text = humanProgress(downloaded, total, bps),
                        progress = pct,
                        indeterminate = false,
                    )
                }
            }
            setProgress(workDataOf(KEY_STAGE to STAGE_VERIFYING))
            trySetForeground(
                notifId, asset.displayName, asset.handle.id,
                text = "Verifying…", progress = 100, indeterminate = true,
            )
            if (!source.verify(asset)) {
                return Result.failure(workDataOf(KEY_ERROR to "Verification failed for ${asset.handle.id}"))
            }
            setProgress(workDataOf(KEY_STAGE to STAGE_EXTRACTING))
            trySetForeground(
                notifId, asset.displayName, asset.handle.id,
                text = "Extracting…", progress = 100, indeterminate = true,
            )
            source.postProcess(asset)
            setProgress(workDataOf(KEY_STAGE to STAGE_INSTALLING))
            trySetForeground(
                notifId, asset.displayName, asset.handle.id,
                text = "Installing…", progress = 100, indeterminate = true,
            )
            Result.success()
        } catch (ce: CancellationException) {
            // WorkManager surfaces cancellation as CANCELLED state — .part stays for resume.
            throw ce
        } catch (t: Throwable) {
            Result.failure(workDataOf(KEY_ERROR to (t.message ?: t::class.java.simpleName)))
        }
    }

    private sealed interface FgResult {
        data object Ok : FgResult
        data object NotAllowed : FgResult
        data class Other(val throwable: Throwable) : FgResult
    }

    private suspend fun trySetForeground(
        notifId: Int,
        displayName: String,
        assetId: String,
        text: String,
        progress: Int,
        indeterminate: Boolean,
    ): FgResult {
        val info = DownloadNotifications.foregroundInfo(
            context = applicationContext,
            notificationId = notifId,
            workId = id,
            title = "Downloading $displayName",
            text = text,
            progress = progress,
            total = 100,
            indeterminate = indeterminate,
            pauseModelId = assetId,
        )
        return try {
            setForeground(info)
            FgResult.Ok
        } catch (t: Throwable) {
            if (t is ForegroundServiceStartNotAllowedException) {
                FgResult.NotAllowed
            } else {
                Log.w(TAG, "setForeground failed for $assetId: ${t.message}")
                FgResult.Other(t)
            }
        }
    }

    private fun notificationIdFor(assetId: String): Int = 1000 + (assetId.hashCode() and 0x0FFF)

    private fun humanProgress(downloaded: Long, total: Long, bps: Long): String {
        fun fmt(b: Long): String {
            if (b < 1024) return "$b B"
            val u = arrayOf("KB", "MB", "GB", "TB"); var d = b.toDouble() / 1024.0; var i = 0
            while (d >= 1024.0 && i < u.lastIndex) { d /= 1024.0; i++ }
            return String.format(java.util.Locale.US, "%.1f %s", d, u[i])
        }
        val rate = if (bps > 0) " · ${fmt(bps)}/s" else ""
        return "${fmt(downloaded)} / ${fmt(total)}$rate"
    }

    companion object {
        private const val TAG = "DownloadWorker"
        const val KEY_HANDLE_KIND = "handle_kind"
        const val KEY_HANDLE_ID = "handle_id"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_BYTES_PER_SEC = "bps"
        const val KEY_ERROR = "error"
        const val KEY_STAGE = "stage"
        const val STAGE_VERIFYING = "verifying"
        const val STAGE_EXTRACTING = "extracting"
        const val STAGE_INSTALLING = "installing"

        // Back-compat: pre-(kind,id)-split records may still be enqueued with only this key.
        const val KEY_LEGACY_MODEL_ID = "model_id"
    }
}
