package com.sabreware.aide.app.data.download

import com.sabreware.aide.core.domain.download.DownloadScheduler
import com.sabreware.aide.core.domain.download.DownloadStatus
import com.sabreware.aide.core.domain.download.AssetHandle
import kotlinx.coroutines.flow.Flow

/**
 * Android [DownloadScheduler]: a thin delegate to the WorkManager-backed [DownloadController], which resolves
 * paths through the same `AssetSourceRegistry` the coroutine scheduler uses. WorkManager is not
 * reimplemented — what it buys (surviving process death, a foreground-service notification) is the whole
 * reason this target has its own scheduler at all.
 */
class AndroidDownloadScheduler(
    private val controller: DownloadController,
) : DownloadScheduler {

    override fun enqueue(kind: String, id: String): String =
        controller.enqueue(AssetHandle(kind, id))

    override fun observe(kind: String, id: String): Flow<DownloadStatus> =
        controller.observe(AssetHandle(kind, id))

    override fun pause(kind: String, id: String) =
        controller.pause(AssetHandle(kind, id))

    override fun cancel(kind: String, id: String) =
        controller.cancel(AssetHandle(kind, id))
}
