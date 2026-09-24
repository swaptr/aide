package com.sabreware.aide.aisdk.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * The polling machine every async vendor shares.
 *
 * Written once because six copies would mean six different backoff schedules and six different ways of
 * failing to time out — and the ways of failing to time out are the ones that hang an app.
 *
 * Uses the test scheduler's virtual clock, so the schedule is asserted exactly without any real waiting.
 */
class JobPollerTest {

    @Test
    fun `a job that succeeds immediately returns without waiting`() = runTest {
        val result = pollUntilDone(elapsedMillis = { currentTime }) { JobStatus.Succeeded("done") }

        assertEquals("done", result)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `polling continues until the job finishes`() = runTest {
        var checks = 0

        val result = pollUntilDone(elapsedMillis = { currentTime }) {
            checks++
            if (checks < 4) JobStatus.InProgress() else JobStatus.Succeeded("done")
        }

        assertEquals("done", result)
        assertEquals(4, checks)
    }

    @Test
    fun `the wait backs off but stops growing at the cap`() = runTest {
        val waits = mutableListOf<Long>()
        var last = 0L
        var checks = 0

        pollUntilDone(
            policy = PollPolicy(initialDelayMillis = 1_000, backoffFactor = 2.0, maxDelayMillis = 4_000),
            elapsedMillis = { currentTime },
        ) {
            waits += currentTime - last
            last = currentTime
            checks++
            if (checks < 6) JobStatus.InProgress() else JobStatus.Succeeded(Unit)
        }

        // 0 before the first check, then 1s, 2s, 4s, and capped at 4s thereafter. An uncapped schedule
        // would be checking every 16 seconds by now, which is most of a short job's latency.
        assertEquals(listOf(0L, 1_000L, 2_000L, 4_000L, 4_000L, 4_000L), waits)
    }

    @Test
    fun `a server's retry-after hint wins over the computed backoff`() = runTest {
        var checks = 0
        val start = currentTime

        pollUntilDone(
            policy = PollPolicy(initialDelayMillis = 1_000),
            elapsedMillis = { currentTime },
        ) {
            checks++
            // The server knows how long the job actually needs; ignoring it means hammering or waiting
            // too long.
            if (checks < 2) JobStatus.InProgress(retryAfterMillis = 7_000) else JobStatus.Succeeded(Unit)
        }

        assertEquals(7_000L, currentTime - start)
    }

    @Test
    fun `a job that never finishes fails rather than hanging the caller`() = runTest {
        val error = assertFailsWith<JobTimeoutError> {
            pollUntilDone(
                policy = PollPolicy(initialDelayMillis = 1_000, timeoutMillis = 5_000),
                elapsedMillis = { currentTime },
            ) { JobStatus.InProgress() }
        }

        assertEquals(5_000L, error.timeoutMillis)
        // And it gives up AT the deadline rather than sleeping past it, which would waste a request.
        assertEquals(5_000L, currentTime)
    }

    @Test
    fun `a reported failure is not retried`() = runTest {
        var checks = 0

        val error = assertFailsWith<JobFailedError> {
            pollUntilDone(elapsedMillis = { currentTime }) {
                checks++
                JobStatus.Failed("NSFW content detected")
            }
        }

        // The request succeeded and the WORK failed; retrying it verbatim fails the same way.
        assertEquals(1, checks)
        assertTrue(error.message!!.contains("NSFW"))
    }

    @Test
    fun `the two failures are distinguishable so a retry policy can tell them apart`() = runTest {
        val timeout: Throwable = assertFailsWith<JobTimeoutError> {
            pollUntilDone(
                policy = PollPolicy(initialDelayMillis = 10, timeoutMillis = 20),
                elapsedMillis = { currentTime },
            ) { JobStatus.InProgress() }
        }
        val failed: Throwable = assertFailsWith<JobFailedError> {
            pollUntilDone(elapsedMillis = { currentTime }) { JobStatus.Failed("bad prompt") }
        }

        // A policy that cannot tell "too slow" from "refused" burns its budget on the refusal.
        assertTrue(timeout is JobTimeoutError)
        assertTrue(failed is JobFailedError)
    }

    @Test
    fun `the fast policy is tuned for jobs where a long gap dominates the latency`() = runTest {
        var checks = 0
        val start = currentTime

        pollUntilDone(policy = PollPolicy.Fast, elapsedMillis = { currentTime }) {
            checks++
            if (checks < 2) JobStatus.InProgress() else JobStatus.Succeeded(Unit)
        }

        // Half a second, not a full one: for short audio the poll gap IS most of the wall clock.
        assertEquals(500L, currentTime - start)
    }
}

/** The virtual clock, so the schedule is asserted exactly without any real waiting. */
@OptIn(ExperimentalCoroutinesApi::class)
private val TestScope.currentTime: Long get() = testScheduler.currentTime
