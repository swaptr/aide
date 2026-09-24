package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.TranscriptionStreamOptions
import com.sabreware.aide.aisdk.TranscriptionStreamPart
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Gemini Live transcription, ported from the `doStream` half of the reference's
 * `google-transcription-model.test.ts`.
 *
 * Ktor's `MockEngine` cannot serve a WebSocket upgrade, so what is pinned is what the reference's mock
 * socket pins: the setup frame the provider sends, and the mapping from a recorded frame sequence to
 * stream parts — both reachable because the mapper is split from the socket.
 */
class GoogleLiveTranscriptionTest {

    private fun model(id: String = "gemini-3.5-transcribe-live") =
        GoogleProvider(
            client = HttpClient(TestServer(TestServer.json("{}")).engine()),
            apiKey = "test-api-key",
        ).transcriptionModel(id)

    private fun streamOptions(
        type: String = "audio/pcm",
        rate: Int? = 16_000,
        vendor: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null,
    ) = TranscriptionStreamOptions(
        audio = emptyFlow(),
        inputAudioFormat = AudioFormat(type = type, rate = rate),
        providerOptions = vendor?.let { mapOf(GOOGLE_PROVIDER_ID to buildJsonObject(it)) },
    )

    private fun mapper() = GoogleLiveTranscriptionMapper(warnings = emptyList(), includeRawChunks = false)

    private fun GoogleLiveTranscriptionMapper.feed(
        vararg json: String,
    ): List<TranscriptionStreamPart> = buildList {
        addAll(start())
        json.forEach { addAll(on(GoogleLiveTranscriptionEvent.Frame(it))) }
    }

    // --- Session setup ---------------------------------------------------------------------------

    @Test
    fun `the setup names the model and enables input transcription`() = runTest {
        val result = assertNotNull(model().doStream(streamOptions()))
        val setup = assertNotNull(result.request?.body)
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(setup).jsonObject

        assertEquals("models/gemini-3.5-transcribe-live", parsed["model"]?.jsonPrimitive?.content)
        // An EMPTY object is what turns transcription on; omitting the key turns it off, so the
        // difference between `{}` and absent is the whole feature.
        assertNotNull(parsed["inputAudioTranscription"])
        // No generationConfig: sending `responseModalities: ["TEXT"]` suppresses the FINAL segments on
        // the live endpoint, leaving only interim partials that never settle.
        assertTrue("generationConfig" !in parsed, setup)
    }

    @Test
    fun `the reported request is the setup body, never the URL that carries the key`() = runTest {
        val result = assertNotNull(model().doStream(streamOptions()))
        val body = assertNotNull(result.request?.body)

        assertTrue("test-api-key" !in body, body)
        assertContains(body, "gemini-3.5-transcribe-live")
    }

    @Test
    fun `a language hint reaches the transcription config`() = runTest {
        val result = assertNotNull(model().doStream(streamOptions { put("language", "de") }))
        val parsed = kotlinx.serialization.json.Json
            .parseToJsonElement(assertNotNull(result.request?.body)).jsonObject

        assertEquals(
            "de",
            parsed["inputAudioTranscription"]?.jsonObject?.get("language")?.jsonPrimitive?.content,
        )
    }

    // --- Refusals --------------------------------------------------------------------------------

    @Test
    fun `a unary model has no socket and says so instead of opening one`() = runTest {
        assertFailsWith<InvalidArgumentError> {
            model("gemini-3.5-transcribe").doStream(streamOptions())
        }
    }

    @Test
    fun `an unsupported audio shape fails here, not at the handshake`() = runTest {
        // Live audio is naked PCM with nothing to sniff, and the handshake rejection names neither the
        // rate nor the format.
        assertFailsWith<InvalidArgumentError> { model().doStream(streamOptions(rate = 44_100)) }
        assertFailsWith<InvalidArgumentError> { model().doStream(streamOptions(type = "audio/mpeg")) }
    }

    @Test
    fun `the audio chunk repeats the rate, because the session has none`() {
        val chunk = googleLiveTranscriptionChunk(byteArrayOf(1, 2, 3), 16_000)
        val audio = chunk["realtimeInput"]?.jsonObject?.get("audio")?.jsonObject

        assertEquals("audio/pcm;rate=16000", audio?.get("mimeType")?.jsonPrimitive?.content)
        assertEquals("AQID", audio?.get("data")?.jsonPrimitive?.content)
    }

    // --- Frames ----------------------------------------------------------------------------------

    @Test
    fun `deltas accumulate into the segment a finished flag settles`() {
        val parts = mapper().feed(
            """{"setupComplete":{}}""",
            """{"serverContent":{"inputTranscription":{"text":"hello ","languageCode":"en-US"}}}""",
            """{"serverContent":{"inputTranscription":{"text":"world.","finished":true}}}""",
        )

        assertEquals(
            listOf("hello ", "world."),
            parts.filterIsInstance<TranscriptionStreamPart.TranscriptDelta>().map { it.delta },
        )
        assertEquals(
            "hello world.",
            parts.filterIsInstance<TranscriptionStreamPart.TranscriptFinal>().single().text,
        )
    }

    @Test
    fun `the top-level spelling of inputTranscription is read too`() {
        // Early sessions put it outside serverContent; both spellings are live, and reading only one
        // produces an empty transcript from a session that worked.
        val parts = mapper().feed(
            """{"setupComplete":{}}""",
            """{"inputTranscription":{"text":"hi","finished":true}}""",
        )

        assertEquals("hi", parts.filterIsInstance<TranscriptionStreamPart.TranscriptFinal>().single().text)
    }

    @Test
    fun `interim text is revisable and superseded by a real delta`() {
        val parts = mapper().feed(
            """{"setupComplete":{}}""",
            """{"serverContent":{"interimInputTranscription":{"text":"hel"}}}""",
            """{"serverContent":{"inputTranscription":{"text":"hello"}}}""",
            """{"serverContent":{"turnComplete":true}}""",
        )

        assertEquals(
            "hel",
            parts.filterIsInstance<TranscriptionStreamPart.TranscriptPartial>().single().text,
        )
        // The settled delta replaces the interim guess rather than being appended to it.
        assertEquals(
            "hello",
            parts.filterIsInstance<TranscriptionStreamPart.TranscriptFinal>().single().text,
        )
    }

    @Test
    fun `an interim-only turn still produces a final, rather than losing the sentence`() {
        val parts = mapper().feed(
            """{"setupComplete":{}}""",
            """{"serverContent":{"interimInputTranscription":{"text":"only interim"}}}""",
            """{"serverContent":{"turnComplete":true}}""",
        )

        // The server never sent a settled delta; the newest revisable text is the only record of what
        // was said, so dropping it would lose a sentence the user watched appear.
        assertEquals(
            "only interim",
            parts.filterIsInstance<TranscriptionStreamPart.TranscriptFinal>().single().text,
        )
    }

    @Test
    fun `an idle interaction status after the audio ended finishes immediately`() {
        val mapper = mapper()
        mapper.feed(
            """{"setupComplete":{}}""",
            """{"serverContent":{"inputTranscription":{"text":"done","finished":true}}}""",
        )
        mapper.on(GoogleLiveTranscriptionEvent.AudioEnded)

        val parts = mapper.on(
            GoogleLiveTranscriptionEvent.Frame("""{"serverContent":{"interactionStatus":"IDLE"}}"""),
        )

        // The one signal that ends the session without waiting out the quiet window.
        assertEquals("done", parts.filterIsInstance<TranscriptionStreamPart.Finish>().single().text)
        assertTrue(mapper.finished)
    }

    @Test
    fun `the quiet window finishes a session the server never terminates`() {
        val mapper = mapper()
        mapper.feed(
            """{"setupComplete":{}}""",
            """{"serverContent":{"inputTranscription":{"text":"trailing"}}}""",
        )
        mapper.on(GoogleLiveTranscriptionEvent.AudioEnded)

        val parts = mapper.on(GoogleLiveTranscriptionEvent.GraceElapsed)

        // Gemini emits trailing fragments after audioStreamEnd and then simply stops; without the
        // window the stream hangs after the last word.
        assertEquals("trailing", parts.filterIsInstance<TranscriptionStreamPart.Finish>().single().text)
    }

    @Test
    fun `usage metadata rides the finish under the provider namespace`() {
        val mapper = mapper()
        mapper.feed(
            """{"setupComplete":{}}""",
            """{"usageMetadata":{"totalTokenCount":42}}""",
            """{"serverContent":{"inputTranscription":{"text":"x","finished":true}}}""",
        )
        mapper.on(GoogleLiveTranscriptionEvent.AudioEnded)

        val finish = mapper.on(GoogleLiveTranscriptionEvent.GraceElapsed)
            .filterIsInstance<TranscriptionStreamPart.Finish>().single()
        val usage = finish.providerMetadata?.get(GOOGLE_PROVIDER_ID)?.get("usageMetadata") as? JsonObject

        assertEquals(42, usage?.get("totalTokenCount")?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `an error frame ends the session rather than reporting a short transcript`() {
        val mapper = mapper()
        mapper.start()

        val failure = assertFailsWith<Throwable> {
            mapper.on(GoogleLiveTranscriptionEvent.Frame("""{"error":{"message":"quota exceeded"}}"""))
        }
        assertContains(failure.message.orEmpty(), "quota exceeded")
    }

    @Test
    fun `a close before the audio ended is a failure, after it is a finish`() {
        val early = mapper()
        early.start()
        // Audio was transcribed and then thrown away; a short transcript here is indistinguishable
        // from a complete one, so it has to be an error.
        assertFailsWith<Throwable> { early.onClose() }

        val late = mapper()
        late.feed(
            """{"setupComplete":{}}""",
            """{"serverContent":{"inputTranscription":{"text":"all of it","finished":true}}}""",
        )
        late.on(GoogleLiveTranscriptionEvent.AudioEnded)
        assertEquals(
            "all of it",
            late.onClose().filterIsInstance<TranscriptionStreamPart.Finish>().single().text,
        )
    }

    @Test
    fun `a frame that is not JSON is skipped rather than fatal`() {
        val parts = mapper().feed(
            """{"setupComplete":{}}""",
            "not json at all",
            """{"serverContent":{"inputTranscription":{"text":"survived","finished":true}}}""",
        )

        assertEquals(
            "survived",
            parts.filterIsInstance<TranscriptionStreamPart.TranscriptFinal>().single().text,
        )
    }
}
