package com.swaptr.aide.data.download

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// Hilt multibinding key is `kind`; `changes` re-emits status on deletions (which fire no
// WorkManager state change).
interface AssetSource {
    val kind: String

    fun resolve(id: String): DownloadAsset?

    suspend fun verify(asset: DownloadAsset): Boolean =
        asset.finalFile.length() > 0

    suspend fun postProcess(asset: DownloadAsset) = Unit

    fun isInstalled(asset: DownloadAsset): Boolean = asset.finalFile.exists()

    fun delete(asset: DownloadAsset) {
        asset.partFile.delete()
        asset.finalFile.delete()
    }

    val changes: Flow<Unit> get() = flowOf()
}
