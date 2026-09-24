package com.sabreware.aide.data.speech

import com.sabreware.aide.core.domain.speech.SpeechAssetSpec
import kotlinx.coroutines.flow.Flow
import okio.Path

/**
 * Speech-asset on-disk layout, as okio paths (a `filesDir/speech/` subtree so LLM and speech catalog ids
 * can't collide on disk). commonMain surface — no `java.io.File` — so the download pipeline and the models
 * screen resolve paths identically on every target. [SpeechAssetStorageImpl] is the only implementation;
 * the port survives so tests can fake a disk.
 */
interface SpeechAssetStorage {

    /** Fires on explicit deletes (a download-status source alone can't observe them). */
    val changes: Flow<Unit>

    /** Nudge [changes] after an out-of-band mutation on disk (a bundle extraction finishing). */
    fun signalChange()

    /** The downloaded file (archive or raw `.onnx`) for [spec]. */
    fun assetFile(spec: SpeechAssetSpec): Path

    /** The in-flight `.part` for [spec]. */
    fun partFile(spec: SpeechAssetSpec): Path

    /** Directory a `.tar.bz2` bundle extracts into. */
    fun extractedDir(spec: SpeechAssetSpec): Path

    /** True once [spec]'s bundle is fully extracted + install-marked (family-aware). */
    fun hasExtracted(spec: SpeechAssetSpec): Boolean

    /** True when [spec]'s raw asset file is present and non-empty. */
    fun hasFile(spec: SpeechAssetSpec): Boolean

    /** Wipe file + part + extracted dir for [spec] and emit on [changes]. */
    fun delete(spec: SpeechAssetSpec)
}
