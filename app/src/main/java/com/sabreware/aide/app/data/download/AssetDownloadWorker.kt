package com.sabreware.aide.app.data.download

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sabreware.aide.core.domain.download.AssetHandle
import com.sabreware.aide.core.domain.download.AssetSource
import com.sabreware.aide.core.domain.download.AssetSourceRegistry
import com.sabreware.aide.core.domain.download.DownloadAsset
import com.sabreware.aide.data.download.DownloadEngine
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

// Drives every asset download (LLM + speech), resolved by (kind, id) through the Koin-provided sources map.
class AssetDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val downloadEngine: DownloadEngine by inject()
    private val sources: AssetSourceRegistry by inject()

    private suspend fun resolve(): Pair<AssetSource, DownloadAsset>? {
        val kind = inputData.getString(KEY_HANDLE_KIND) ?: return null
        val id = inputData.getString(KEY_HANDLE_ID) ?: return null
        return sources.resolve(AssetHandle(kind, id))
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
            pauseAssetId = id.ifBlank { null },
            pauseAssetKind = resolved?.second?.handle?.kind,
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
            notifId, asset.displayName, asset.handle.id, asset.handle.kind,
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
                        notifId, asset.displayName, asset.handle.id, asset.handle.kind,
                        text = humanProgress(downloaded, total, bps),
                        progress = pct,
                        indeterminate = false,
                    )
                }
            }
            setProgress(workDataOf(KEY_STAGE to STAGE_VERIFYING))
            trySetForeground(
                notifId, asset.displayName, asset.handle.id, asset.handle.kind,
                text = "Verifying…", progress = 100, indeterminate = true,
            )
            if (!source.verify(asset)) {
                return Result.failure(workDataOf(KEY_ERROR to "Verification failed for ${asset.handle.id}"))
            }
            setProgress(workDataOf(KEY_STAGE to STAGE_EXTRACTING))
            trySetForeground(
                notifId, asset.displayName, asset.handle.id, asset.handle.kind,
                text = "Extracting…", progress = 100, indeterminate = true,
            )
            source.postProcess(asset)
            setProgress(workDataOf(KEY_STAGE to STAGE_INSTALLING))
            trySetForeground(
                notifId, asset.displayName, asset.handle.id, asset.handle.kind,
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
        assetKind: String,
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
            pauseAssetId = assetId,
            pauseAssetKind = assetKind,
        )
        return try {
            setForeground(info)
            FgResult.Ok
        } catch (notAllowed: ForegroundServiceStartNotAllowedException) {
            // Started from the background with no exemption — the caller retries as non-expedited work.
            FgResult.NotAllowed
        } catch (t: Throwable) {
            Log.w(TAG, "setForeground failed for $assetId: ${t.message}")
            FgResult.Other(t)
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
    }
}
