package com.swaptr.aide.data.download

import com.swaptr.aide.data.catalog.ModelCatalog
import com.swaptr.aide.data.storage.ModelStorage
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

// LLM-weight source; no postProcess (LiteRtLmEngine loads the file directly).
@Singleton
class ModelAssetSource @Inject constructor(
    private val storage: ModelStorage,
) : AssetSource {
    override val kind: String = KIND

    override fun resolve(id: String): DownloadAsset? {
        val spec = ModelCatalog.findById(id) ?: return null
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

    override fun isInstalled(asset: DownloadAsset): Boolean {
        // Mirror ModelStorage.isDownloaded — final file exists and no in-flight .part.
        return asset.finalFile.exists() && !asset.partFile.exists()
    }

    override fun delete(asset: DownloadAsset) {
        val spec = ModelCatalog.findById(asset.handle.id) ?: return super.delete(asset)
        storage.delete(spec)
    }

    override val changes: Flow<Unit> get() = storage.changes

    companion object { const val KIND = "model" }
}
