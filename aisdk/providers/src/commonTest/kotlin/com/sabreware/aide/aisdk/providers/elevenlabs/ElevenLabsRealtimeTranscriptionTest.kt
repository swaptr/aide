package com.sabreware.aide.aisdk.providers.elevenlabs

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.AiSdkError
import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.TranscriptionStreamOptions
import com.sabreware.aide.aisdk.TranscriptionStreamPart
import com.sabreware.aide.aisdk.providers.options.optString
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * ElevenLabs' realtime speech-to-text, driven by the frame sequences the reference recorded from the
 * live service.
 *
 * There is no socket-level test here and cannot be: Ktor's `MockEngine` does not implement the WebSocket
 * upgrade, so the shared `TestServer` harness cannot serve this protocol at all. What is testable is
 * everything that decides what goes on the wire and what comes off it — the URL, the frames sent, and
 * the mapping from a recorded frame sequence to parts, which is where the partial/final distinction
 * lives and is the whole point of the streaming contract.
 */
class ElevenLabsRealtimeTranscriptionTest {

    private fun mapper(
        includeTimestamps: Boolean = false,
        includeLanguageDetection: Boolean = false,
        includeRawChunks: Boolean = false,
        language: String? = null,
    ) = ElevenLabsRealtimeMapper(
        warnings = emptyList(),
        includeTimestamps = includeTimestamps,
        includeLanguageDetection = includeLanguageDetection,
        includeRawChunks = includeRawChunks,
        detectedLanguage = language,
    )

    private fun ElevenLabsRealtimeMapper.frame(json: String) =
        on(ElevenLabsRealtimeEvent.Frame(json))

    // --- the wire the session is configured on --------------------------------------------------

    @Test
    fun `every realtime option rides the query string`() {
        val url = elevenLabsRealtimeUrl(
            baseUrl = "https://api.elevenlabs.io/v1",
            modelId = "scribe_v2_realtime",
            format = ElevenLabsRealtimeFormat("pcm_16000", 16_000),
            languageCode = "en",
            streaming = buildJsonObject {
                put("commitStrategy", "vad")
                put("minSilenceDurationMs", 300)
                put("vadThreshold", 0.5)
                putJsonArray("keyterms") { add(JsonPrimitive("Cartesia")); add(JsonPrimitive("Ktor")) }
            },
        )

        assertTrue(url.startsWith("wss://api.elevenlabs.io/v1/speech-to-text/realtime?"))
        assertContains(url, "model_id=scribe_v2_realtime")
        assertContains(url, "audio_format=pcm_16000")
        assertContains(url, "commit_strategy=vad")
        assertContains(url, "min_silence_duration_ms=300")
        assertContains(url, "vad_threshold=0.5")
        assertContains(url, "language_code=en")
        // Repeated, not comma-joined: a joined list is one keyterm the vendor has never heard of.
        assertContains(url, "keyterms=Cartesia")
        assertContains(url, "keyterms=Ktor")
    }

    @Test
    fun `language detection forces the timestamps it is delivered on`() {
        val url = elevenLabsRealtimeUrl(
            baseUrl = "https://api.elevenlabs.io/v1",
            modelId = "scribe_v2_realtime",
            format = ElevenLabsRealtimeFormat("pcm_16000", 16_000),
            languageCode = null,
            streaming = buildJsonObject { put("includeLanguageDetection", true) },
        )

        assertContains(url, "include_timestamps=true")
    }

    @Test
    fun `an unsupported sample rate fails before the socket opens`() {
        assertFailsWith<InvalidArgumentError> {
            elevenLabsRealtimeFormat(AudioFormat(type = "audio/pcm", rate = 11_025))
        }
        assertFailsWith<InvalidArgumentError> {
            elevenLabsRealtimeFormat(AudioFormat(type = "audio/pcmu", rate = 16_000))
        }
        assertEquals(
            ElevenLabsRealtimeFormat("pcm_16000", 16_000),
            // No rate is not an error: 16kHz is the vendor's own default for linear PCM.
            elevenLabsRealtimeFormat(AudioFormat(type = "audio/pcm")),
        )
    }

    @Test
    fun `context primes the first chunk only`() {
        val first = elevenLabsAudioChunk(byteArrayOf(1, 2, 3), 16_000, previousText = "so far")
        val second = elevenLabsAudioChunk(byteArrayOf(4), 16_000, previousText = null)

        assertEquals("input_audio_chunk", first.optString("message_type"))
        assertEquals("AQID", first.optString("audio_base_64"))
        assertEquals("false", first["commit"].toString())
        assertEquals("so far", first.optString("previous_text"))
        // Re-sending it would re-prime the decoder against text the speaker moved past long ago.
        assertNull(second.optString("previous_text"))

        val commit = elevenLabsCommit(16_000)
        assertEquals("", commit.optString("audio_base_64"))
        assertEquals("true", commit["commit"].toString())
    }

    // --- the frame sequence, as parts ------------------------------------------------------------

    @Test
    fun `a partial is superseded by the commit that settles it`() {
        val mapper = mapper()

        assertTrue(mapper.frame("""{"message_type":"session_started","session_id":"s1"}""").single()
            is TranscriptionStreamPart.StreamStart)
        assertTrue(mapper.sessionStarted)

        val partial = mapper.frame("""{"message_type":"partial_transcript","text":"Hello wor"}""")
            .single() as TranscriptionStreamPart.TranscriptPartial
        assertEquals("Hello wor", partial.text)
        assertEquals("s1:0", partial.id)

        val final = mapper.frame("""{"message_type":"committed_transcript","text":" Hello world "}""")
            .single() as TranscriptionStreamPart.TranscriptFinal
        // The partial carried the same id, so a consumer replaces it rather than appending; the commit
        // is where the vendor's leading and trailing space is dropped.
        assertEquals("s1:0", final.id)
        assertEquals("Hello world", final.text)
    }

    @Test
    fun `an empty commit advances the session without becoming a segment`() {
        val mapper = mapper()
        mapper.frame("""{"message_type":"session_started","session_id":"s1"}""")

        assertEquals(emptyList(), mapper.frame("""{"message_type":"committed_transcript","text":"  "}"""))

        val final = mapper.frame("""{"message_type":"committed_transcript","text":"Real"}""")
            .single() as TranscriptionStreamPart.TranscriptFinal
        // Still segment zero: an empty commit is a real commit event but not a piece of transcript, and
        // numbering it would leave a hole a consumer keyed by id waits forever to fill.
        assertEquals("s1:0", final.id)
    }

    @Test
    fun `the finish assembles the commits and the timestamped twin's timings`() {
        val mapper = mapper(includeTimestamps = true)
        mapper.frame("""{"message_type":"session_started","session_id":"s1"}""")
        mapper.frame("""{"message_type":"committed_transcript","text":"Hola"}""")
        mapper.frame(
            """{"message_type":"committed_transcript_with_timestamps","text":"Hola",""" +
                """"language_code":"es","words":[{"text":"Hola","start":0.1,"end":0.6}]}""",
        )
        mapper.on(ElevenLabsRealtimeEvent.InputCommitted)
        mapper.frame("""{"message_type":"committed_transcript","text":"adios"}""")
        mapper.frame(
            """{"message_type":"committed_transcript_with_timestamps","text":"adios",""" +
                """"words":[{"text":"adios","start":0.7,"end":1.2}]}""",
        )

        assertEquals(ElevenLabsSessionState.Finalizing, mapper.state)
        val finish = mapper.on(ElevenLabsRealtimeEvent.GraceElapsed)
            .single() as TranscriptionStreamPart.Finish
        assertEquals("Hola adios", finish.text)
        assertEquals("es", finish.language)
        assertEquals(2, finish.segments.size)
        assertEquals(1.2, finish.durationInSeconds)
    }

    @Test
    fun `the legacy final does not wait for a twin that never comes`() {
        val mapper = mapper(includeTimestamps = true)
        mapper.frame("""{"message_type":"session_started","session_id":"s1"}""")
        mapper.on(ElevenLabsRealtimeEvent.InputCommitted)

        mapper.frame("""{"message_type":"final_transcript","text":"Legacy"}""")

        // `final_transcript` is the older spelling and is never followed by a timestamped commit, so a
        // session that waited for one would hang until the caller cancelled.
        assertEquals(ElevenLabsSessionState.Finalizing, mapper.state)
    }

    @Test
    fun `silence at the end of the recording finishes rather than voids the transcript`() {
        val mapper = mapper()
        mapper.frame("""{"message_type":"session_started","session_id":"s1"}""")
        mapper.frame("""{"message_type":"committed_transcript","text":"Already done"}""")
        mapper.on(ElevenLabsRealtimeEvent.InputCommitted)

        val finish = mapper.frame(
            """{"message_type":"insufficient_audio_activity","error":"no speech"}""",
        ).single() as TranscriptionStreamPart.Finish

        assertEquals("Already done", finish.text)
        assertEquals(ElevenLabsSessionState.Finished, mapper.state)
    }

    @Test
    fun `the same refusal before any commit is a failure`() {
        val mapper = mapper()
        mapper.frame("""{"message_type":"session_started","session_id":"s1"}""")

        val error = assertFailsWith<AiSdkError> {
            mapper.frame("""{"message_type":"insufficient_audio_activity","error":"no speech"}""")
        }
        assertEquals("no speech", error.message)
    }

    @Test
    fun `a quota refusal ends the session even with a transcript in hand`() {
        val mapper = mapper()
        mapper.frame("""{"message_type":"session_started","session_id":"s1"}""")
        mapper.frame("""{"message_type":"committed_transcript","text":"Some"}""")
        mapper.on(ElevenLabsRealtimeEvent.InputCommitted)

        // Not a late finalization: the account is out of credit, and finishing quietly would report a
        // truncated transcript as a complete one.
        assertFailsWith<AiSdkError> {
            mapper.frame("""{"message_type":"quota_exceeded","error":"out of credit"}""")
        }
    }

    @Test
    fun `a close before the answering commit is a lost transcript, not a short one`() {
        val mapper = mapper()
        mapper.frame("""{"message_type":"session_started","session_id":"s1"}""")
        mapper.frame("""{"message_type":"committed_transcript","text":"Half"}""")

        val error = assertFailsWith<AiSdkError> { mapper.onClose() }
        assertContains(error.message.orEmpty(), "closed before completion")
    }

    @Test
    fun `an unparseable frame is skipped rather than fatal`() {
        val mapper = mapper()
        assertEquals(emptyList(), mapper.frame("not json"))
        assertEquals(emptyList(), mapper.frame("""{"message_type":"pong"}"""))
    }

    @Test
    fun `raw frames are passed through verbatim when asked for`() {
        val mapper = mapper(includeRawChunks = true)
        val parts = mapper.frame("""{"message_type":"session_started","session_id":"s1"}""")

        val raw = parts.first() as TranscriptionStreamPart.Raw
        assertEquals("s1", (raw.rawValue as JsonObject).optString("session_id"))
        assertTrue(parts[1] is TranscriptionStreamPart.StreamStart)
    }

    // --- the transport the caller has to supply --------------------------------------------------

    @Test
    fun `a client without the WebSockets plugin fails naming the plugin`() = runTest {
        val provider = ElevenLabsProvider(
            client = HttpClient(MockEngine { respondOk() }),
            apiKey = "k",
        )
        val model = provider.transcriptionModel("scribe_v2_realtime")

        val result = model.doStream(
            TranscriptionStreamOptions(
                audio = flowOf(byteArrayOf(1)),
                inputAudioFormat = AudioFormat(type = "audio/pcm", rate = 16_000),
            ),
        )

        // The failure is a wiring mistake, so it must name what is missing and must not be retried: the
        // plugin is not going to appear between attempts.
        val error = assertFailsWith<APICallError> { result!!.stream.collect {} }
        assertContains(error.message.orEmpty(), "WebSockets")
        assertEquals(false, error.isRetryable)
    }

    @Test
    fun `a batch model refuses to stream rather than opening a session it cannot use`() = runTest {
        val provider = ElevenLabsProvider(client = HttpClient(MockEngine { respondOk() }), apiKey = "k")

        assertFailsWith<com.sabreware.aide.aisdk.UnsupportedFunctionalityError> {
            provider.transcriptionModel("scribe_v1").doStream(
                TranscriptionStreamOptions(
                    audio = emptyFlow(),
                    inputAudioFormat = AudioFormat(type = "audio/pcm", rate = 16_000),
                ),
            )
        }
    }
}
