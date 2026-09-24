package com.sabreware.aide.aisdk.providers.media

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.providers.deepgram.DEEPGRAM_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.deepgram.DeepgramProvider
import com.sabreware.aide.aisdk.providers.elevenlabs.ELEVENLABS_OUTPUT_FORMATS
import com.sabreware.aide.aisdk.providers.elevenlabs.ElevenLabsProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The dedicated audio vendors, whose call shapes differ from OpenAI's in ways that fail confusingly
 * rather than cleanly.
 */
class MediaVendorsTest {

    // --- ElevenLabs -----------------------------------------------------------------------------

    private fun elevenLabs(server: TestServer) = ElevenLabsProvider(
        server.http(),
        "k",
        ElevenLabsProvider.DEFAULT_BASE_URL,
        ElevenLabsProvider.DEFAULT_VOICE_ID,
        emptyMap(),
    )

    private fun audio() = TestServer(TestServer.bytes("AUDIO".encodeToByteArray(), "audio/mpeg"))

    @Test
    fun `ElevenLabs puts the voice in the path and the model in the body`() = runTest {
        val server = audio()

        val result = elevenLabs(server).speechModel("eleven_multilingual_v2")
            .doGenerate(SpeechCallOptions(text = "hello", voice = "voice-42"))

        // The reverse of OpenAI's shape: porting that call straight across yields a 404, not a
        // validation error.
        val call = server.request()
        assertEquals("v1/text-to-speech/voice-42", call.path)
        call.assertBodyKeys("text", "model_id")
        call.assertBodyJson { assertEquals("eleven_multilingual_v2", it["model_id"].string()) }
        assertEquals("AUDIO", (result.audio as BinaryData.Bytes).value.decodeToString())
    }

    @Test
    fun `ElevenLabs authenticates with xi-api-key, not a bearer token`() = runTest {
        val server = audio()

        elevenLabs(server).speechModel("eleven_multilingual_v2")
            .doGenerate(SpeechCallOptions(text = "hi"))

        server.request().assertHeader("xi-api-key", "k")
        server.request().assertNoHeader("Authorization")
    }

    @Test
    fun `a voice is always chosen because ElevenLabs has no server default`() = runTest {
        val server = audio()

        elevenLabs(server).speechModel("eleven_multilingual_v2")
            .doGenerate(SpeechCallOptions(text = "hi"))

        assertEquals("v1/text-to-speech/${ElevenLabsProvider.DEFAULT_VOICE_ID}", server.request().path)
    }

    @Test
    fun `the format is a query parameter naming a codec, a rate and a bitrate at once`() = runTest {
        val server = audio()

        elevenLabs(server).speechModel("eleven_multilingual_v2")
            .doGenerate(SpeechCallOptions(text = "hi", outputFormat = "mp3"))

        // The spec's own documented `"mp3"` is not a member of this API's enum, so sending it through
        // unmapped is a 422 for the single most ordinary format anyone asks for.
        assertEquals("mp3_44100_128", server.request().query["output_format"])
        assertEquals("mp3_44100_128", ELEVENLABS_OUTPUT_FORMATS.getValue("mp3"))
    }

    @Test
    fun `a format the table does not name is forwarded rather than clamped`() = runTest {
        val server = audio()

        elevenLabs(server).speechModel("eleven_multilingual_v2")
            .doGenerate(SpeechCallOptions(text = "hi", outputFormat = "pcm_48000"))

        // The enum has more members than are worth mirroring, and a caller naming one directly means it.
        assertEquals("pcm_48000", server.request().query["output_format"])
    }

    @Test
    fun `instructions ElevenLabs cannot honour are warned about`() = runTest {
        val server = audio()

        val result = elevenLabs(server).speechModel("eleven_multilingual_v2")
            .doGenerate(SpeechCallOptions(text = "hi", instructions = "sound cheerful"))

        // It steers delivery through voice_settings; accepting free text silently would look applied.
        result.warnings.assertUnsupported(
            feature = "instructions",
            details = "ElevenLabs speech models do not support instructions. " +
                "Instructions parameter was ignored.",
        )
    }

    @Test
    fun `ElevenLabs serves audio in both directions and nothing else`() {
        val provider = elevenLabs(audio())

        assertNull(provider.languageModel("anything"))
        assertNull(provider.embeddingModel("anything"))
    }

    @Test
    fun `ElevenLabs transcription names the model in a field and asks for speaker labels`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"text":"hello there","language_code":"en",
                    "words":[{"text":"hello","start":0.0,"end":1.0},
                             {"text":"there","start":1.0,"end":2.5}]}""",
            ),
        )

        val result = elevenLabs(server).transcriptionModel("scribe_v1")
            .doGenerate(TranscriptionCallOptions(BinaryData.Bytes("AUDIO".encodeToByteArray()), "audio/wav"))

        // The mirror of the speech half, where the voice is the path: here the model is a form field.
        val call = server.request()
        assertEquals("v1/speech-to-text", call.path)
        call.assertMultipartField("model_id", "scribe_v1")
        // Speaker labels are what the per-word timings are worth having for, and they are not billed
        // separately.
        call.assertMultipartField("diarize", "true")
        assertEquals("hello there", result.text)
        assertEquals(listOf("hello", "there"), result.segments.map { it.text })
        assertEquals("en", result.language)
        // No duration field exists: the last word's end is all the response says about the length.
        assertEquals(2.5, result.durationInSeconds)
    }

    // --- Deepgram -------------------------------------------------------------------------------

    private val deepgramResponse = """
        {"metadata":{"duration":2.5},
         "results":{"channels":[{"detected_language":"en",
                    "alternatives":[{"transcript":"hello there",
                                     "words":[{"word":"hello","start":0.0,"end":1.0},
                                              {"word":"there","start":1.0,"end":2.5}]}]}]}}
    """.trimIndent()

    private fun deepgram(server: TestServer) =
        DeepgramProvider(HttpClient(server.engine()), apiKey = "k")

    @Test
    fun `Deepgram uploads raw audio with its content type, not multipart`() = runTest {
        val server = TestServer(TestServer.json(deepgramResponse))

        deepgram(server).transcriptionModel("nova-3")
            .doGenerate(TranscriptionCallOptions(BinaryData.Bytes("AUDIO".encodeToByteArray()), "audio/wav"))

        // Sending multipart here does not fail cleanly — it transcribes the MIME envelope as audio.
        val call = server.request()
        assertEquals("AUDIO", call.bodyText)
        call.assertHeader("Authorization", "Token k")
    }

    @Test
    fun `Deepgram sends the model and nothing else a caller did not ask for`() = runTest {
        val server = TestServer(TestServer.json(deepgramResponse))

        val result = deepgram(server).transcriptionModel("nova-3")
            .doGenerate(TranscriptionCallOptions(BinaryData.Bytes(ByteArray(1)), "audio/wav"))

        // `utterances` and `detect_language` used to be pinned on here. Both are billed features, so
        // every caller paid for word-level diarization and language detection whether or not they read
        // either — and no assertion could see it, because the query was never read as a whole.
        assertEquals(mapOf("model" to "nova-3"), server.request().query)
        assertEquals("hello there", result.text)
        // Words, not utterances: utterances exist only when the caller paid for them, and a consumer
        // comparing two vendors' segments has to be comparing the same unit.
        assertEquals(2, result.segments.size)
        assertEquals("hello", result.segments[0].text)
        assertEquals(1.0, result.segments[0].endSecond)
        assertEquals("en", result.language)
        assertEquals(2.5, result.durationInSeconds)
    }

    @Test
    fun `a Deepgram option a caller does set reaches the query under its wire name`() = runTest {
        val server = TestServer(TestServer.json(deepgramResponse))

        deepgram(server).transcriptionModel("nova-3").doGenerate(
            TranscriptionCallOptions(
                audio = BinaryData.Bytes(ByteArray(1)),
                mediaType = "audio/wav",
                providerOptions = mapOf(
                    DEEPGRAM_PROVIDER_ID to buildJsonObject {
                        put("detectLanguage", JsonPrimitive(true))
                        put("diarize", JsonPrimitive(true))
                    },
                ),
            ),
        )

        // Every diarization control lives here: without a call-level provider-options read, the reason
        // anyone chooses Deepgram is unreachable.
        val query = server.request().query
        assertEquals("true", query["detect_language"])
        assertEquals("true", query["diarize"])
        assertFalse("detectLanguage" in query, "the camelCase name must not reach the wire")
    }

    @Test
    fun `Deepgram decodes base64 audio before upload`() = runTest {
        val server = TestServer(TestServer.json(deepgramResponse))

        deepgram(server).transcriptionModel("nova-3")
            .doGenerate(TranscriptionCallOptions(BinaryData.Base64("QVVESU8="), "audio/wav"))

        assertEquals("AUDIO", server.request().bodyText)
    }

    @Test
    fun `Deepgram speech puts everything but the text in the query`() = runTest {
        val server = audio()

        deepgram(server).speechModel("aura-2-thalia-en")
            .doGenerate(SpeechCallOptions(text = "hi", outputFormat = "wav"))

        val call = server.request()
        assertEquals("v1/speak", call.path)
        call.assertBodyKeys("text")
        // The encoding decides whether a container is legal at all, so a name is expanded into both
        // rather than sent as one: `wav` alone names no encoding and is rejected.
        assertEquals(
            mapOf("model" to "aura-2-thalia-en", "encoding" to "linear16", "container" to "wav"),
            call.query,
        )
    }

    @Test
    fun `a bare Deepgram voice family is composed from the voice and language`() = runTest {
        val server = audio()

        deepgram(server).speechModel("aura-2")
            .doGenerate(SpeechCallOptions(text = "hi", voice = "thalia", language = "de"))

        // `aura-2` resolves to nothing at Deepgram: the real id is family-voice-language, and the
        // vendor's 400 names a model the caller never typed.
        assertEquals("aura-2-thalia-de", server.request().query["model"])
    }

    @Test
    fun `a Deepgram voice family with no voice is refused rather than sent`() = runTest {
        val server = audio()

        val error = assertFailsWith<InvalidArgumentError> {
            deepgram(server).speechModel("aura-2").doGenerate(SpeechCallOptions(text = "hi"))
        }
        assertEquals("voice", error.argument)
    }

    @Test
    fun `a bit rate the encoding cannot carry is dropped with a warning, not sent`() = runTest {
        val server = audio()

        val result = deepgram(server).speechModel("aura-2-thalia-en").doGenerate(
            SpeechCallOptions(
                text = "hi",
                outputFormat = "wav",
                providerOptions = mapOf(
                    DEEPGRAM_PROVIDER_ID to buildJsonObject { put("bitRate", JsonPrimitive(48_000)) },
                ),
            ),
        )

        // linear16 is uncompressed: a bit rate alongside it is a 400 naming `bit_rate`, which reads as
        // that value being wrong rather than as the combination being wrong.
        assertFalse("bit_rate" in server.request().query)
        result.warnings.assertUnsupported(
            feature = "providerOptions",
            details = "Encoding \"linear16\" does not support bit_rate parameter. " +
                "Bit rate 48000 was ignored.",
        )
    }

    @Test
    fun `a Deepgram failure carries its own err_msg, not a body excerpt`() = runTest {
        val server = TestServer(
            TestServer.error(
                400,
                """{"err_code":"INVALID_QUERY_PARAMETER","err_msg":"Invalid 'model' value of nope"}""",
            ),
        )

        val error = runCatching {
            deepgram(server).transcriptionModel("nope")
                .doGenerate(TranscriptionCallOptions(BinaryData.Bytes(ByteArray(1)), "audio/wav"))
        }.exceptionOrNull()

        // Deepgram shares no key with anyone else's error shape, so the OpenAI-shaped default finds
        // nothing and leaves the caller a raw excerpt for a one-line, actionable complaint.
        assertTrue(
            error?.message?.contains("Invalid 'model' value of nope") == true,
            error?.message.orEmpty(),
        )
    }
}
