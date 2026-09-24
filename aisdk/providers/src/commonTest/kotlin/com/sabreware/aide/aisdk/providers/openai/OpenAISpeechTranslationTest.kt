package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.AiSdkError
import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.ResponseMetadata
import com.sabreware.aide.aisdk.SpeechTranslationStreamOptions
import com.sabreware.aide.aisdk.SpeechTranslationStreamPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.ProviderSocket
import com.sabreware.aide.aisdk.util.SocketClosed
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

/**
 * OpenAI realtime translation, driven by the frame sequences of
 * `speech-translation/openai-speech-translation-model.test.ts` and the session the reference recorded.
 *
 * There is no socket-level test here and cannot be: Ktor's `MockEngine` does not implement the WebSocket
 * upgrade. What is testable is everything that decides what goes on the wire and what comes off it —
 * the URL, the subprotocol the credential rides in, the frames sent, and the mapping from a recorded
 * frame sequence to parts — which is what the reference's mock socket pins too.
 */
class OpenAISpeechTranslationTest {

    private val session = """{"type":"session.update","session":{"audio":{"input":{"transcription":""" +
        """{"model":"gpt-realtime-whisper"},"noise_reduction":null},"output":{"language":"es"}}}}"""

    private fun mapper(warnings: List<Warning> = emptyList(), includeRawChunks: Boolean = false) =
        OpenAIRealtimeTranslationMapper(warnings, includeRawChunks)

    private fun OpenAIRealtimeTranslationMapper.feed(vararg frames: String) = buildList {
        addAll(start())
        frames.forEach { addAll(on(it)) }
    }

    private fun inputDelta(text: String, id: String = "event-1") =
        """{"type":"session.input_transcript.delta","event_id":"$id","delta":"$text"}"""

    private fun outputDelta(text: String, id: String = "event-2") =
        """{"type":"session.output_transcript.delta","event_id":"$id","delta":"$text"}"""

    private fun audioDelta(base64: String, id: String = "event-3") =
        """{"type":"session.output_audio.delta","event_id":"$id","delta":"$base64"}"""

    private val closed = """{"type":"session.closed","event_id":"event-4"}"""

    // --- the wire the session is configured on --------------------------------------------------

    @Test
    fun `the socket URL names the model in its query`() {
        assertEquals(
            "wss://api.openai.com/v1/realtime/translations?model=gpt-realtime-translate",
            openAIRealtimeTranslationUrl("https://api.openai.com/v1", "gpt-realtime-translate"),
        )
    }

    @Test
    fun `the bearer token moves into the subprotocol and the header is stripped`() {
        val connection = openAIRealtimeConnection(
            mapOf(
                "Authorization" to "Bearer test-api-key",
                "OpenAI-Organization" to "test-organization",
                "Custom-Header" to "custom-value",
            ),
        )

        assertEquals(listOf("realtime", "openai-insecure-api-key.test-api-key"), connection.protocols)
        // OpenAI rejects a handshake that sends both auth channels.
        assertTrue(connection.headers.keys.none { it.equals("authorization", ignoreCase = true) })
        assertEquals("test-organization", connection.headers["OpenAI-Organization"])
        assertEquals("custom-value", connection.headers["Custom-Header"])
    }

    @Test
    fun `no bearer token means the realtime protocol alone and the headers untouched`() {
        val headers = mapOf("Custom-Header" to "custom-value")

        val connection = openAIRealtimeConnection(headers)

        assertEquals(listOf("realtime"), connection.protocols)
        assertEquals(headers, connection.headers)
    }

    @Test
    fun `the auth scheme is matched case-insensitively`() {
        val connection = openAIRealtimeConnection(mapOf("authorization" to "bearer sk-test"))

        assertEquals(listOf("realtime", "openai-insecure-api-key.sk-test"), connection.protocols)
    }

    @Test
    fun `the session frame is the reference's, byte for byte`() {
        assertEquals(session, openAIRealtimeTranslationSession("es").toString())
    }

    @Test
    fun `audio is appended base64 in a text frame, and the input is closed by a frame`() {
        assertEquals(
            """{"type":"session.input_audio_buffer.append","audio":"AQID"}""",
            openAIRealtimeAudioFrame(byteArrayOf(1, 2, 3)).toString(),
        )
        assertEquals("""{"type":"session.close"}""", OPENAI_REALTIME_SESSION_CLOSE.toString())
    }

    @Test
    fun `the request record is the session frame and the response names the model`() = runTest {
        val result = model().doStream(options())

        assertEquals(session, result.request?.body)
        assertTrue("test-api-key" !in result.request?.body.orEmpty(), "the API key must never reach a request record")
        assertEquals(
            ResponseMetadata(timestamp = 0L, modelId = "gpt-realtime-translate"),
            result.response?.metadata,
        )
    }

    @Test
    fun `the provider offers the modality under its own id`() = runTest {
        val model = OpenAIProvider(HttpClient(MockEngine { respondOk() }), "test-api-key")
            .speechTranslationModel("gpt-realtime-translate")

        assertNotNull(model)
        assertEquals(OPENAI_PROVIDER_ID, model.provider)
        assertEquals("gpt-realtime-translate", model.modelId)
    }

    // --- what the model refuses before opening a socket -----------------------------------------

    @Test
    fun `only 24 kHz PCM input is accepted`() = runTest {
        val model = model()

        val nonPcm = assertFailsWith<InvalidArgumentError> {
            model.doStream(options(format = AudioFormat("audio/pcmu", 24_000)))
        }
        val wrongRate = assertFailsWith<InvalidArgumentError> {
            model.doStream(options(format = AudioFormat("audio/pcm", 16_000)))
        }

        assertEquals("inputAudioFormat", nonPcm.argument)
        assertEquals(
            "The OpenAI Realtime translation API only supports 24kHz 16-bit PCM input audio.",
            nonPcm.message,
        )
        assertEquals("inputAudioFormat", wrongRate.argument)
        // A declared type with no rate is taken at the endpoint's one rate.
        model.doStream(options(format = AudioFormat("audio/pcm")))
    }

    @Test
    fun `a translation with no target language is refused`() = runTest {
        val error = assertFailsWith<InvalidArgumentError> {
            model().doStream(options(targetLanguage = " "))
        }

        assertEquals("targetLanguage", error.argument)
        assertEquals("targetLanguage is required for translation model 'gpt-realtime-translate'.", error.message)
    }

    @Test
    fun `the two fields the endpoint ignores are warned about rather than sent`() {
        val warnings = openAIRealtimeTranslationWarnings(
            options(sourceLanguage = "en", outputFormat = AudioFormat("audio/pcm", 24_000)),
        )

        assertEquals(
            listOf(
                Warning.Unsupported(
                    feature = "sourceLanguage",
                    details = "The OpenAI Realtime translation API auto-detects the source language and " +
                        "does not accept a source language.",
                ),
                Warning.Unsupported(
                    feature = "outputAudioFormat",
                    details = "The OpenAI Realtime translation API always outputs 24kHz 16-bit PCM audio " +
                        "and does not accept an output audio format.",
                ),
            ),
            warnings,
        )
        assertEquals(emptyList(), openAIRealtimeTranslationWarnings(options()))
    }

    // --- the frame sequence, as parts -----------------------------------------------------------

    @Test
    fun `one session produces both text channels, its audio, both finals and a finish`() {
        val parts = mapper().feed(inputDelta("Hello"), outputDelta("Hola"), audioDelta("BAUG"), closed)

        assertEquals(
            listOf(
                SpeechTranslationStreamPart.StreamStart(emptyList()),
                SpeechTranslationStreamPart.SourceTranscriptDelta("Hello"),
                SpeechTranslationStreamPart.OutputTextDelta("Hola"),
                SpeechTranslationStreamPart.Audio(byteArrayOf(4, 5, 6)),
                SpeechTranslationStreamPart.SourceTranscriptFinal("Hello"),
                SpeechTranslationStreamPart.OutputTextFinal("Hola"),
                SpeechTranslationStreamPart.Finish(sourceText = "Hello", outputText = "Hola"),
            ),
            parts,
        )
    }

    @Test
    fun `deltas accumulate until the session closes`() {
        val parts = mapper().feed(
            inputDelta("Hello "),
            inputDelta("world", id = "event-2"),
            outputDelta("Hola ", id = "event-3"),
            outputDelta("mundo", id = "event-4"),
            closed,
        )

        assertEquals(
            SpeechTranslationStreamPart.Finish(sourceText = "Hello world", outputText = "Hola mundo"),
            parts.last(),
        )
    }

    @Test
    fun `the reference's recorded session maps to one audio chunk, both finals and the finish`() {
        val parts = mapper().feed(*OpenAIFixtures.realtimeTranslationChunks.toTypedArray())

        assertEquals(
            listOf(SpeechTranslationStreamPart.Audio(byteArrayOf(1, 2, 3))),
            parts.filterIsInstance<SpeechTranslationStreamPart.Audio>(),
        )
        assertEquals(
            listOf(
                SpeechTranslationStreamPart.SourceTranscriptFinal(" The quick brown fox jumps over the lazy"),
                SpeechTranslationStreamPart.OutputTextFinal("La rápida zorra marrón salta sobre el perro perezoso."),
                SpeechTranslationStreamPart.Finish(
                    sourceText = " The quick brown fox jumps over the lazy",
                    outputText = "La rápida zorra marrón salta sobre el perro perezoso.",
                ),
            ),
            parts.takeLast(3),
        )
    }

    @Test
    fun `the warnings ride the stream start, which the first frame implies if the open was not reported`() {
        val warnings = listOf(Warning.Unsupported("sourceLanguage"))
        val mapper = mapper(warnings = warnings)

        val parts = mapper.on(outputDelta("Hola")) + mapper.on(closed)

        assertEquals(SpeechTranslationStreamPart.StreamStart(warnings), parts.first())
        assertEquals(emptyList(), mapper.start())
    }

    @Test
    fun `raw chunks are forwarded verbatim when the caller asked for them`() {
        val parts = mapper(includeRawChunks = true).feed(
            """{"type":"session.updated"}""",
            outputDelta("Hola", id = "event-1"),
            """{"type":"session.closed","event_id":"event-2"}""",
        )

        assertEquals(
            listOf(
                """{"type":"session.updated"}""",
                """{"type":"session.output_transcript.delta","event_id":"event-1","delta":"Hola"}""",
                """{"type":"session.closed","event_id":"event-2"}""",
            ),
            parts.filterIsInstance<SpeechTranslationStreamPart.Raw>().map { it.rawValue.toString() },
        )
    }

    @Test
    fun `a server error is a part, and the session goes on`() {
        val parts = mapper().feed(
            """{"type":"error","error":{"message":"invalid target language"}}""",
            outputDelta("Hola"),
            closed,
        )

        val error = assertIs<SpeechTranslationStreamPart.Error>(parts[1])
        assertEquals("invalid target language", error.error.message)
        assertEquals(SpeechTranslationStreamPart.Finish(sourceText = "", outputText = "Hola"), parts.last())
    }

    @Test
    fun `an empty audio delta carries nothing and is skipped`() {
        val parts = mapper().feed(audioDelta(""), audioDelta("BAUG", id = "event-2"), outputDelta("Hola"), closed)

        assertEquals(
            listOf(SpeechTranslationStreamPart.Audio(byteArrayOf(4, 5, 6))),
            parts.filterIsInstance<SpeechTranslationStreamPart.Audio>(),
        )
    }

    @Test
    fun `a frame that is not JSON is skipped rather than fatal`() {
        val mapper = mapper()
        mapper.start()

        assertEquals(emptyList(), mapper.on("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun `a close before the finish is a failure that quotes the close diagnostics`() {
        val mapper = mapper()
        mapper.feed(outputDelta("Ho"))

        val withReason = assertFailsWith<AiSdkError> {
            mapper.onClose(SocketClosed(code = 1011, reason = "internal server error"))
        }
        val unknown = assertFailsWith<AiSdkError> { mapper.onClose(null) }

        assertEquals(
            "OpenAI realtime translation WebSocket closed unexpectedly before finishing " +
                "(code 1011, reason: internal server error).",
            withReason.message,
        )
        assertContains(unknown.message.orEmpty(), "(code unknown).")
    }

    @Test
    fun `messages after the finish are a no-op`() {
        val mapper = mapper()
        val parts = mapper.feed(outputDelta("Hola"), closed)
        assertIs<SpeechTranslationStreamPart.Finish>(parts.last())

        assertEquals(
            emptyList(),
            mapper.on(outputDelta("late", id = "event-3")) +
                mapper.on("""{"type":"session.closed","event_id":"event-4"}""") +
                mapper.onClose(null),
        )
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun model(headers: Map<String, String> = mapOf("Authorization" to "Bearer test-api-key")) =
        OpenAISpeechTranslationModel(
            modelId = "gpt-realtime-translate",
            socket = ProviderSocket(HttpClient(MockEngine { respondOk() })),
            baseUrl = "https://api.openai.com/v1",
            headers = headers,
            now = { 0L },
        )

    private fun options(
        format: AudioFormat = AudioFormat("audio/pcm", 24_000),
        targetLanguage: String = "es",
        sourceLanguage: String? = null,
        outputFormat: AudioFormat? = null,
    ) = SpeechTranslationStreamOptions(
        audio = emptyFlow(),
        inputAudioFormat = format,
        targetLanguage = targetLanguage,
        sourceLanguage = sourceLanguage,
        outputAudioFormat = outputFormat,
    )
}
