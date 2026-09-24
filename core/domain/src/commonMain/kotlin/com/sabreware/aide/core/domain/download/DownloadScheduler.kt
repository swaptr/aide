package com.sabreware.aide.core.domain.download

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * The one way anything in the app asks for bytes to be fetched, whatever they are for.
 *
 * A caller names an asset by `(kind, id)` and observes its [DownloadStatus]; it never says where the file
 * goes or which URL it comes from. That is the registered
 * [com.sabreware.aide.data.download.AssetSource]'s job, so a new downloadable capability is one source
 * binding and nothing else — no second scheduler, no second repository port, no per-platform stub.
 *
 * Implementations differ only in what drives the transfer: Android runs a WorkManager job that survives
 * process death and shows a foreground notification, everything else runs a coroutine on an app scope.
 * Neither shape reaches the caller.
 *
 * `(kind, id)` rather than a bare id because ids are namespaced per [AssetKind] — an LLM "foo" and a speech
 * "foo" must not share a work key.
 */
interface DownloadScheduler {
    /** Start (or resume) the download; returns the asset id. Idempotent for an already-running id. */
    fun enqueue(kind: String, id: String): String

    fun observe(kind: String, id: String): Flow<DownloadStatus>

    /** Stop the transfer but keep the `.part` on disk so a later [enqueue] resumes via HTTP Range. */
    fun pause(kind: String, id: String)

    /** Stop the transfer AND wipe what landed on disk — the source decides what that means. */
    fun cancel(kind: String, id: String)
}

/**
 * Suspends until the download of `(kind, id)` settles: true once it has COMPLETED, false if it failed or was
 * cancelled. A pause keeps waiting — a paused download is resumed, not abandoned.
 */
suspend fun DownloadScheduler.awaitCompletion(kind: String, id: String): Boolean =
    observe(kind, id).first {
        it is DownloadStatus.Completed || it is DownloadStatus.Failed || it is DownloadStatus.Cancelled
    } is DownloadStatus.Completed
