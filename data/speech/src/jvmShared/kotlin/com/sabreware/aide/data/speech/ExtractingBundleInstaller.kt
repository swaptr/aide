package com.sabreware.aide.data.speech

import com.sabreware.aide.core.domain.speech.SpeechAssetSpec

/**
 * The JVM [SpeechBundleInstaller]: unpack the `.tar.bz2` with [SpeechBundleExtractor], then nudge
 * [SpeechAssetStorage.changes] so the rows re-read on-disk state. A raw `.onnx` (the VAD) needs nothing.
 *
 * JVM-shared rather than common because the extractor is `java.io`.
 */
class ExtractingBundleInstaller(
    private val storage: SpeechAssetStorage,
    private val extractor: SpeechBundleExtractor,
) : SpeechBundleInstaller {

    override suspend fun install(spec: SpeechAssetSpec) {
        if (spec.fileName?.endsWith(".tar.bz2") != true) return
        if (storage.hasExtracted(spec)) return
        extractor.extract(
            archive = storage.assetFile(spec).toFile(),
            targetDir = storage.extractedDir(spec).toFile(),
        ).onFailure { throw it }
        storage.signalChange()
    }
}
