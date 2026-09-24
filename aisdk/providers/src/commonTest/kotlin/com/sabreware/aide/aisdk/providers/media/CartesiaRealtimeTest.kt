package com.sabreware.aide.aisdk.providers.media

import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.TranscriptionStreamOptions
import com.sabreware.aide.aisdk.TranscriptionStreamPart
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.cartesia.CARTESIA_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.cartesia.CartesiaProvider
import com.sabreware.aide.aisdk.providers.cartesia.CartesiaRealtimeEvent
import com.sabreware.aide.aisdk.providers.cartesia.CartesiaRealtimeMapper
import com.sabreware.aide.aisdk.providers.cartesia.cartesiaFinalizeFrame
import com.sabreware.aide.aisdk.providers.cartesia.cartesiaRedactedUrl
import com.sabreware.aide.aisdk.providers.cartesia.cartesiaResolvedEncoding
import com.sabreware.aide.aisdk.providers.cartesia.cartesiaStreamingEncoding
import com.sabreware.aide.aisdk.providers.testing.TestServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Cartesia Ink 2 live transcription, ported from the `doStream` half of the reference's
 * `cartesia-transcription-model.test.ts`.
 *
 * Ktor's `MockEngine` does not implement the WebSocket upgrade, so what is pinned here is what the
 * reference's mock socket actually pins: the URL and frames the provider SENDS, and the mapping from a
 * recorded frame sequence to stream parts. Both are reachable because the port splits the mapper from
 * the socket — which is why it is split.
 */
class CartesiaRealtimeTest {

    private fun provider(server: TestServer) = CartesiaProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-api-key",
    )

    /** `doStream` mints an access token over HTTP before it opens the socket. */
    private fun tokenServer() = TestServer(TestServer.json("""{"token":"tok_123"}"""))

    private fun streamOptions(
        type: String = "audio/pcm",
        rate: Int? = 16_000,
        vendor: (JsonObjectBuilder.() -> Unit)? = null,
    ) = TranscriptionStreamOptions(
        audio = emptyFlow(),
        inputAudioFormat = AudioFormat(type = type, rate = rate),
        providerOptions = vendor?.let { mapOf(CARTESIA_PROVIDER_ID to buildJsonObject(it)) },
    )

    private suspend fun sessionUrl(
        options: TranscriptionStreamOptions = streamOptions(),
        modelId: String = "ink-2",
    ): String {
        val server = tokenServer()
        val result = assertNotNull(provider(server).transcriptionModel(modelId).doStream(options))
        return assertNotNull(result.request?.body)
    }

    private fun mapper(
        useTurnDetection: Boolean = true,
        language: String = "en",
        warnings: List<Warning> = emptyList(),
    ) = CartesiaRealtimeMapper(
        warnings = warnings,
        language = language,
        useTurnDetection = useTurnDetection,
        includeRawChunks = false,
    )

    private fun CartesiaRealtimeMapper.frames(vararg json: String): List<TranscriptionStreamPart> =
        buildList {
            addAll(start())
            json.forEach { addAll(on(CartesiaRealtimeEvent.Frame(it)).parts) }
        }

    // --- Session setup ---------------------------------------------------------------------------

    @Test
    fun `turn detection picks the turns endpoint, not a flag`() = runTest {
        // The two endpoints speak different message vocabularies, so pointing at the wrong one produces
        // frames the mapper never matches — a session that runs to completion and reports nothing.
        assertContains(sessionUrl(), "/stt/turns/websocket")
        assertContains(
            sessionUrl(streamOptions { put("streaming", buildJsonObject { put("turnDetection", false) }) }),
            "/stt/websocket",
        )
    }

    @Test
    fun `the session carries the model, encoding, rate and pinned version`() = runTest {
        val url = sessionUrl()

        assertTrue(url.startsWith("wss://"), url)
        assertContains(url, "model=ink-2")
        assertContains(url, "encoding=pcm_s16le")
        assertContains(url, "sample_rate=16000")
        // The same version the REST half is pinned to; the socket rejects a request without it.
        assertContains(url, "cartesia_version=2026-03-01")
    }

    @Test
    fun `the reported request URL never carries the access token`() = runTest {
        val url = sessionUrl()

        // A live session has no request body, so the URL is all a bug report has — and the token is in
        // it. Reporting it verbatim would copy a working credential into every log that records one.
        assertTrue("access_token" !in url, url)
        assertContains(url, "model=ink-2")
    }

    @Test
    fun `redaction keeps every other parameter`() {
        assertEquals(
            "wss://api.cartesia.ai/stt/websocket?model=ink-2&encoding=pcm_s16le",
            cartesiaRedactedUrl(
                "wss://api.cartesia.ai/stt/websocket?model=ink-2&access_token=secret&encoding=pcm_s16le",
            ),
        )
    }

    @Test
    fun `the language rides the plain endpoint only`() = runTest {
        val withDetection = sessionUrl(streamOptions { put("language", "en") })
        assertTrue("language=en" !in withDetection, withDetection)

        val plain = sessionUrl(
            streamOptions {
                put("language", "en")
                put("streaming", buildJsonObject { put("turnDetection", false) })
            },
        )
        assertContains(plain, "language=en")
    }

    // --- Refusals --------------------------------------------------------------------------------

    @Test
    fun `a non-Ink model has no socket endpoint and says so`() = runTest {
        assertFailsWith<UnsupportedFunctionalityError> {
            provider(tokenServer()).transcriptionModel("whisper-large").doStream(streamOptions())
        }
    }

    @Test
    fun `a language Ink 2 cannot serve fails before the socket opens`() = runTest {
        // Sending it is accepted at the handshake and then transcribed as English, so the mismatch
        // never surfaces as an error — only as a wrong transcript.
        assertFailsWith<InvalidArgumentError> {
            provider(tokenServer()).transcriptionModel("ink-2")
                .doStream(streamOptions { put("language", "fr") })
        }
    }

    @Test
    fun `an audio format with no encoding of its own is refused here, not at the handshake`() {
        assertEquals("pcm_s16le", cartesiaStreamingEncoding("audio/pcm"))
        assertEquals("pcm_mulaw", cartesiaStreamingEncoding("audio/pcmu"))
        assertEquals("pcm_alaw", cartesiaStreamingEncoding("audio/pcma"))
        assertFailsWith<InvalidArgumentError> { cartesiaStreamingEncoding("audio/mpeg") }
    }

    @Test
    fun `widening generic PCM is silent, contradicting a companded type warns`() {
        val widening = mutableListOf<Warning>()
        assertEquals(
            "pcm_f32le",
            cartesiaResolvedEncoding(
                format = AudioFormat("audio/pcm", 16_000),
                streaming = buildJsonObject { put("encoding", "pcm_f32le") },
                warnings = widening,
            ),
        )
        // `audio/pcm` does not say how many bits, so a linear override is the option working as meant.
        assertTrue(widening.isEmpty())

        val contradicting = mutableListOf<Warning>()
        assertEquals(
            "pcm_s16le",
            cartesiaResolvedEncoding(
                format = AudioFormat("audio/pcmu", 8_000),
                streaming = buildJsonObject { put("encoding", "pcm_s16le") },
                warnings = contradicting,
            ),
        )
        // Still sent — the caller may know something the media type does not — but never silently.
        assertEquals(1, contradicting.size)
    }

    // --- Frames ----------------------------------------------------------------------------------

    @Test
    fun `the turn endpoint revises, then settles`() {
        val parts = mapper().frames(
            """{"type":"turn.update","request_id":"r1","transcript":"hello"}""",
            """{"type":"turn.eager_end","request_id":"r1","transcript":"hello wor"}""",
            """{"type":"turn.end","request_id":"r1","transcript":"hello world"}""",
            """{"type":"done"}""",
        )

        // A live transcriber revises what it said a moment ago; a consumer that treats every emission
        // as settled renders text that jumps.
        assertEquals(
            listOf("hello", "hello wor"),
            parts.filterIsInstance<TranscriptionStreamPart.TranscriptPartial>().map { it.text },
        )
        assertEquals(
            listOf("hello world"),
            parts.filterIsInstance<TranscriptionStreamPart.TranscriptFinal>().map { it.text },
        )
        assertEquals("hello world", parts.filterIsInstance<TranscriptionStreamPart.Finish>().single().text)
    }

    @Test
    fun `the plain endpoint distinguishes final by flag and accumulates duration`() {
        val parts = mapper(useTurnDetection = false).frames(
            """{"type":"transcript","request_id":"r1","text":"hello ","is_final":false,"duration":0.5}""",
            """{"type":"transcript","request_id":"r1","text":"hello ","is_final":true,"duration":1.5}""",
            """{"type":"transcript","request_id":"r1","text":"world","is_final":true,"duration":1.0}""",
            """{"type":"done"}""",
        )

        val partial = parts.filterIsInstance<TranscriptionStreamPart.TranscriptPartial>().single()
        assertEquals(0.5, partial.durationInSeconds)
        val finish = parts.filterIsInstance<TranscriptionStreamPart.Finish>().single()
        // Joined with NO separator: these are fragments of one utterance, already spaced where they
        // belong, so a space between them lands mid-sentence.
        assertEquals("hello world", finish.text)
        assertEquals(2.5, finish.durationInSeconds)
    }

    @Test
    fun `turn finals are joined with a space, fragments are not`() {
        val turns = mapper(useTurnDetection = true).frames(
            """{"type":"turn.end","transcript":"one"}""",
            """{"type":"turn.end","transcript":"two"}""",
            """{"type":"done"}""",
        )
        assertEquals("one two", turns.filterIsInstance<TranscriptionStreamPart.Finish>().single().text)
    }

    @Test
    fun `flush_done is answered with close, which is what ends the session`() {
        val mapper = mapper()
        mapper.start()

        val mapped = mapper.on(CartesiaRealtimeEvent.Frame("""{"type":"flush_done"}"""))

        // Without the answering frame Cartesia holds the socket open and the stream never ends.
        assertEquals("close", mapped.reply)
    }

    @Test
    fun `an error frame ends the session rather than reporting an empty transcript`() {
        val mapper = mapper()
        mapper.start()

        val failure = assertFailsWith<Throwable> {
            mapper.on(CartesiaRealtimeEvent.Frame("""{"type":"error","message":"quota exceeded"}"""))
        }
        assertContains(failure.message.orEmpty(), "quota exceeded")
    }

    @Test
    fun `a close finishes with what was transcribed`() {
        val mapper = mapper()
        mapper.start()
        mapper.on(CartesiaRealtimeEvent.Frame("""{"type":"turn.end","transcript":"partial run"}"""))

        // Cartesia closes after `done`, and also closes without one when the session ends on its own
        // terms — so a close finishes rather than fails, which is the opposite of the ElevenLabs rule.
        val finish = mapper.onClose().filterIsInstance<TranscriptionStreamPart.Finish>().single()
        assertEquals("partial run", finish.text)
    }

    @Test
    fun `the end of input is spelled differently per endpoint`() {
        // A JSON control message on the turn endpoint, a bare word on the plain one; sending the wrong
        // one leaves the tail of the recording untranscribed.
        assertEquals("""{"type":"close"}""", cartesiaFinalizeFrame(useTurnDetection = true))
        assertEquals("finalize", cartesiaFinalizeFrame(useTurnDetection = false))
    }
}
