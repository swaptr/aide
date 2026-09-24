package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.AiSdkError
import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.SpeechTranslationStreamOptions
import com.sabreware.aide.aisdk.SpeechTranslationStreamPart
import com.sabreware.aide.aisdk.SpeechTranslationUsage
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.ProviderSocket
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Gemini Live translation, driven by the frame sequences the reference recorded from the live service.
 *
 * There is no socket-level test here and cannot be: Ktor's `MockEngine` does not implement the WebSocket
 * upgrade, so the shared `TestServer` harness cannot serve this protocol at all. What is testable is
 * everything that decides what goes on the wire and what comes off it — the URL, the frames sent, and
 * the mapping from a recorded frame sequence to parts, which is where both text channels and the
 * partial/final distinction live and is the whole point of the contract.
 */
class GoogleSpeechTranslationTest {

    private fun mapper(
        warnings: List<Warning> = emptyList(),
        includeRawChunks: Boolean = false,
        finishGraceMs: Long = 0,
    ) = GoogleLiveTranslationMapper(warnings, includeRawChunks, finishGraceMs)

    private fun GoogleLiveTranslationMapper.frame(json: String) =
        on(GoogleTranslationEvent.Frame(json))

    private fun GoogleLiveTranslationMapper.started() = frame("""{"setupComplete":{}}""")

    // --- the wire the session is configured on --------------------------------------------------

    @Test
    fun `the key rides the query string and the base URL's version segment comes off`() {
        val url = googleLiveTranslationUrl(
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKey = "test-api-key",
        )

        assertEquals(
            "wss://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent" +
                "?key=test-api-key",
            url,
        )
    }

    @Test
    fun `a base URL with no version segment is left alone`() {
        val url = googleLiveTranslationUrl("https://proxy.internal", "k")

        assertTrue(url.startsWith("wss://proxy.internal/ws/"))
    }

    @Test
    fun `the request record carries the setup body, never the URL that holds the key`() = runTest {
        val model = model(apiKey = "secret-key")

        val result = model.doStream(options())

        val body = result.request?.body.orEmpty()
        assertContains(body, "\"targetLanguageCode\":\"es\"")
        assertTrue("secret-key" !in body, "the API key must never reach a request record")
        assertTrue("wss://" !in body)
        assertEquals("gemini-3.5-live-translate-preview", result.response?.metadata?.modelId)
    }

    @Test
    fun `the setup asks for both transcriptions and the vendor's echo option`() {
        val setup = googleLiveTranslationSetup(
            modelId = "gemini-3.5-live-translate-preview",
            targetLanguage = "es",
            vendorOptions = buildJsonObject { put("echoTargetLanguage", true) },
        )

        assertEquals(
            """{"model":"models/gemini-3.5-live-translate-preview",""" +
                """"generationConfig":{"responseModalities":["AUDIO"],""" +
                """"translationConfig":{"targetLanguageCode":"es","echoTargetLanguage":true}},""" +
                """"inputAudioTranscription":{},"outputAudioTranscription":{}}""",
            setup.toString(),
        )
    }

    @Test
    fun `a model named by its full resource path is not prefixed again`() {
        val setup = googleLiveTranslationSetup("models/gemini-live", "es", null)

        assertEquals("\"models/gemini-live\"", setup["model"].toString())
    }

    @Test
    fun `an audio chunk declares its rate on every frame`() {
        val chunk = googleLiveAudioChunk(byteArrayOf(1, 2, 3), 16_000)

        assertEquals(
            """{"realtimeInput":{"audio":{"data":"AQID","mimeType":"audio/pcm;rate=16000"}}}""",
            chunk.toString(),
        )
        assertEquals("""{"realtimeInput":{"audioStreamEnd":true}}""", GOOGLE_LIVE_AUDIO_STREAM_END.toString())
    }

    // --- what the model refuses before opening a socket -----------------------------------------

    @Test
    fun `only 16 kHz PCM input is accepted`() = runTest {
        val model = model()

        val nonPcm = assertFailsWith<InvalidArgumentError> {
            model.doStream(options(format = AudioFormat("audio/pcmu", 8_000)))
        }
        val wrongRate = assertFailsWith<InvalidArgumentError> {
            model.doStream(options(format = AudioFormat("audio/pcm", 24_000)))
        }

        assertEquals("inputAudioFormat", nonPcm.argument)
        assertContains(nonPcm.message.orEmpty(), "16kHz 16-bit PCM")
        assertEquals("inputAudioFormat", wrongRate.argument)
    }

    @Test
    fun `a translation with no target language is refused`() = runTest {
        val error = assertFailsWith<InvalidArgumentError> {
            model().doStream(options(targetLanguage = " "))
        }

        assertEquals("targetLanguage", error.argument)
    }

    @Test
    fun `the two fields the Live API ignores are warned about rather than sent`() {
        val warnings = googleLiveTranslationWarnings(
            options(sourceLanguage = "en", outputFormat = AudioFormat("audio/pcm", 24_000)),
        )

        assertEquals(
            listOf("sourceLanguage", "outputAudioFormat"),
            warnings.map { (it as Warning.Unsupported).feature },
        )
        assertEquals(emptyList(), googleLiveTranslationWarnings(options()))
    }

    // --- the frame sequence, as parts -----------------------------------------------------------

    @Test
    fun `one turn produces both text channels, its audio, and a finish`() {
        val mapper = mapper(warnings = listOf(Warning.Unsupported("sourceLanguage")))

        val parts = mapper.started() +
            mapper.on(GoogleTranslationEvent.AudioEnded) +
            mapper.frame("""{"serverContent":{"inputTranscription":{"text":"Hello"}}}""") +
            mapper.frame(
                """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"BAUG"}}]},""" +
                    """"outputTranscription":{"text":"Hola"}}}""",
            ) +
            mapper.frame(
                """{"usageMetadata":{"promptTokensDetails":[{"modality":"AUDIO","tokenCount":10},""" +
                    """{"modality":"TEXT","tokenCount":2}],"responseTokensDetails":""" +
                    """[{"modality":"AUDIO","tokenCount":20},{"modality":"TEXT","tokenCount":3}]}}""",
            ) +
            mapper.frame("""{"serverContent":{"turnComplete":true}}""") +
            mapper.on(GoogleTranslationEvent.GraceElapsed)

        assertEquals(
            listOf(
                SpeechTranslationStreamPart.StreamStart(listOf(Warning.Unsupported("sourceLanguage"))),
                SpeechTranslationStreamPart.SourceTranscriptDelta("Hello", "google-item-0"),
                SpeechTranslationStreamPart.Audio(byteArrayOf(4, 5, 6), "google-item-0"),
                SpeechTranslationStreamPart.OutputTextDelta("Hola", "google-item-0"),
                SpeechTranslationStreamPart.SourceTranscriptFinal("Hello", "google-item-0"),
                SpeechTranslationStreamPart.OutputTextFinal("Hola", "google-item-0"),
                SpeechTranslationStreamPart.Finish(
                    sourceText = "Hello",
                    outputText = "Hola",
                    // Only the audio modalities. The TEXT prompt detail is the model's own translation
                    // context, and billing the caller for it would be inventing a charge.
                    usage = SpeechTranslationUsage(inputAudioTokens = 10, outputAudioTokens = 20),
                ),
            ),
            parts,
        )
    }

    @Test
    fun `each turn gets its own id and the texts accumulate across turns`() {
        val mapper = mapper()
        mapper.started()

        val first = mapper.frame(
            """{"serverContent":{"inputTranscription":{"text":"Hello "},""" +
                """"outputTranscription":{"text":"Hola "}}}""",
        ) + mapper.frame("""{"serverContent":{"turnComplete":true}}""")
        val second = mapper.on(GoogleTranslationEvent.AudioEnded) +
            mapper.frame(
                """{"serverContent":{"inputTranscription":{"text":"world"},""" +
                    """"outputTranscription":{"text":"mundo"}}}""",
            ) +
            mapper.frame("""{"serverContent":{"turnComplete":true}}""") +
            mapper.on(GoogleTranslationEvent.GraceElapsed)

        assertEquals(
            listOf("google-item-0", "google-item-0"),
            first.filterIsInstance<SpeechTranslationStreamPart.OutputTextDelta>().map { it.id } +
                first.filterIsInstance<SpeechTranslationStreamPart.OutputTextFinal>().map { it.id },
        )
        assertEquals(
            listOf(SpeechTranslationStreamPart.OutputTextFinal("mundo", "google-item-1")),
            second.filterIsInstance<SpeechTranslationStreamPart.OutputTextFinal>(),
        )
        assertEquals(
            SpeechTranslationStreamPart.Finish(sourceText = "Hello world", outputText = "Hola mundo"),
            second.last(),
        )
    }

    @Test
    fun `a turn that completed while the microphone was open finishes on the end of audio`() {
        val mapper = mapper()
        mapper.started()
        mapper.frame(
            """{"serverContent":{"inputTranscription":{"text":"Hello"},""" +
                """"outputTranscription":{"text":"Hola"}}}""",
        )
        mapper.frame("""{"serverContent":{"turnComplete":true}}""")

        // No further server message arrives; without this the stream would wait for a second
        // `turnComplete` the service has no reason to send.
        mapper.on(GoogleTranslationEvent.AudioEnded)
        val finish = mapper.on(GoogleTranslationEvent.GraceElapsed)

        assertEquals(GoogleTranslationState.Finished, mapper.state)
        assertEquals(
            listOf(SpeechTranslationStreamPart.Finish(sourceText = "Hello", outputText = "Hola")),
            finish,
        )
    }

    @Test
    fun `trailing output silence ends a continuous session, and mid-session silence does not`() {
        val mapper = mapper(finishGraceMs = 400)
        mapper.started()
        mapper.frame("""{"serverContent":{"outputTranscription":{"text":"Hola"}}}""")

        // 4800 samples at 24 kHz is 200 ms; before the microphone stopped it is a pause between
        // sentences, and finishing on it would cut the speaker off at their first breath.
        val duringSpeech = mapper.frame(silenceFrame(samples = 4_800))
        mapper.on(GoogleTranslationEvent.AudioEnded)
        val firstQuiet = mapper.frame(silenceFrame(samples = 4_800))
        val secondQuiet = mapper.frame(silenceFrame(samples = 4_800))

        assertEquals(1, duringSpeech.filterIsInstance<SpeechTranslationStreamPart.Audio>().size)
        assertTrue(firstQuiet.none { it is SpeechTranslationStreamPart.Finish })
        assertEquals(
            SpeechTranslationStreamPart.Finish(sourceText = "", outputText = "Hola"),
            secondQuiet.last(),
        )
    }

    @Test
    fun `speech after the grace period began restarts it rather than truncating the turn`() {
        val mapper = mapper(finishGraceMs = 10_000)
        mapper.started()
        mapper.on(GoogleTranslationEvent.AudioEnded)
        mapper.frame("""{"serverContent":{"outputTranscription":{"text":"Hola"}}}""")
        mapper.frame("""{"serverContent":{"turnComplete":true}}""")
        assertEquals(GoogleTranslationState.Finalizing, mapper.state)

        mapper.frame("""{"serverContent":{"outputTranscription":{"text":" mundo"}}}""")

        assertEquals(GoogleTranslationState.Running, mapper.state)
    }

    /**
     * The failure the grace period is measured from the finish condition, rather than restarted per
     * frame, exists to prevent: Live Translation bills in periodic usage deltas, so a timeout that any
     * arriving frame reset would be held open by traffic that says nothing about the conversation.
     */
    @Test
    fun `a usage delta arriving during the grace period is not turn activity`() {
        val mapper = mapper(finishGraceMs = 10_000)
        mapper.started()
        mapper.on(GoogleTranslationEvent.AudioEnded)
        mapper.frame("""{"serverContent":{"outputTranscription":{"text":"Hola"}}}""")
        mapper.frame("""{"serverContent":{"turnComplete":true}}""")

        val parts = mapper.frame(
            """{"serverContent":{},"usageMetadata":{"promptTokensDetails":""" +
                """[{"modality":"AUDIO","tokenCount":25}]}}""",
        )

        assertEquals(emptyList(), parts)
        assertEquals(GoogleTranslationState.Finalizing, mapper.state)
    }

    @Test
    fun `a recorded Live translation maps to both texts, its audio and the summed audio usage`() {
        val mapper = mapper()
        val parts = mutableListOf<SpeechTranslationStreamPart>()
        parts += mapper.started()
        // The reference's example pushes all of its audio at once, so the end of input precedes every
        // server turn — which is what makes the trailing silence at the end of the recording terminal.
        parts += mapper.on(GoogleTranslationEvent.AudioEnded)
        RECORDED_FRAMES.forEach { parts += mapper.frame(it) }

        assertEquals(
            listOf("AQIDBAUGBwg=", "AAAAAAAAAAAAAAAA"),
            parts.filterIsInstance<SpeechTranslationStreamPart.Audio>().map { encode(it.audio) },
        )
        assertEquals(
            listOf(
                SpeechTranslationStreamPart.SourceTranscriptFinal(
                    "The quick brown fox jumps over the lazy dog.",
                    "google-item-0",
                ),
                SpeechTranslationStreamPart.OutputTextFinal(
                    "El zorro marrón rápido salta sobre el perro perezoso.",
                    "google-item-0",
                ),
                SpeechTranslationStreamPart.Finish(
                    sourceText = "The quick brown fox jumps over the lazy dog.",
                    outputText = "El zorro marrón rápido salta sobre el perro perezoso.",
                    usage = SpeechTranslationUsage(inputAudioTokens = 150, outputAudioTokens = 150),
                ),
            ),
            parts.takeLast(3),
        )
    }

    @Test
    fun `raw chunks are forwarded verbatim when the caller asked for them`() {
        val mapper = mapper(includeRawChunks = true)

        val parts = mapper.started() +
            mapper.frame("""{"serverContent":{"outputTranscription":{"text":"Hola"}}}""") +
            mapper.frame("""{"unknownFrame":{"the service grew a field":1}}""")

        assertEquals(
            listOf(
                """{"setupComplete":{}}""",
                """{"serverContent":{"outputTranscription":{"text":"Hola"}}}""",
                """{"unknownFrame":{"the service grew a field":1}}""",
            ),
            parts.filterIsInstance<SpeechTranslationStreamPart.Raw>().map { it.rawValue.toString() },
        )
    }

    @Test
    fun `a frame that is not JSON is skipped rather than fatal`() {
        val mapper = mapper()
        mapper.started()

        assertEquals(emptyList(), mapper.frame("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun `a server error frame fails the stream with the vendor's own message`() {
        val mapper = mapper()

        val error = assertFailsWith<AiSdkError> {
            mapper.frame("""{"error":{"message":"invalid target language"}}""")
        }

        assertEquals("invalid target language", error.message)
    }

    @Test
    fun `messages after the finish are a no-op`() {
        val mapper = mapper()
        mapper.started()
        mapper.frame("""{"serverContent":{"outputTranscription":{"text":"Hola"}}}""")
        mapper.on(GoogleTranslationEvent.AudioEnded)
        mapper.frame("""{"serverContent":{"turnComplete":true}}""")
        mapper.on(GoogleTranslationEvent.GraceElapsed)

        assertEquals(
            emptyList(),
            mapper.frame("""{"serverContent":{"outputTranscription":{"text":"late"}}}""") +
                mapper.on(GoogleTranslationEvent.GraceElapsed) +
                mapper.onClose(),
        )
    }

    // --- what a socket close means --------------------------------------------------------------

    @Test
    fun `a close while the finish is pending completes the translation`() {
        val mapper = mapper(finishGraceMs = 10_000)
        mapper.started()
        mapper.on(GoogleTranslationEvent.AudioEnded)
        mapper.frame("""{"serverContent":{"outputTranscription":{"text":"Hola"}}}""")
        mapper.frame("""{"serverContent":{"turnComplete":true}}""")

        assertEquals(
            listOf(SpeechTranslationStreamPart.Finish(sourceText = "", outputText = "Hola")),
            mapper.onClose(),
        )
    }

    @Test
    fun `a close before the finish condition is a failure, not a short translation`() {
        val mapper = mapper()
        mapper.started()
        mapper.frame("""{"serverContent":{"outputTranscription":{"text":"Ho"}}}""")

        val error = assertFailsWith<AiSdkError> { mapper.onClose() }

        assertContains(error.message.orEmpty(), "closed unexpectedly before finishing")
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun model(apiKey: String = "test-api-key") = GoogleSpeechTranslationModel(
        modelId = "gemini-3.5-live-translate-preview",
        socket = ProviderSocket(HttpClient(MockEngine { respondOk() })),
        baseUrl = GOOGLE_DEFAULT_BASE_URL,
        apiKey = apiKey,
        extraHeaders = emptyMap(),
    )

    private fun options(
        format: AudioFormat = AudioFormat("audio/pcm", 16_000),
        targetLanguage: String = "es",
        sourceLanguage: String? = null,
        outputFormat: AudioFormat? = null,
        providerOptions: Map<String, JsonObject>? = null,
    ) = SpeechTranslationStreamOptions(
        audio = emptyFlow(),
        inputAudioFormat = format,
        targetLanguage = targetLanguage,
        sourceLanguage = sourceLanguage,
        outputAudioFormat = outputFormat,
        providerOptions = providerOptions,
    )

    @OptIn(ExperimentalEncodingApi::class)
    private fun encode(bytes: ByteArray) = Base64.encode(bytes)

    @OptIn(ExperimentalEncodingApi::class)
    private fun silenceFrame(samples: Int): String {
        val encoded = Base64.encode(ByteArray(samples * 2))
        return """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"$encoded"}}]}}}"""
    }
}

/**
 * The reference's own recording of a live session, minus the `setupComplete` the test sends first.
 *
 * It is wire another team captured from the running service, which is the strongest verification
 * available without a key: it carries the periodic usage deltas, the top-level `sessionResumptionUpdate`
 * this model has no use for, and a session that ends in trailing silence rather than a `turnComplete`.
 */
private val RECORDED_FRAMES = listOf(
    """{"serverContent":{"inputTranscription":{"text":"The quick brown fox","languageCode":"en"}}}""",
    """{"serverContent":{"outputTranscription":{"text":"El zorro marrón","languageCode":"es"}}}""",
    """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000",""" +
        """"data":"AQIDBAUGBwg="}}]}}}""",
    """{"serverContent":{},"usageMetadata":{"promptTokenCount":50,"responseTokenCount":50,""" +
        """"totalTokenCount":100,"promptTokensDetails":[{"modality":"TEXT","tokenCount":535},""" +
        """{"modality":"AUDIO","tokenCount":50}],"responseTokensDetails":""" +
        """[{"modality":"AUDIO","tokenCount":50}]}}""",
    """{"serverContent":{"inputTranscription":{"text":" jumps over the","languageCode":"en"}}}""",
    """{"serverContent":{},"usageMetadata":{"promptTokenCount":25,"responseTokenCount":25,""" +
        """"totalTokenCount":50,"promptTokensDetails":[{"modality":"TEXT","tokenCount":554},""" +
        """{"modality":"AUDIO","tokenCount":25}],"responseTokensDetails":""" +
        """[{"modality":"AUDIO","tokenCount":25}]}}""",
    """{"serverContent":{"outputTranscription":{"text":" rápido salta","languageCode":"es"}}}""",
    """{"serverContent":{"inputTranscription":{"text":" lazy dog.","languageCode":"en"}}}""",
    """{"serverContent":{},"usageMetadata":{"promptTokenCount":25,"responseTokenCount":25,""" +
        """"totalTokenCount":50,"promptTokensDetails":[{"modality":"TEXT","tokenCount":575},""" +
        """{"modality":"AUDIO","tokenCount":25}],"responseTokensDetails":""" +
        """[{"modality":"AUDIO","tokenCount":25}]}}""",
    """{"serverContent":{"outputTranscription":{"text":" sobre el perro perezoso.","languageCode":"es"}}}""",
    """{"serverContent":{},"usageMetadata":{"promptTokenCount":25,"responseTokenCount":25,""" +
        """"totalTokenCount":50,"promptTokensDetails":[{"modality":"TEXT","tokenCount":598},""" +
        """{"modality":"AUDIO","tokenCount":25}],"responseTokensDetails":""" +
        """[{"modality":"AUDIO","tokenCount":25}]}}""",
    """{"sessionResumptionUpdate":{"newHandle":"redacted-fixture-handle","resumable":true}}""",
    """{"serverContent":{},"usageMetadata":{"promptTokenCount":25,"responseTokenCount":25,""" +
        """"totalTokenCount":50,"promptTokensDetails":[{"modality":"TEXT","tokenCount":608},""" +
        """{"modality":"AUDIO","tokenCount":25}],"responseTokensDetails":""" +
        """[{"modality":"AUDIO","tokenCount":25}]}}""",
    """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000",""" +
        """"data":"AAAAAAAAAAAAAAAA"}}]}}}""",
)
