package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.RetryError
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay

/**
 * How many attempts, and how long to wait between them.
 *
 * [maxRetries] counts RETRIES, not attempts: 2 means up to three calls. Naming it the other way is a
 * classic off-by-one in retry code, so it is stated here rather than left to the reader.
 */
public data class RetryPolicy(
    /** Retries after the first attempt — see the class doc for the off-by-one this naming avoids. */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    /** The wait before the first retry, absent a server-named `retry-after`. */
    val initialDelayMillis: Long = DEFAULT_INITIAL_DELAY_MS,
    /** How much each wait grows over the last. */
    val backoffFactor: Double = DEFAULT_BACKOFF_FACTOR,
    /** The ceiling the growing wait never exceeds. */
    val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MS,
) {

    public companion object {
        public const val DEFAULT_MAX_RETRIES: Int = 2
        public const val DEFAULT_INITIAL_DELAY_MS: Long = 2_000
        public const val DEFAULT_BACKOFF_FACTOR: Double = 2.0
        public const val DEFAULT_MAX_DELAY_MS: Long = 60_000

        /** Never retry. For a caller that owns its own policy, or a non-idempotent call. */
        public val None: RetryPolicy = RetryPolicy(maxRetries = 0)
    }
}

/**
 * Runs [block], retrying on failures that declare themselves retryable.
 *
 * Only [APICallError] with `isRetryable` is retried, and only while [canRetry] holds. Everything else
 * propagates — retrying a 401 three times does nothing but delay the error the user needs to see, and
 * retrying a non-idempotent POST that actually succeeded is worse than failing.
 *
 * **What comes out on failure is as much a contract as whether it retried.** The ladder mirrors the
 * reference exactly:
 *
 * - Retries disabled ([RetryPolicy.None]) — the original error, untouched. A caller that opted out owns
 *   the policy, and a [RetryError] wrapper there is a `catch (e: APICallError)` that stops matching.
 *   The provider test harness runs under `None`, so this is the shape every provider test asserts.
 * - Failed on the first attempt with nothing retryable — the original error, untouched, for the same
 *   reason: nothing was retried, so there is nothing to describe.
 * - Ran out of attempts — [RetryError.Reason.MaxRetriesExceeded], carrying every failure. The last one
 *   alone loses the case where the first attempt named the real problem and the rest timed out.
 * - Hit a non-retryable failure *after* retrying — [RetryError.Reason.ErrorNotRetryable].
 *
 * [CancellationException] is rethrown untouched: a cancelled request must not be retried, and swallowing
 * it would break structured concurrency.
 *
 * @param policy how many retries, and the waits between them.
 * @param canRetry an external gate checked before each retry — a closing scope, a budget shared wider
 *   than this call.
 * @param block the attempt; `attempt` counts from zero.
 */
public suspend fun <T> withRetry(
    policy: RetryPolicy = RetryPolicy(),
    canRetry: () -> Boolean = { true },
    block: suspend (attempt: Int) -> T,
): T {
    var delayMillis = policy.initialDelayMillis
    val failures = mutableListOf<Throwable>()
    while (true) {
        try {
            return block(failures.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: APICallError) {
            failures += e
            if (policy.maxRetries == 0) throw e
            if (failures.size > policy.maxRetries) {
                throw RetryError(RetryError.Reason.MaxRetriesExceeded, failures.toList())
            }
            if (!e.isRetryable || !canRetry()) {
                // The first failure is rethrown as itself: wrapping a single 401 in a RetryError buries
                // the only useful error under a policy the caller never invoked.
                throw if (failures.size == 1) e else {
                    RetryError(RetryError.Reason.ErrorNotRetryable, failures.toList())
                }
            }
            // A server that names a wait knows better than our curve; a server that names an absurd one
            // is telling us to stop, which is why the value is clamped rather than obeyed literally.
            delay(e.responseHeaders?.retryAfterMillis() ?: delayMillis)
            delayMillis = (delayMillis * policy.backoffFactor).toLong().coerceAtMost(policy.maxDelayMillis)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // Only an APICallError carries a judgement about whether repeating the call could succeed.
            // Anything else reached us without one, and retrying on a guess is how a non-idempotent call
            // that already took effect gets made twice.
            if (failures.isEmpty()) throw e
            failures += e
            throw RetryError(RetryError.Reason.ErrorNotRetryable, failures.toList())
        }
    }
}
