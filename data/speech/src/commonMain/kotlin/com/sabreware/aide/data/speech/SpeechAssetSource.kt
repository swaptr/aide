package com.sabreware.aide.data.speech

import com.sabreware.aide.core.domain.download.DownloadAsset
import com.sabreware.aide.core.domain.download.AssetHandle
import com.sabreware.aide.core.domain.download.AssetSource
import com.sabreware.aide.core.domain.download.AssetKind
import com.sabreware.aide.core.domain.speech.SpeechAssetSpec
import kotlinx.coroutines.flow.Flow
import okio.FileSystem

/**
 * Sherpa-ONNX speech bundles. [postProcess] unpacks a `.tar.bz2`; a raw `.onnx` (the VAD) is used in place.
 *
 * Unpacking belongs here rather than in the asset repository because *every* scheduler runs a source's
 * post-process step. That is what removed the asset repository's platform seam: Android used to extract
 * inside its download worker while desktop extracted afterwards in the repository, and the repository had
 * to know which.
 */
class SpeechAssetSource(
    private val storage: SpeechAssetStorage,
    private val installer: SpeechBundleInstaller,
    private val fs: FileSystem = FileSystem.SYSTEM,
) : AssetSource {

    override val kind: String = AssetKind.SPEECH

    override suspend fun resolve(id: String): DownloadAsset? {
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

    // Sherpa archives ship at ~10 MB+; reject anything obviously truncated.
    override suspend fun verify(asset: DownloadAsset): Boolean =
        (fs.metadataOrNull(asset.finalFile)?.size ?: 0L) > 1024L

    override suspend fun postProcess(asset: DownloadAsset) {
        val spec = lookup(asset) ?: return
        installer.install(spec)
    }

    override suspend fun isInstalled(asset: DownloadAsset): Boolean {
        val spec = lookup(asset) ?: return fs.exists(asset.finalFile)
        return storage.hasExtracted(spec) || storage.hasFile(spec)
    }

    override suspend fun delete(asset: DownloadAsset) {
        val spec = lookup(asset)
        if (spec != null) {
            storage.delete(spec)
            return
        }
        runCatching { fs.delete(asset.partFile, mustExist = false) }
        runCatching { fs.delete(asset.finalFile, mustExist = false) }
    }

    override val changes: Flow<Unit> get() = storage.changes

    private fun lookup(asset: DownloadAsset): SpeechAssetSpec? =
        SpeechAssetCatalog.findById(asset.handle.id)
}
