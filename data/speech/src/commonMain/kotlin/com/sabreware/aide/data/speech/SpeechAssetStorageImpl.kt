package com.sabreware.aide.data.speech

import com.sabreware.aide.core.common.storage.PlatformPaths
import com.sabreware.aide.core.domain.speech.SpeechAssetFamily
import com.sabreware.aide.core.domain.speech.SpeechAssetSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okio.FileSystem
import okio.Path

/**
 * The one [SpeechAssetStorage]: a `filesDir/speech/` subtree (kept apart from the LLM tree so catalog ids
 * cannot collide on disk).
 *
 * There is nothing platform-shaped left in it. Android and desktop ran separate copies only because one was
 * written against `java.io.File` + `Context.filesDir` and the other against okio + [PlatformPaths] — and
 * `AndroidPlatformPaths.filesDir` IS `Context.filesDir`, so the two resolved the same paths by different
 * routes. Written in okio it is plain commonMain, not even JVM-shared.
 */
class SpeechAssetStorageImpl(
    paths: PlatformPaths,
    private val fs: FileSystem = FileSystem.SYSTEM,
) : SpeechAssetStorage {

    // No `createDirectories` here. The single is built while the Koin graph is constructed — inside
    // Application.onCreate on Android — so a blocking mkdir in the constructor ran on the main thread
    // before the first frame. The directory is created where something is about to be written instead.
    private val root: Path = paths.filesDir / ROOT

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val changes: Flow<Unit> = _changes.asSharedFlow()

    override fun signalChange() {
        _changes.tryEmit(Unit)
    }

    override fun assetFile(spec: SpeechAssetSpec): Path =
        root / requireNotNull(spec.fileName) { "spec.fileName required" }

    // The download target, so this is the one path method that makes sure the root exists — replacing the
    // constructor's eager mkdir. One `createDirectories` on a directory that already exists is a no-op.
    override fun partFile(spec: SpeechAssetSpec): Path =
        root.also { fs.createDirectories(it) } / "${requireNotNull(spec.fileName)}.part"

    override fun extractedDir(spec: SpeechAssetSpec): Path =
        root / spec.extractedDirName

    // The install marker is authoritative and written LAST by the extractor: without it a mid-extract crash
    // leaves tokens.txt in place and the bundle reads as installed.
    override fun hasExtracted(spec: SpeechAssetSpec): Boolean {
        val dir = extractedDir(spec)
        if (fs.metadataOrNull(dir)?.isDirectory != true) return false
        if (!fs.exists(dir / INSTALL_MARKER_NAME)) return false
        if (!fs.exists(dir / "tokens.txt")) return false
        // The weights themselves, non-empty. The marker plus tokens.txt used to be the whole check, so a
        // bundle whose .onnx was truncated by an interrupted write passed availability, JNI threw at load,
        // and the user got a generic failure instead of a demotion to the next engine.
        if (!hasNonEmptyWeights(dir)) return false
        return when (spec.family) {
            SpeechAssetFamily.KOKORO, SpeechAssetFamily.KITTEN -> hasNonEmpty(dir / "voices.bin")
            else -> true
        }
    }

    private fun hasNonEmptyWeights(dir: Path): Boolean =
        runCatching { fs.list(dir) }.getOrNull()
            ?.any { it.name.endsWith(WEIGHTS_EXTENSION) && hasNonEmpty(it) } == true

    private fun hasNonEmpty(path: Path): Boolean = (fs.metadataOrNull(path)?.size ?: 0L) > 0L

    override fun hasFile(spec: SpeechAssetSpec): Boolean =
        (fs.metadataOrNull(assetFile(spec))?.size ?: 0L) > 0L

    override fun delete(spec: SpeechAssetSpec) {
        runCatching { fs.delete(assetFile(spec), mustExist = false) }
        runCatching { fs.delete(partFile(spec), mustExist = false) }
        runCatching { fs.deleteRecursively(extractedDir(spec), mustExist = false) }
        signalChange()
    }

    companion object {
        // Every Sherpa family ships its model as ONNX; a bundle with none has not really been installed.
        private const val WEIGHTS_EXTENSION = ".onnx"
        private const val ROOT = "speech"

        /** Written last by the bundle extractor; its presence is what "installed" means. */
        const val INSTALL_MARKER_NAME = ".install_complete"
    }
}
