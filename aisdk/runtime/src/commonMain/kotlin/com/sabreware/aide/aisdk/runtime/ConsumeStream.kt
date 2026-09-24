package com.sabreware.aide.aisdk.runtime

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Drains this flow to the end without looking at what it carries.
 *
 * For the caller that wants a run's side effects — tools executed, [RunCallbacks] fired, a
 * [com.sabreware.aide.aisdk.runtime.telemetry.RunMetricsRecorder] filled — and none of its events. The
 * reference exposes the same thing on its result object; here it is an operator on the flow, because
 * that is the shape everything else in this package takes.
 *
 * A failure is handed to [onError] and does NOT propagate, matching the reference: this is a
 * fire-and-forget drain, and a drain that throws is spelled `collect {}`. Cancellation is the one thing
 * never absorbed — a cancelled coroutine has to actually stop.
 */
public suspend fun Flow<*>.consumeStream(onError: ((Throwable) -> Unit)? = null) {
    try {
        collect {}
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
        onError?.invoke(e)
    }
}
