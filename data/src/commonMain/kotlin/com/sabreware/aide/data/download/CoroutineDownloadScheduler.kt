package com.sabreware.aide.data.download

import com.sabreware.aide.core.domain.download.AssetHandle
import com.sabreware.aide.core.domain.download.AssetSourceRegistry
import com.sabreware.aide.core.domain.download.DownloadScheduler
import com.sabreware.aide.core.domain.download.DownloadStatus
import com.sabreware.aide.core.domain.util.AideLog
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * [DownloadScheduler] for a target with no OS work queue: one coroutine per `(kind, id)` on an injected app
 * scope, progress in an in-memory flow. Cancellation is job cancellation — the `.part` survives, so a later
 * [enqueue] resumes via HTTP Range.
 *
 * Terminal status is written from the coroutine's own catch/finally (the last writer, strictly after the
 * final progress tick), and [pause]/[cancel] only record an intent and cancel the job, so they never race
 * the progress updates.
 *
 * It resolves paths through the [AssetSourceRegistry] exactly as the Android WorkManager scheduler does —
 * that shared resolution is what lets a caller name an asset by `(kind, id)` alone.
 */
class CoroutineDownloadScheduler(
    private val engine: DownloadEngine,
    private val sources: AssetSourceRegistry,
    private val scope: CoroutineScope,
    /** Where the disk work — resolving a job, stat-ing an installed file — runs. */
    private val ioDispatcher: CoroutineDispatcher,
) : DownloadScheduler {

    private enum class Intent { PAUSE, CANCEL }

    // observe()/enqueue() are called from arbitrary threads (UI, app scope, a worker), so the three maps
    // are guarded by one monitor — atomicfu's, because this is commonMain.
    private val lock = SynchronizedObject()
    private val states = mutableMapOf<String, MutableStateFlow<DownloadStatus>>()
    private val jobs = mutableMapOf<String, Job>()
    private val intents = mutableMapOf<String, Intent>()

    private fun key(kind: String, id: String) = "$kind/$id"

    private fun stateFor(kind: String, id: String): MutableStateFlow<DownloadStatus> =
        synchronized(lock) { states.getOrPut(key(kind, id)) { MutableStateFlow(DownloadStatus.Idle(id)) } }

    override fun enqueue(kind: String, id: String, authToken: String?): String {
        val handle = AssetHandle(kind, id)
        val k = key(kind, id)
        val state = stateFor(kind, id)
        synchronized(lock) {
            if (jobs[k]?.isActive == true) return id // already running → KEEP
            intents.remove(k)
            state.value = DownloadStatus.Queued(id)
            // Resolution moved INSIDE the job: it asks storage where the bytes go and creates the target
            // directory, which is disk work and no longer pretends otherwise. Queued is already published,
            // so the row shows the right thing while that happens.
            jobs[k] = scope.launch { run(k, handle, authToken, state) }
        }
        return id
    }

    private suspend fun run(
        k: String,
        handle: AssetHandle,
        authToken: String?,
        state: MutableStateFlow<DownloadStatus>,
    ) {
        val id = handle.id
        val resolved = sources.resolve(handle)
        if (resolved == null) {
            AideLog.w(TAG, "no asset source resolved ${handle.kind}/$id")
            state.value = DownloadStatus.Failed(id, "No download source for $id")
            synchronized(lock) { jobs.remove(k) }
            return
        }
        val (source, asset) = resolved
        try {
            engine.downloadFile(
                url = asset.downloadUrl,
                authToken = authToken,
                partFile = asset.partFile,
                finalFile = asset.finalFile,
            ) { downloaded, total, bps ->
                state.value = DownloadStatus.InProgress(id, downloaded, total, bps)
            }
            state.value = DownloadStatus.Finalizing(id, DownloadStatus.Finalizing.Stage.Verifying)
            if (!source.verify(asset)) {
                state.value = DownloadStatus.Failed(id, "Verification failed for $id")
                return
            }
            state.value = DownloadStatus.Finalizing(id, DownloadStatus.Finalizing.Stage.Extracting)
            source.postProcess(asset)
            state.value = DownloadStatus.Completed(id)
        } catch (ce: CancellationException) {
            val cur = state.value
            state.value = when (synchronized(lock) { intents.remove(k) }) {
                // Pause keeps the .part; surface the last-known bytes so the row reads "resumable".
                Intent.PAUSE -> if (cur is DownloadStatus.InProgress) {
                    DownloadStatus.Paused(id, cur.downloadedBytes, cur.totalBytes)
                } else {
                    DownloadStatus.Idle(id)
                }
                // Cancel (or a scope teardown) → re-arm to Idle; cancel() already wiped the disk.
                else -> DownloadStatus.Idle(id)
            }
            throw ce
        } catch (t: Throwable) {
            state.value = DownloadStatus.Failed(id, t.message ?: "download failed")
        } finally {
            synchronized(lock) { jobs.remove(k) }
        }
    }

    // On-disk state is authoritative — a stored file outlives this scheduler's own record of the download,
    // which resets to Idle on restart. Same rule the WorkManager scheduler applies.
    override fun observe(kind: String, id: String): Flow<DownloadStatus> = flow {
        // Resolving is disk work, so it happens inside the flow (on [ioDispatcher] below) rather than in the
        // caller's thread at subscribe time.
        val resolved = sources.resolve(AssetHandle(kind, id))
        if (resolved == null) {
            // No source claims this id, so there is no on-disk state to consult — but there may still be a
            // recorded one: `enqueue` marks an unresolvable id Failed, and hard-coding Idle here would make
            // that unobservable, leaving a row that was told the download failed showing nothing at all.
            emitAll(stateFor(kind, id))
            return@flow
        }
        val (source, asset) = resolved
        emitAll(
            combine(
                stateFor(kind, id),
                source.changes.onStart { emit(Unit) },
            ) { status, _ ->
                if (source.isInstalled(asset)) DownloadStatus.Completed(id) else status
            },
        )
    }.flowOn(ioDispatcher)

    override fun pause(kind: String, id: String) {
        val k = key(kind, id)
        synchronized(lock) {
            intents[k] = Intent.PAUSE
            jobs[k]
        }?.cancel()
    }

    override fun cancel(kind: String, id: String) {
        val k = key(kind, id)
        val job = synchronized(lock) {
            intents[k] = Intent.CANCEL
            jobs[k]
        }
        if (job != null) job.cancel() else stateFor(kind, id).value = DownloadStatus.Idle(id)
        // Wiping the partial file is disk work; do it on the scheduler's scope, not the caller's thread.
        scope.launch {
            sources.resolve(AssetHandle(kind, id))?.let { (source, asset) -> source.delete(asset) }
        }
    }

    private companion object {
        const val TAG = "Downloads"
    }
}
