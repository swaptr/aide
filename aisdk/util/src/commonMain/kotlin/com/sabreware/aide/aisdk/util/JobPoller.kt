package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.AiSdkError
import kotlinx.coroutines.delay

/** Where a submitted job has got to. */
public sealed interface JobStatus<out T> {

    /** Still running. [retryAfterMillis] honours a server's own pacing hint when it sends one. */
    public data class InProgress(val retryAfterMillis: Long? = null) : JobStatus<Nothing>

    /** Finished; [value] is what the job produced. */
    public data class Succeeded<T>(val value: T) : JobStatus<T>

    /** The job ran and failed. Distinct from a transport error, which throws. */
    public data class Failed(val message: String) : JobStatus<Nothing>
}

/**
 * How hard to poll.
 *
 * Backoff is capped rather than unbounded: an image job that takes three minutes should not end up
 * checked once every two minutes near the end, which is how a naive doubling schedule behaves.
 */
public data class PollPolicy(
    /** The wait before the second check; the first happens immediately. */
    val initialDelayMillis: Long = 1_000,
    /** The ceiling the growing wait never exceeds. */
    val maxDelayMillis: Long = 10_000,
    /** How much each wait grows over the last. */
    val backoffFactor: Double = 1.5,
    /** Total budget. A job that never finishes must fail rather than hang a caller forever. */
    val timeoutMillis: Long = 5 * 60 * 1_000,
) {

    public companion object {
        /** For fast jobs — short audio, small images — where a ten-second gap is most of the latency. */
        public val Fast: PollPolicy = PollPolicy(
            initialDelayMillis = 500,
            maxDelayMillis = 3_000,
            timeoutMillis = 60_000,
        )
    }
}

/**
 * Submit a job, then poll until it finishes.
 *
 * Several vendors — fal, Replicate, AssemblyAI, Kling, Luma, Prodia — are asynchronous in the same way:
 * a POST returns a handle, and the result arrives at a status URL some seconds later. That shape is the
 * real work; the per-vendor code around it is a URL and a JSON path. Written once here rather than six
 * times, because six copies means six different backoff schedules and six different ways of failing to
 * time out.
 *
 * Cancellation works by construction: the wait is [delay], so a cancelled caller stops waiting
 * immediately rather than after the current sleep.
 *
 * A server's own `retry-after` hint wins over the computed backoff when it sends one — it knows how long
 * the job actually needs, and ignoring it means either hammering the endpoint or waiting too long.
 *
 * @param policy how hard to poll and for how long.
 * @param elapsedMillis the budget's clock; monotonic in production, pinned in tests.
 * @param check one status request; its answer decides whether to return, throw, or wait again.
 */
public suspend fun <T> pollUntilDone(
    policy: PollPolicy = PollPolicy(),
    elapsedMillis: () -> Long,
    check: suspend () -> JobStatus<T>,
): T {
    val started = elapsedMillis()
    var wait = policy.initialDelayMillis

    while (true) {
        when (val status = check()) {
            is JobStatus.Succeeded -> return status.value
            is JobStatus.Failed -> throw JobFailedError(status.message)
            is JobStatus.InProgress -> {
                val spent = elapsedMillis() - started
                if (spent >= policy.timeoutMillis) {
                    throw JobTimeoutError(policy.timeoutMillis)
                }
                // The server's hint wins: it knows how long the job actually needs.
                val next = status.retryAfterMillis ?: wait
                // Never sleep past the budget — a poll after the deadline is a wasted request.
                delay(next.coerceAtMost(policy.timeoutMillis - spent))
                wait = (wait * policy.backoffFactor).toLong().coerceAtMost(policy.maxDelayMillis)
            }
        }
    }
}

/**
 * The job ran and the vendor reported it failed.
 *
 * Distinct from a transport error on purpose: the request succeeded, so retrying it verbatim will fail
 * the same way, and a retry policy that cannot tell the two apart burns a budget for nothing.
 */
public class JobFailedError(message: String) : AiSdkError("AI_JobFailedError", message)

/**
 * The job did not finish inside the budget.
 *
 * Also distinct: the work may simply be slow, so a caller with a longer budget can succeed where this
 * one gave up.
 */
public class JobTimeoutError(public val timeoutMillis: Long) :
    AiSdkError("AI_JobTimeoutError", "The job did not complete within ${timeoutMillis}ms")
