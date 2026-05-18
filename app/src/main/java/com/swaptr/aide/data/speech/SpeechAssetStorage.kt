package com.swaptr.aide.data.speech

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// Separate filesDir/speech/ subtree so LLM and speech catalog ids can't collide on disk.
@Singleton
class SpeechAssetStorage @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    val rootDir: File = File(appContext.filesDir, ROOT).apply { mkdirs() }

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // Fires on explicit deletes (WorkManager state alone can't observe them).
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    fun assetFile(spec: SpeechAssetSpec): File =
        File(rootDir, requireNotNull(spec.fileName) { "spec.fileName required" })

    fun partFile(spec: SpeechAssetSpec): File =
        File(rootDir, "${requireNotNull(spec.fileName)}.part")

    fun extractedDir(spec: SpeechAssetSpec): File =
        File(rootDir, spec.extractedDirName)

    // INSTALL_MARKER is authoritative; written last by the extractor to avoid mid-extract
    // race where tokens.txt appears before the tar finishes.
    fun hasExtracted(spec: SpeechAssetSpec): Boolean {
        val dir = extractedDir(spec)
        if (!dir.isDirectory) return false
        if (!File(dir, SpeechBundleExtractor.INSTALL_MARKER_NAME).isFile) return false
        if (!File(dir, "tokens.txt").isFile) return false
        return when (spec.family) {
            SpeechAssetFamily.KOKORO, SpeechAssetFamily.KITTEN ->
                File(dir, "voices.bin").isFile
            else -> true
        }
    }

    // For one-shot back-fill of pre-marker extracted dirs on upgrade.
    fun looksFullyExtractedWithoutMarker(spec: SpeechAssetSpec): Boolean {
        val dir = extractedDir(spec)
        if (!dir.isDirectory) return false
        if (File(dir, SpeechBundleExtractor.INSTALL_MARKER_NAME).isFile) return false
        if (!File(dir, "tokens.txt").isFile) return false
        return when (spec.family) {
            SpeechAssetFamily.KOKORO, SpeechAssetFamily.KITTEN ->
                File(dir, "voices.bin").isFile
            else -> true
        }
    }

    fun writeInstallMarker(spec: SpeechAssetSpec) {
        val marker = File(extractedDir(spec), SpeechBundleExtractor.INSTALL_MARKER_NAME)
        if (!marker.exists()) {
            runCatching { marker.outputStream().close() }
        }
    }

    fun hasFile(spec: SpeechAssetSpec): Boolean = assetFile(spec).exists() && assetFile(spec).length() > 0

    fun delete(spec: SpeechAssetSpec) {
        runCatching { assetFile(spec).delete() }
        runCatching { partFile(spec).delete() }
        runCatching { extractedDir(spec).deleteRecursively() }
        _changes.tryEmit(Unit)
    }

    companion object {
        private const val ROOT = "speech"
    }
}
