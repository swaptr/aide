package com.sabreware.aide.core.domain.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The contract every native engine's stream keeps: cancelling the collector reaches the engine, and the
 * collector does not return until the engine has stopped.
 */
class NativeStreamTest {

    /** A native run the test drives by hand, standing in for the engine's own threads. */
    private class FakeRun {
        var sink: NativeStreamSink<String>? = null
        var cancels = 0

        /** When true, the engine acknowledges a cancel the moment it is asked, as LiteRT does between tokens. */
        var acknowledgeCancel = true

        val stream = nativeStream<String>(
            cancel = {
                cancels++
                if (acknowledgeCancel) sink?.fail(CancellationException("cancelled by engine"))
            },
            settleTimeoutMs = 1_000L,
        ) { sink = it }
    }

    @Test
    fun `outputs arrive in order and done ends the stream without a cancel`() = runTest {
        val run = FakeRun()
        val collected = mutableListOf<String>()
        val job = launch { run.stream.collect { collected += it } }
        runCurrent()

        run.sink!!.emit("a")
        run.sink!!.emit("b")
        run.sink!!.done()
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), collected)
        assertTrue(job.isCompleted)
        assertEquals(0, run.cancels, "a run that finished on its own is never cancelled")
    }

    @Test
    fun `cancelling the collector cancels the native run exactly once`() = runTest {
        val run = FakeRun()
        val job = launch { run.stream.collect { } }
        runCurrent()
        run.sink!!.emit("partial")

        job.cancel()
        advanceUntilIdle()

        assertEquals(1, run.cancels, "the one thing LiteRT's own flow never did")
        assertTrue(job.isCompleted)
    }

    @Test
    fun `the collector does not return until the engine acknowledges the cancel`() = runTest {
        val run = FakeRun().apply { acknowledgeCancel = false }
        val job = launch { run.stream.collect { } }
        runCurrent()

        job.cancel()
        runCurrent()
        assertFalse(job.isCompleted, "still waiting: the engine has not stopped, so nothing may free it yet")

        run.sink!!.fail(CancellationException("stopped"))
        runCurrent()
        assertTrue(job.isCompleted, "released the moment the engine reports it stopped")
    }

    @Test
    fun `an engine that ignores its cancel releases the collector after the timeout`() = runTest {
        val run = FakeRun().apply { acknowledgeCancel = false }
        val job = launch { run.stream.collect { } }
        runCurrent()

        job.cancel()
        advanceTimeBy(999L)
        runCurrent()
        assertFalse(job.isCompleted)
        advanceTimeBy(2L)
        runCurrent()
        assertTrue(job.isCompleted, "a wedged engine must not wedge its caller forever")
    }

    @Test
    fun `an engine error fails the stream`() = runTest {
        val run = FakeRun()
        val result = launch {
            assertFailsWith<IllegalStateException> { run.stream.toList() }
        }
        runCurrent()
        run.sink!!.fail(IllegalStateException("decode failed"))
        advanceUntilIdle()
        assertTrue(result.isCompleted)
        assertEquals(0, run.cancels)
    }

    @Test
    fun `an engine-side cancel ends the stream normally`() = runTest {
        val run = FakeRun()
        val collected = mutableListOf<String>()
        val job = launch { run.stream.collect { collected += it } }
        runCurrent()
        run.sink!!.emit("half")
        run.sink!!.fail(CancellationException("stop requested elsewhere"))
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled, "a Stop acknowledged by the engine is an end, not a failure of the collector")
        assertEquals(listOf("half"), collected)
    }
}
