package com.sabreware.aide.core.domain.cache

import com.sabreware.aide.core.common.startup.DeferredBootstrap
import com.sabreware.aide.core.domain.util.AideLog
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * THE data-layer cache: an app-wide snapshot of an expensive flow (disk stats, a decrypt, an N-flow fold),
 * one upstream however many surfaces watch.
 *
 * - `null` = not resolved this session; a surface seeds from [StateFlow.value] (`stateInUiCached`) and shows
 *   its skeleton only while it is null. An upstream that emits null itself (an aggregate still settling)
 *   passes that through as the same "not yet".
 * - `WhileSubscribed(5 s)` releases the upstream when nothing watches; the RETAINED value re-seeds the next
 *   open instantly while the restarted upstream refreshes behind it (stale-while-revalidate).
 * - A throw is logged and completes the upstream instead of killing the shared coroutine (which would hang
 *   every future subscriber on its skeleton); the next 0→1 subscription retries.
 * - Each (re)start logs its time to first value, so a cold cost is visible in the log, never a guess.
 *
 * Put `flowOn(...)` BEFORE this when an emission does blocking work; this does not pick a dispatcher.
 * Never read [StateFlow.value] as the read half of a read-modify-write — read the store.
 */
fun <T> Flow<T?>.snapshotCache(scope: CoroutineScope, name: String): StateFlow<T?> =
    logTimeToFirstValue(TAG, name)
        .catch { AideLog.e(TAG, "$name snapshot failed", it) }
        .stateIn(scope, SharingStarted.WhileSubscribed(RETAIN_MS), null)

/**
 * Logs, per collection, how long this flow took to produce its first non-null value — for a cache, the time
 * a cold surface spends on its skeleton. [TimeSource.Monotonic] keeps it portable to every target.
 */
private fun <T> Flow<T>.logTimeToFirstValue(tag: String, name: String): Flow<T> = flow {
    val mark = TimeSource.Monotonic.markNow()
    var logged = false
    collect { value ->
        if (!logged && value != null) {
            logged = true
            AideLog.d(tag, "$name: first snapshot in ${mark.elapsedNow().inWholeMilliseconds} ms")
        }
        emit(value)
    }
}

/**
 * Resolves [caches] once, AFTER the first frame, so the first open of a surface a tap away from the start
 * screen (the model picker is the case that earned this) seeds straight into Ready instead of computing
 * while its sheet slides in. Subscribing until each cache holds a value leaves that value retained;
 * `WhileSubscribed` then releases the upstream.
 *
 * Launched, not awaited — the shell runs bootstraps in sequence and one slow source must not hold up the
 * others — and bounded, so a source that never settles cannot pin its upstream open for the process.
 * Warm only what a first open would otherwise wait on; a cache already warmed by another bootstrap (the MCP
 * server list, via the reconnect) does not belong here.
 */
class CacheWarmup(
    private val scope: CoroutineScope,
    private val caches: Map<String, StateFlow<*>>,
) : DeferredBootstrap {

    private var started = false

    override suspend fun start() {
        if (started) return
        started = true
        caches.forEach { (name, cache) ->
            if (cache.value != null) return@forEach
            scope.launch {
                val mark = TimeSource.Monotonic.markNow()
                val settled = withTimeoutOrNull(WARM_TIMEOUT) { cache.first { it != null } }
                val ms = mark.elapsedNow().inWholeMilliseconds
                if (settled != null) AideLog.d(TAG, "$name warm in $ms ms")
                else AideLog.w(TAG, "$name still unresolved after $ms ms; its first open will load it")
            }
        }
    }

    private companion object {
        val WARM_TIMEOUT = 30.seconds
    }
}

private const val TAG = "Cache"
private const val RETAIN_MS = 5_000L
