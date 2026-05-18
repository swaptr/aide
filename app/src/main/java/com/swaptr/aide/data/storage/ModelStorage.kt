package com.swaptr.aide.data.storage

import android.content.Context
import com.swaptr.aide.data.catalog.ModelSpec
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

// {root}/{normalizedId}/{commitHash}/{fileName} so re-pinning a commit doesn't collide
// with the previous artifact. Remote specs short-circuit ("downloaded" = true).
class ModelStorage(context: Context) {

    private val root: File = File(context.filesDir, "models").apply { mkdirs() }

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // Fires on deletions (WorkManager state alone can't observe them).
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /**
     * Resolved on-disk path for the bundle. Returns the *current-commit* path if present,
     * otherwise the first `spec.previousCommits` entry whose file exists, otherwise falls
     * back to the current-commit path so callers can still build a download target.
     * Mirrors gallery's `ModelManagerViewModel.isModelDownloaded` probe.
     */
    fun modelFile(spec: ModelSpec): File {
        val current = File(modelDir(spec), requireLocalFileName(spec))
        if (current.exists()) return current
        for (prev in spec.previousCommits) {
            val candidate = File(modelDirForCommit(spec, prev), requireLocalFileName(spec))
            if (candidate.exists()) return candidate
        }
        return current
    }

    fun partFile(spec: ModelSpec): File =
        File(modelDir(spec), "${requireLocalFileName(spec)}.part")

    // Remote specs always "downloaded" so the gate flips Ready as soon as a provider configures.
    fun isDownloaded(spec: ModelSpec): Boolean {
        if (!spec.requiresDownload) return true
        // modelFile() already probes previousCommits, so exists() is enough.
        // Guard with !partFile.exists() so an in-progress current-commit download
        // doesn't claim Ready while only the legacy artifact is fully on disk.
        return modelFile(spec).exists() && !partFile(spec).exists()
    }

    fun downloadedBytes(spec: ModelSpec): Long {
        if (!spec.requiresDownload) return 0L
        val full = modelFile(spec)
        if (full.exists()) return full.length()
        val part = partFile(spec)
        return if (part.exists()) part.length() else 0L
    }

    fun delete(spec: ModelSpec): Boolean {
        if (!spec.requiresDownload) return false
        val dir = modelDir(spec)
        val a = File(dir, requireLocalFileName(spec)).delete()
        val b = File(dir, "${requireLocalFileName(spec)}.part").delete()
        _changes.tryEmit(Unit)
        return a || b
    }

    fun rootDir(): File = root

    /** Per-spec directory: `{root}/{normalizedId}/{commitHash}`. */
    private fun modelDir(spec: ModelSpec): File =
        modelDirForCommit(spec, spec.commitHash)

    private fun modelDirForCommit(spec: ModelSpec, commit: String?): File {
        val version = commit?.takeIf { it.isNotBlank() } ?: "default"
        return File(File(root, normalizeId(spec.id)), version).apply { mkdirs() }
    }

    private fun normalizeId(id: String): String =
        id.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')

    private fun requireLocalFileName(spec: ModelSpec): String =
        spec.fileName ?: throw IllegalArgumentException(
            "ModelSpec '${spec.id}' has no fileName (provider=${spec.provider})",
        )
}
