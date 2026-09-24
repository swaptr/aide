package com.sabreware.aide.core.common.coroutines

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` for code that suspends. The stdlib one catches `CancellationException` too, which turns a
 * cancelled coroutine into an ordinary failure: a Stop became a tool error, a cancelled GPU load retried on
 * CPU. This rethrows cancellation and captures everything else.
 *
 * Inline, so the block may call suspend functions from the caller's coroutine.
 */
inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    Result.failure(t)
}
