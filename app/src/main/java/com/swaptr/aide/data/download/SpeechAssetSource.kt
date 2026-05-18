package com.swaptr.aide.data.download

import com.swaptr.aide.data.speech.SpeechAssetCatalog
import com.swaptr.aide.data.speech.SpeechAssetSpec
import com.swaptr.aide.data.speech.SpeechAssetStorage
import com.swaptr.aide.data.speech.SpeechBundleExtractor
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

// Sherpa-ONNX speech bundle source. postProcess unpacks .tar.bz2; raw .onnx (VAD)
// bypasses extraction by virtue of the filename.
@Singleton
class SpeechAssetSource @Inject constructor(
    private val storage: SpeechAssetStorage,
) : AssetSource {
    override val kind: String = KIND

    override fun resolve(id: String): DownloadAsset? {
        val spec = SpeechAssetCatalog.findById(id) ?: return null
        val url = spec.downloadUrl ?: return null
        spec.fileName ?: return null
        return DownloadAsset(
            handle = AssetHandle(kind, spec.id),
            displayName = spec.displayName,
            downloadUrl = url,
            partFile = storage.partFile(spec),
            finalFile = storage.assetFile(spec),
            sizeBytes = spec.sizeBytes,
        )
    }

    override suspend fun verify(asset: DownloadAsset): Boolean {
        // Sherpa archives ship at ~10MB+; reject anything obviously truncated.
        return asset.finalFile.length() > 1024
    }

    override suspend fun postProcess(asset: DownloadAsset) {
        val spec = lookup(asset) ?: return
        val archiveLike = spec.fileName?.endsWith(".tar.bz2") == true
        if (!archiveLike) return
        SpeechBundleExtractor
            .extract(archive = asset.finalFile, targetDir = storage.extractedDir(spec))
            .onFailure { throw it }
    }

    override fun isInstalled(asset: DownloadAsset): Boolean {
        val spec = lookup(asset) ?: return asset.finalFile.exists()
        return storage.hasExtracted(spec) || storage.hasFile(spec)
    }

    override fun delete(asset: DownloadAsset) {
        val spec = lookup(asset) ?: return super.delete(asset)
        storage.delete(spec)
    }

    override val changes: Flow<Unit> get() = storage.changes

    private fun lookup(asset: DownloadAsset): SpeechAssetSpec? =
        SpeechAssetCatalog.findById(asset.handle.id)

    companion object { const val KIND = "speech" }
}
