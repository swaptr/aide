package com.sabreware.aide.data.model.storage

import com.sabreware.aide.core.common.storage.PlatformPaths
import com.sabreware.aide.core.domain.model.ModelSpec
import com.sabreware.aide.core.domain.model.ModelStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/**
 * The one [ModelStorage]. `{filesDir}/models/{normalizedId}/{commitHash}/{fileName}`, in okio, so the layout
 * is stated once instead of once per JVM — the same move that collapsed the speech storages. Nothing here is
 * platform-shaped: `AndroidPlatformPaths.filesDir` IS `Context.filesDir`, so an existing install's files are
 * exactly where they were.
 *
 * This is also what makes on-device models on desktop a wiring change rather than a port to write.
 */
class ModelStorageImpl(
    paths: PlatformPaths,
    /**
     * Where the disk work runs. Every suspending member hops here, which is the point of the port being
     * suspending at all — the caller no longer has to know that "is this downloaded?" is two syscalls.
     */
    private val ioDispatcher: CoroutineDispatcher,
    private val fs: FileSystem = FileSystem.SYSTEM,
) : ModelStorage {

    // No `createDirectories` in the constructor. This single is built during Koin graph construction, which
    // on Android happens inside Application.onCreate — so a blocking mkdir here ran on the main thread
    // before the first frame. Directories are created where something is about to be written instead.
    private val root: Path = paths.filesDir / ROOT

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val changes: Flow<Unit> = _changes.asSharedFlow()

    override suspend fun modelFile(spec: ModelSpec): Path = withContext(ioDispatcher) {
        resolveModelFile(spec)
    }

    // A path a caller is about to WRITE to, so the directory is created here rather than by the resolver.
    override suspend fun partFile(spec: ModelSpec): Path = withContext(ioDispatcher) {
        writableDir(spec) / "${requireLocalFileName(spec)}.part"
    }

    override suspend fun importTarget(spec: ModelSpec): Path = withContext(ioDispatcher) {
        writableDir(spec) / requireLocalFileName(spec)
    }

    // Remote specs always "downloaded" so the gate flips Ready as soon as a provider configures.
    override suspend fun isDownloaded(spec: ModelSpec): Boolean = withContext(ioDispatcher) {
        if (!spec.requiresDownload) return@withContext true
        // resolveModelFile() already probes previousCommits, so existence is enough. Guard with the absent
        // `.part` so an in-progress current-commit download doesn't claim Ready while only an older artifact
        // is fully on disk.
        fs.exists(resolveModelFile(spec)) && !fs.exists(partPath(spec))
    }

    override suspend fun hasUpdate(spec: ModelSpec): Boolean = withContext(ioDispatcher) {
        if (!spec.requiresDownload) return@withContext false
        if (fs.exists(dirFor(spec, spec.commitHash) / requireLocalFileName(spec))) return@withContext false
        spec.previousCommits.any {
            fs.exists(dirFor(spec, it) / requireLocalFileName(spec))
        }
    }

    override suspend fun isPresent(spec: ModelSpec): Boolean = withContext(ioDispatcher) {
        fs.exists(resolveModelFile(spec))
    }

    override suspend fun downloadedBytes(spec: ModelSpec): Long = withContext(ioDispatcher) {
        if (!spec.requiresDownload) return@withContext 0L
        fs.metadataOrNull(resolveModelFile(spec))?.size
            ?: fs.metadataOrNull(partPath(spec))?.size
            ?: 0L
    }

    override suspend fun delete(spec: ModelSpec): Boolean {
        if (!spec.requiresDownload) return false
        return deleteFile(spec)
    }

    override suspend fun deleteFile(spec: ModelSpec): Boolean = withContext(ioDispatcher) {
        val dir = dirFor(spec, spec.commitHash)
        val a = runCatching { fs.delete(dir / requireLocalFileName(spec), mustExist = true) }.isSuccess
        val b = runCatching { fs.delete(dir / "${requireLocalFileName(spec)}.part", mustExist = true) }.isSuccess
        _changes.tryEmit(Unit)
        a || b
    }

    /** The current-commit file if present, else the newest previous commit that is, else current. Pure. */
    private fun resolveModelFile(spec: ModelSpec): Path {
        val current = dirFor(spec, spec.commitHash) / requireLocalFileName(spec)
        if (fs.exists(current)) return current
        for (prev in spec.previousCommits) {
            val candidate = dirFor(spec, prev) / requireLocalFileName(spec)
            if (fs.exists(candidate)) return candidate
        }
        return current
    }

    private fun partPath(spec: ModelSpec): Path =
        dirFor(spec, spec.commitHash) / "${requireLocalFileName(spec)}.part"

    /** Per-spec directory: `{root}/{normalizedId}/{commitHash}`. **Pure** — computing a path never touches
     *  the disk, so a read path costs no syscalls and no directory appears just because someone asked. */
    private fun dirFor(spec: ModelSpec, commit: String?): Path {
        val version = commit?.takeIf { it.isNotBlank() } ?: "default"
        return root / normalizeId(spec.id) / version
    }

    /** The current-commit directory, created — for the callers that are about to write into it. */
    private fun writableDir(spec: ModelSpec): Path =
        dirFor(spec, spec.commitHash).also { fs.createDirectories(it) }

    private fun normalizeId(id: String): String =
        id.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')

    private fun requireLocalFileName(spec: ModelSpec): String =
        spec.fileName ?: throw IllegalArgumentException(
            "ModelSpec '${spec.id}' has no fileName (provider=${spec.provider})",
        )

    private companion object {
        const val ROOT = "models"
    }
}
