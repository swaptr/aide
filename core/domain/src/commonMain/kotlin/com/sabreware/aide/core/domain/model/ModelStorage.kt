package com.sabreware.aide.core.domain.model

import kotlinx.coroutines.flow.Flow
import okio.Path

/**
 * On-device LLM weight files: where they live, whether they are there, and how to remove them.
 *
 * Paths are okio, not `java.io.File` — one path currency below the UI, which is what lets the layout live in
 * one place instead of once per JVM. The layout itself is `{root}/{normalizedId}/{commitHash}/{fileName}`, so
 * re-pinning a commit never collides with the artifact already on disk.
 *
 * A remote spec always reports "downloaded" ([ModelDescriptor.requiresDownload] short-circuits), so the model
 * gate flips Ready as soon as a provider is configured — on every platform, with no files involved.
 *
 * **Every member that touches the disk is `suspend`.** They were plain functions backed by synchronous okio
 * syscalls, which meant a caller could not be dispatcher-correct without reading the implementation — and
 * one did not: the send flow called [isDownloaded] on `Main.immediate`, so every message the user sent did
 * two blocking stats on the main thread. A suspend signature makes the cost visible at the call site and
 * lets the implementation choose the dispatcher.
 */
interface ModelStorage {

    /** Fires on deletes and imports; a scheduler's own state cannot observe either. */
    val changes: Flow<Unit>

    /**
     * Resolved on-disk path for the bundle: the *current-commit* path if present, else the first
     * [ModelSpec.previousCommits] entry whose file exists, else the current-commit path so a caller can
     * still build a download target.
     */
    suspend fun modelFile(spec: ModelSpec): Path

    /** The in-flight `.part` for [spec]. Creates the directory: the caller is about to write into it. */
    suspend fun partFile(spec: ModelSpec): Path

    /** Destination for an imported model's file (the current-commit dir). Creates the directory. */
    suspend fun importTarget(spec: ModelSpec): Path

    /** The bundle is present + not mid-download (remote specs → always true). */
    suspend fun isDownloaded(spec: ModelSpec): Boolean

    /** The pinned commit isn't on disk but an older [ModelSpec.previousCommits] one is (stale download). */
    suspend fun hasUpdate(spec: ModelSpec): Boolean

    /** The current-commit file exists on disk (used to distinguish imported/present models). */
    suspend fun isPresent(spec: ModelSpec): Boolean

    /** Bytes on disk for [spec] — the finished file, else the `.part`, else 0. */
    suspend fun downloadedBytes(spec: ModelSpec): Long

    /** Removes a downloadable model's file + part. No-op for a spec with nothing to download. */
    suspend fun delete(spec: ModelSpec): Boolean

    /**
     * Removes the file + part regardless of [ModelDescriptor.requiresDownload]. [delete] declines for a spec
     * that was never downloaded, but an IMPORTED model is on disk and must still be removable.
     */
    suspend fun deleteFile(spec: ModelSpec): Boolean
}
