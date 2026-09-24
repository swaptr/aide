package com.sabreware.aide.core.domain.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The microphone indicator's arithmetic.
 *
 * It is a shared signal with three kinds of producer — our own capturer, a recogniser that owns the mic, and
 * Silero — and the meter every surface draws reads only the result. Two of the ways it went wrong are the
 * ones a user actually notices, and both are about `begin`/`end` not being balanced:
 *
 *  - the first `end()` blanked a meter while another source was still recording;
 *  - a `begin()` whose `end()` never ran (a capture cancelled before its teardown) left `listening = true`
 *    on for the life of the process, which reads as "this app is recording you".
 *
 * Refcounting is what makes both impossible, so it is what this pins.
 */
class MicActivityMonitorTest {

    @Test
    fun `one source begins and ends`() {
        val monitor = MicActivityMonitor()
        assertFalse(monitor.state.value.listening)

        monitor.begin()
        assertTrue(monitor.state.value.listening)

        monitor.end()
        assertFalse(monitor.state.value.listening)
    }

    @Test
    fun `a second source ending does not blank a meter the first is still driving`() {
        val monitor = MicActivityMonitor()

        monitor.begin()
        monitor.begin()
        monitor.end()

        assertTrue(
            monitor.state.value.listening,
            "one of two sources stopped; the mic is still open and the meter must still say so",
        )

        monitor.end()
        assertFalse(monitor.state.value.listening, "the last source ending is what clears it")
    }

    @Test
    fun `an unbalanced end cannot drive the count negative`() {
        val monitor = MicActivityMonitor()

        // Two ends against one begin — a teardown running twice, which the capturer's completion handler
        // and its finally block could once both do.
        monitor.begin()
        monitor.end()
        monitor.end()

        // If the count had gone to -1, this begin would leave it at 0 and the meter would stay dark while
        // the microphone was open.
        monitor.begin()
        assertTrue(monitor.state.value.listening, "a fresh capture must light the meter regardless of history")
    }

    @Test
    fun `a level reading updates the envelope without touching the listening flag`() {
        val monitor = MicActivityMonitor()
        monitor.begin()

        monitor.onRms(db = -20f, frameMs = 32)

        val state = monitor.state.value
        assertTrue(state.listening)
        assertEquals(-20f, state.rmsDb)
    }

    @Test
    fun `an authoritative voice-activity verdict wins over the energy detector`() {
        val monitor = MicActivityMonitor()
        monitor.begin()

        // Silero says "speech" while the energy detector, given near-silence, would say otherwise.
        monitor.onVoiceActivity(speaking = true)
        monitor.onRms(db = MicActivity.SilentDb, frameMs = 32)

        assertTrue(
            monitor.state.value.speaking,
            "a real VAD's verdict is better than anything inferred from levels, and must not be overwritten",
        )
    }
}
