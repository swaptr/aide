package com.sabreware.aide.data.speech.cloud

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 32 ms frames at 16 kHz, as both capturers deliver them. */
private const val FRAME = 512

class EndpointerTest {

    private fun silence(): FloatArray = FloatArray(FRAME) { 0.0005f * if (it % 2 == 0) 1 else -1 }

    private fun speech(): FloatArray = FloatArray(FRAME) { (0.4 * sin(2 * PI * it / 40)).toFloat() }

    private fun Endpointer.feed(frame: () -> FloatArray, count: Int): List<Endpointer.Verdict> =
        List(count) { accept(frame()) }

    @Test
    fun `silence alone never ends an utterance`() {
        val endpointer = Endpointer()

        val verdicts = endpointer.feed(::silence, 200)

        assertTrue(verdicts.all { it == Endpointer.Verdict.Silence })
        assertEquals(false, endpointer.speaking)
    }

    @Test
    fun `a burst of speech followed by the hangover of silence ends the utterance once`() {
        val endpointer = Endpointer(hangoverMs = 800)
        endpointer.feed(::silence, 30) // learn the floor

        val speaking = endpointer.feed(::speech, 20)
        assertEquals(1, speaking.count { it == Endpointer.Verdict.SpeechStart })
        assertTrue(speaking.none { it == Endpointer.Verdict.Utterance })

        val trailing = endpointer.feed(::silence, 60)
        val end = trailing.indexOf(Endpointer.Verdict.Utterance)
        assertTrue(end in 20..40, "800 ms of silence is 25 frames; ended after $end")
    }

    @Test
    fun `speech that never pauses ends at the cap`() {
        val endpointer = Endpointer(maxSpeechMs = 1_000)
        endpointer.feed(::silence, 30)

        val verdicts = endpointer.feed(::speech, 60)

        val end = verdicts.indexOf(Endpointer.Verdict.Utterance)
        assertTrue(end in 28..36, "1 s of speech is ~31 frames; ended after $end")
    }

    @Test
    fun `reset forgets that speech was seen`() {
        val endpointer = Endpointer()
        endpointer.feed(::silence, 30)
        endpointer.feed(::speech, 10)
        assertTrue(endpointer.speaking)

        endpointer.reset()

        assertEquals(false, endpointer.speaking)
        assertTrue(endpointer.feed(::silence, 5).all { it == Endpointer.Verdict.Silence })
    }
}
