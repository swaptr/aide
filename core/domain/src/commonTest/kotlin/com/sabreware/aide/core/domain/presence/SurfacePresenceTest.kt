package com.sabreware.aide.core.domain.presence

import com.sabreware.aide.core.domain.llm.Surface
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SurfacePresenceTest {

    @Test
    fun `visible tracks each surface on its own`() {
        val presence = SurfacePresence(HiddenWorkPolicy.StopAndFree)
        presence.shown(Surface.CHAT)
        presence.shown(Surface.IME)
        presence.hidden(Surface.CHAT)
        assertEquals(setOf(Surface.IME), presence.visible.value)
    }

    @Test
    fun `a stop signal fires once per hide of that surface only`() = runTest {
        val presence = SurfacePresence(HiddenWorkPolicy.StopAndFree)
        var stops = 0
        val job = launch { presence.stopSignals(Surface.CHAT).collect { stops++ } }
        runCurrent()

        presence.shown(Surface.CHAT)
        runCurrent()
        presence.shown(Surface.IME)
        presence.hidden(Surface.IME)
        runCurrent()
        assertEquals(0, stops, "another surface hiding is not this one leaving")

        presence.hidden(Surface.CHAT)
        runCurrent()
        presence.hidden(Surface.CHAT)
        runCurrent()
        assertEquals(1, stops, "one hide, one stop; a repeated report changes nothing")
        job.cancel()
    }

    @Test
    fun `a picker launched for a result is not leaving`() = runTest {
        val presence = SurfacePresence(HiddenWorkPolicy.StopAndFree)
        var stops = 0
        presence.shown(Surface.CHAT)
        val job = launch { presence.stopSignals(Surface.CHAT).collect { stops++ } }
        runCurrent()

        presence.awayForResult(Surface.CHAT)
        presence.hidden(Surface.CHAT)
        runCurrent()
        assertEquals(0, stops, "the contact picker covering the chat must not cancel the turn waiting on it")

        presence.shown(Surface.CHAT)
        presence.hidden(Surface.CHAT)
        runCurrent()
        assertEquals(1, stops, "back from the picker, the next hide is a real one")
        job.cancel()
    }

    @Test
    fun `a host that keeps running never signals a stop`() = runTest {
        val presence = SurfacePresence(HiddenWorkPolicy.KeepRunning)
        var stops = 0
        presence.shown(Surface.CHAT)
        val job = launch { presence.stopSignals(Surface.CHAT).collect { stops++ } }
        runCurrent()
        presence.hidden(Surface.CHAT)
        runCurrent()
        assertEquals(0, stops)
        job.cancel()
    }

    @Test
    fun `leftSince sees a hide that happened before anyone watched`() = runTest {
        val presence = SurfacePresence(HiddenWorkPolicy.StopAndFree).apply { shown(Surface.CHAT) }
        val mark = presence.mark(Surface.CHAT)
        presence.hidden(Surface.CHAT)   // the user left during a cold model load

        var left = false
        val job = launch { presence.leftSince(Surface.CHAT, mark).collect { left = true } }
        runCurrent()
        assertEquals(true, left, "no window between reading visibility and starting to watch it")
        job.cancel()
    }

    @Test
    fun `leftSince stays quiet while the surface is only shown`() = runTest {
        val presence = SurfacePresence(HiddenWorkPolicy.StopAndFree)
        val mark = presence.mark(Surface.CHAT)
        presence.shown(Surface.CHAT)
        presence.shown(Surface.IME)
        presence.hidden(Surface.IME)

        var left = false
        val job = launch { presence.leftSince(Surface.CHAT, mark).collect { left = true } }
        runCurrent()
        assertEquals(false, left)
        job.cancel()
    }

    @Test
    fun `endWhen with a signal that never fires passes the whole stream through`() = runTest {
        assertEquals(listOf(1, 2, 3), flowOf(1, 2, 3).endWhen(emptyFlow()).toList())
    }

    @Test
    fun `endWhen delivers every item before an upstream failure`() = runTest {
        val got = mutableListOf<Int>()
        val failing = flow {
            emit(1)
            emit(2)
            throw IllegalStateException("connection reset")
        }
        assertFailsWith<IllegalStateException> { failing.endWhen(emptyFlow()).collect { got += it } }
        assertEquals(listOf(1, 2), got, "the partial reply survives the failure that follows it")
    }

    @Test
    fun `endWhen ends normally and cancels upstream when the signal fires`() = runTest {
        val signal = MutableSharedFlow<Unit>()
        var upstreamCancelled = false
        val got = mutableListOf<Int>()
        val endless = flow {
            try {
                emit(1)
                awaitCancellation()
            } catch (e: CancellationException) {
                upstreamCancelled = true
                throw e
            }
        }
        val job = launch { endless.endWhen(signal).collect { got += it } }
        runCurrent()
        signal.emit(Unit)
        runCurrent()

        assertTrue(job.isCompleted && !job.isCancelled, "a signal ends the stream, it does not fail the collector")
        assertTrue(upstreamCancelled, "the upstream (a native run) is cancelled")
        assertEquals(listOf(1), got)
    }
}
