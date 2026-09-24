package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.RealtimeServerEvent
import com.sabreware.aide.aisdk.providers.google.GoogleRealtimeFixtures.audioTurn
import com.sabreware.aide.aisdk.providers.google.GoogleRealtimeFixtures.textTurn
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The server half of `realtime/google-realtime-event-mapper.test.ts`: every frame the reference maps.
 *
 * The ids are the thing under test. Gemini sends none, so `google-resp-N` / `google-item-N` are minted
 * from a turn counter that rolls over LAZILY — closed by `turnComplete`, advanced only by the next model
 * content — which is what keeps a transcript arriving just after the boundary on the turn it belongs to.
 */
class GoogleRealtimeEventMapperTest {

    private fun frame(json: String) = parseJsonObject(json)

    private fun mapper() = GoogleRealtimeEventMapper()

    @Test
    fun `setupComplete is the session opening`() {
        val raw = frame(GoogleRealtimeFixtures.SETUP_COMPLETE)

        assertEquals(listOf(RealtimeServerEvent.SessionCreated(raw)), mapper().parseServerEvent(raw))
    }

    @Test
    fun `audio in a model turn is an audio delta on turn zero`() {
        val raw = frame(GoogleRealtimeFixtures.AUDIO_PART)

        assertEquals(
            listOf(RealtimeServerEvent.AudioDelta(raw, "google-resp-0", "google-item-0", "base64audio")),
            mapper().parseServerEvent(raw),
        )
    }

    @Test
    fun `text in a model turn is a text delta`() {
        val raw = frame(GoogleRealtimeFixtures.TEXT_PART)

        assertEquals(
            listOf(RealtimeServerEvent.TextDelta(raw, "google-resp-0", "google-item-0", "hello world")),
            mapper().parseServerEvent(raw),
        )
    }

    @Test
    fun `an output transcription is an audio transcript delta`() {
        val raw = frame(GoogleRealtimeFixtures.OUTPUT_TRANSCRIPTION)

        assertEquals(
            listOf(RealtimeServerEvent.AudioTranscriptDelta(raw, "google-resp-0", "google-item-0", "transcribed text")),
            mapper().parseServerEvent(raw),
        )
    }

    @Test
    fun `an input transcription inside serverContent is the user's transcript`() {
        val raw = frame(GoogleRealtimeFixtures.NESTED_INPUT_TRANSCRIPTION)

        assertEquals(
            listOf(RealtimeServerEvent.InputTranscriptionCompleted(raw, "google-input-0", "Can you hear me?")),
            mapper().parseServerEvent(raw),
        )
    }

    @Test
    fun `a top-level input transcription is read too`() {
        // Early sessions put it at the top level; both spellings are live.
        val raw = frame(GoogleRealtimeFixtures.TOP_LEVEL_INPUT_TRANSCRIPTION)

        assertEquals(
            listOf(RealtimeServerEvent.InputTranscriptionCompleted(raw, "google-input-0", "Can you hear me?")),
            mapper().parseServerEvent(raw),
        )
    }

    @Test
    fun `interrupted is the user starting to speak`() {
        val raw = frame(GoogleRealtimeFixtures.INTERRUPTED)

        assertEquals(listOf(RealtimeServerEvent.SpeechStarted(raw)), mapper().parseServerEvent(raw))
    }

    @Test
    fun `turnComplete after audio closes the audio and the response`() {
        val subject = mapper()
        subject.parseServerEvent(frame(audioTurn("audio")))
        val raw = frame(GoogleRealtimeFixtures.TURN_COMPLETE)

        assertEquals(
            listOf(
                RealtimeServerEvent.AudioDone(raw, "google-resp-0", "google-item-0"),
                RealtimeServerEvent.ResponseDone(raw, "google-resp-0", "completed"),
            ),
            subject.parseServerEvent(raw),
        )
    }

    @Test
    fun `turnComplete after text closes the text and the response`() {
        val subject = mapper()
        subject.parseServerEvent(frame(textTurn("hello")))
        val raw = frame(GoogleRealtimeFixtures.TURN_COMPLETE)

        assertEquals(
            listOf(
                RealtimeServerEvent.TextDone(raw, "google-resp-0", "google-item-0"),
                RealtimeServerEvent.ResponseDone(raw, "google-resp-0", "completed"),
            ),
            subject.parseServerEvent(raw),
        )
    }

    @Test
    fun `interactionStatus is forwarded as a custom event`() {
        // The definitive activity signal on a background-reasoning model: `turnComplete` no longer means
        // idle, since asynchronous tool calls and audio may still follow.
        val raw = frame(GoogleRealtimeFixtures.INTERACTION_STATUS)

        assertEquals(listOf(RealtimeServerEvent.Custom(raw, rawType = "interactionStatus")), mapper().parseServerEvent(raw))
    }

    @Test
    fun `interactionStatus rides beside the turnComplete events`() {
        val subject = mapper()
        subject.parseServerEvent(frame(audioTurn("AAAA")))
        val raw = frame(GoogleRealtimeFixtures.TURN_COMPLETE_IDLE)

        val events = subject.parseServerEvent(raw)

        assertTrue(RealtimeServerEvent.Custom(raw, rawType = "interactionStatus") in events, "$events")
        assertTrue(RealtimeServerEvent.ResponseDone(raw, "google-resp-0", "completed") in events, "$events")
    }

    @Test
    fun `waitingForInput is forwarded as a custom event`() {
        // Proactive Audio's turn-taking signal: the model has yielded the floor and expects the user.
        val raw = frame(GoogleRealtimeFixtures.WAITING_FOR_INPUT)

        assertEquals(listOf(RealtimeServerEvent.Custom(raw, rawType = "waitingForInput")), mapper().parseServerEvent(raw))
    }

    @Test
    fun `the ids advance once new content follows a completed turn`() {
        val subject = mapper()
        subject.parseServerEvent(frame(audioTurn("audio1")))
        subject.parseServerEvent(frame(GoogleRealtimeFixtures.TURN_COMPLETE))
        val raw = frame(audioTurn("audio2"))

        assertEquals(
            listOf(RealtimeServerEvent.AudioDelta(raw, "google-resp-1", "google-item-1", "audio2")),
            subject.parseServerEvent(raw),
        )
    }

    @Test
    fun `a transcript arriving after turnComplete stays on the turn it belongs to`() {
        val subject = mapper()
        subject.parseServerEvent(frame(audioTurn("audio1")))
        subject.parseServerEvent(frame(GoogleRealtimeFixtures.TURN_COMPLETE))

        // Google delivers transcription independently, with no ordering guarantee against turnComplete.
        val raw = frame(GoogleRealtimeFixtures.LATE_TRANSCRIPT)
        assertEquals(
            listOf(RealtimeServerEvent.AudioTranscriptDelta(raw, "google-resp-0", "google-item-0", "late transcript")),
            subject.parseServerEvent(raw),
        )

        // The next model response is what opens turn 1.
        val next = subject.parseServerEvent(frame(audioTurn("audio2"))).single()
        assertEquals("google-resp-1", assertIs<RealtimeServerEvent.AudioDelta>(next).responseId)
    }

    @Test
    fun `a multi-part turn is several events from one frame`() {
        val raw = frame(GoogleRealtimeFixtures.MULTI_PART)

        assertEquals(
            listOf(
                RealtimeServerEvent.AudioDelta(raw, "google-resp-0", "google-item-0", "audio"),
                RealtimeServerEvent.TextDelta(raw, "google-resp-0", "google-item-0", "text"),
            ),
            mapper().parseServerEvent(raw),
        )
    }

    @Test
    fun `a tool call is a delta-then-done pair per function`() {
        val raw = frame(GoogleRealtimeFixtures.TOOL_CALL)

        assertEquals(
            listOf(
                RealtimeServerEvent.FunctionCallArgumentsDelta(raw, "google-resp-0", "google-item-0", "call_1", """{"city":"NYC"}"""),
                RealtimeServerEvent.FunctionCallArgumentsDone(
                    raw, "google-resp-0", "google-item-0", "call_1", "getWeather", """{"city":"NYC"}""",
                ),
                RealtimeServerEvent.FunctionCallArgumentsDelta(raw, "google-resp-0", "google-item-0", "call_2", "{}"),
                RealtimeServerEvent.FunctionCallArgumentsDone(raw, "google-resp-0", "google-item-0", "call_2", "rollDice", "{}"),
            ),
            mapper().parseServerEvent(raw),
        )
    }

    @Test
    fun `a tool call cancellation is forwarded whole`() {
        val raw = frame(GoogleRealtimeFixtures.TOOL_CALL_CANCELLATION)

        assertEquals(listOf(RealtimeServerEvent.Custom(raw, "toolCallCancellation")), mapper().parseServerEvent(raw))
    }

    @Test
    fun `goAway is a stable custom lifecycle event`() {
        val raw = frame(GoogleRealtimeFixtures.GO_AWAY)

        assertEquals(listOf(RealtimeServerEvent.Custom(raw, "goAway")), mapper().parseServerEvent(raw))
    }

    @Test
    fun `a session resumption update is a stable custom lifecycle event`() {
        val raw = frame(GoogleRealtimeFixtures.SESSION_RESUMPTION_UPDATE)

        assertEquals(listOf(RealtimeServerEvent.Custom(raw, "sessionResumptionUpdate")), mapper().parseServerEvent(raw))
    }

    @Test
    fun `generationComplete is not turnComplete`() {
        val subject = mapper()
        val raw = frame(GoogleRealtimeFixtures.GENERATION_COMPLETE)

        assertEquals(listOf(RealtimeServerEvent.Custom(raw, "generationComplete")), subject.parseServerEvent(raw))

        // Generation stopping leaves the turn open: the next content is still turn zero.
        val next = subject.parseServerEvent(frame(textTurn("still turn zero"))).single()
        assertEquals("google-resp-0", assertIs<RealtimeServerEvent.TextDelta>(next).responseId)
    }

    @Test
    fun `an unrecognised frame keeps its payload under its own key`() {
        val raw = frame(GoogleRealtimeFixtures.UNKNOWN_FRAME)

        assertEquals(listOf(RealtimeServerEvent.Custom(raw, "somethingNew")), mapper().parseServerEvent(raw))
    }
}
