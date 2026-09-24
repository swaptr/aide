package com.sabreware.aide.data.model

import com.sabreware.aide.core.domain.download.DownloadAsset
import com.sabreware.aide.core.domain.download.AssetHandle
import com.sabreware.aide.core.domain.download.AssetSource
import com.sabreware.aide.core.domain.download.AssetKind
import com.sabreware.aide.core.domain.model.ModelStorage
import com.sabreware.aide.core.domain.catalog.ModelCatalog
import kotlinx.coroutines.flow.Flow
import okio.FileSystem

// LLM weights. No postProcess — the engine loads the downloaded file directly.
class ModelAssetSource(
    private val storage: ModelStorage,
    private val modelCatalog: ModelCatalog,
    private val fs: FileSystem = FileSystem.SYSTEM,
) : AssetSource {

    override val kind: String = AssetKind.MODEL

    override suspend fun resolve(id: String): DownloadAsset? {
        val spec = modelCatalog.findById(id) ?: return null
        val url = spec.downloadUrl ?: return null
        spec.fileName ?: return null
        return DownloadAsset(
            handle = AssetHandle(kind, spec.id),
            displayName = spec.displayName,
            downloadUrl = url,
            partFile = storage.partFile(spec),
            finalFile = storage.modelFile(spec),
            sizeBytes = spec.sizeBytes,
        )
    }

    override suspend fun verify(asset: DownloadAsset): Boolean =
        (fs.metadataOrNull(asset.finalFile)?.size ?: 0L) > 0L

    // Mirrors ModelStorage.isDownloaded: the final file exists and no `.part` is still in flight.
    override suspend fun isInstalled(asset: DownloadAsset): Boolean =
        fs.exists(asset.finalFile) && !fs.exists(asset.partFile)

    override suspend fun delete(asset: DownloadAsset) {
        val spec = modelCatalog.findById(asset.handle.id)
        if (spec != null) {
            storage.delete(spec)
            return
        }
        runCatching { fs.delete(asset.partFile, mustExist = false) }
        runCatching { fs.delete(asset.finalFile, mustExist = false) }
    }

    override val changes: Flow<Unit> get() = storage.changes
}
