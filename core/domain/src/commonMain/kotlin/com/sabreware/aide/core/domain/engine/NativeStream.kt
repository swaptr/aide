package com.sabreware.aide.core.domain.engine

import com.sabreware.aide.core.domain.util.AideLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a native engine reports one run: every output, then exactly one terminal call. Called from the
 * engine's own threads, never from ours, so nothing here may block.
 */
interface NativeStreamSink<in T> {
    fun emit(value: T)

    /** The run finished normally. */
    fun done()

    /**
     * The run failed. A [CancellationException] here means the engine acknowledged a cancel, which ends the
     * stream normally: whoever asked for the cancel already knows why it stopped.
     */
    fun fail(error: Throwable)
}

/**
 * A native run as a [Flow] whose cancellation actually reaches the engine, and whose collection does not
 * return until the engine has stopped.
 *
 * Native inference runs on the engine's threads, not in the collecting coroutine, so cancelling the
 * collector stops nothing by itself. The flow LiteRT-LM ships is the proof: its `sendMessageAsync(): Flow`
 * (checked in 0.11.0) closes with an empty `awaitClose {}`, so a cancelled collector returned at once while
 * the GPU kept decoding to `maxNumTokens`, and every lock "held for the turn" was released on top of a run
 * that was still going.
 *
 * Contract, for any engine (chat, speech, image, audio):
 *  - Cancelling the collector calls [cancel] once, then waits (non-cancellably, up to [settleTimeoutMs])
 *    for the engine's terminal [NativeStreamSink.done] / [NativeStreamSink.fail].
 *  - Collection completes only after that terminal call, so a turn gate held around `collect` is held for
 *    the native run's real lifetime and a free queued behind it never lands mid-decode.
 *  - Outputs are buffered without bound so an engine callback never blocks on a slow collector.
 *
 * The timeout is a safety valve, not a normal path: an engine that ignores its cancel must not wedge the
 * caller forever. It is logged, because it means the engine is still running.
 */
fun <T> nativeStream(
    cancel: () -> Unit,
    settleTimeoutMs: Long = NATIVE_SETTLE_TIMEOUT_MS,
    start: (NativeStreamSink<T>) -> Unit,
): Flow<T> = callbackFlow {
    val settled = CompletableDeferred<Unit>()
    val sink = object : NativeStreamSink<T> {
        override fun emit(value: T) {
            trySend(value)
        }

        override fun done() {
            settled.complete(Unit)
            channel.close()
        }

        override fun fail(error: Throwable) {
            settled.complete(Unit)
            channel.close(error.takeUnless { it is CancellationException })
        }
    }
    start(sink)
    try {
        awaitClose()
    } finally {
        if (!settled.isCompleted) {
            withContext(NonCancellable) {
                runCatching(cancel).onFailure { AideLog.w(TAG, "native cancel failed", it) }
                if (withTimeoutOrNull(settleTimeoutMs) { settled.await() } == null) {
                    AideLog.w(TAG, "native run did not stop within $settleTimeoutMs ms of its cancel")
                }
            }
        }
    }
}.buffer(Channel.UNLIMITED)

/** Long enough for an engine to finish the step it is on (one token, one diffusion step, one audio chunk). */
const val NATIVE_SETTLE_TIMEOUT_MS: Long = 3_000L

private const val TAG = "NativeStream"
