package com.sabreware.aide.aisdk.providers.openaicompatible

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TooManyEmbeddingValuesForCallError
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The non-chat modalities over the OpenAI-compatible wire.
 *
 * These exercise the modality contracts from the spec, and each test targets a way the endpoint can be
 * quietly wrong rather than loudly broken — an embedding matched to the wrong input, an image URL that
 * expires after it is stored, a ratio the server ignores.
 */
class ModalitiesTest {

    private var lastRequest: HttpRequestData? = null

    private fun http(body: String, contentType: String = "application/json") = ProviderHttp(
        HttpClient(
            MockEngine { request ->
                lastRequest = request
                respond(content = body, headers = headersOf(HttpHeaders.ContentType, contentType))
            },
        ),
    )

    private fun sentBody() =
        parseJsonObject((lastRequest!!.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())

    // --- embeddings -----------------------------------------------------------------------------

    @Test
    fun `embeddings are returned in the caller's input order`() = runTest {
        // Deliberately out of order: servers do not promise ordering, and an embedding matched to the
        // wrong input is worse than an error because nothing detects it.
        val response = """
            {"data":[{"index":1,"embedding":[0.3,0.4]},{"index":0,"embedding":[0.1,0.2]}],
             "usage":{"prompt_tokens":7}}
        """.trimIndent()
        val model = OpenAICompatibleEmbeddingModel("openai", "text-embedding-3-small", http(response), "https://x/v1/embeddings")

        val result = model.doEmbed(EmbeddingCallOptions(listOf("first", "second")))

        assertEquals(listOf(listOf(0.1, 0.2), listOf(0.3, 0.4)), result.embeddings)
        assertEquals(7, result.usage)
    }

    @Test
    fun `too many values fails with the ceiling named`() = runTest {
        val model = OpenAICompatibleEmbeddingModel(
            "openai", "text-embedding-3-small", http("{}"), "https://x/v1/embeddings", maxPerCall = 2,
        )

        val error = assertFailsWith<TooManyEmbeddingValuesForCallError> {
            model.doEmbed(EmbeddingCallOptions(listOf("a", "b", "c")))
        }
        // A 400 about token counts would not tell the caller the actual limit.
        assertTrue(error.message!!.contains("at most 2"), error.message!!)
    }

    // --- images ---------------------------------------------------------------------------------

    @Test
    fun `images are requested as bytes, not as a URL that will expire`() = runTest {
        // dall-e-3, not gpt-image-1: the gpt-image family returns base64 already and REJECTS the
        // parameter, so the model that needs asking is the one that can be asked.
        val model = OpenAICompatibleImageModel(
            "openai", "dall-e-3", http("""{"data":[{"b64_json":"aGk="}]}"""), "https://x/v1/images/generations",
        )

        val result = model.doGenerate(ImageCallOptions(prompt = "a cat", size = "1024x1024"))

        // A stored URL becomes a broken image later rather than an error now.
        assertEquals("b64_json", sentBody()["response_format"]?.jsonPrimitive?.content)
        assertEquals(BinaryData.Base64("aGk="), result.images.single())
    }

    @Test
    fun `an aspect ratio the wire cannot carry is warned about, not ignored`() = runTest {
        val model = OpenAICompatibleImageModel(
            "openai", "gpt-image-1", http("""{"data":[{"b64_json":"aGk="}]}"""), "https://x/v1/images/generations",
        )

        val result = model.doGenerate(ImageCallOptions(prompt = "a cat", aspectRatio = "16:9"))

        // Silently dropping it yields a correctly-generated image of the wrong shape.
        result.warnings.assertUnsupported("aspectRatio", "This wire takes an explicit size instead.")
    }

    @Test
    fun `an empty image response is an error, not an empty list`() = runTest {
        val model = OpenAICompatibleImageModel("openai", "gpt-image-1", http("""{"data":[]}"""), "https://x/v1/images/generations")

        assertFailsWith<NoContentGeneratedError> { model.doGenerate(ImageCallOptions(prompt = "x")) }
    }

    // --- speech ---------------------------------------------------------------------------------

    @Test
    fun `speech returns raw audio bytes and always names a voice`() = runTest {
        val model = OpenAICompatibleSpeechModel("openai", "gpt-4o-mini-tts", http("AUDIO", "audio/mpeg"), "https://x/v1/audio/speech")

        val result = model.doGenerate(SpeechCallOptions(text = "hello"))

        assertEquals("AUDIO", (result.audio as BinaryData.Bytes).value.decodeToString())
        // A request without a voice is a 400; OpenAI documents no default.
        assertEquals("alloy", sentBody()["voice"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an empty audio response is an error, not a zero-byte file`() = runTest {
        val model = OpenAICompatibleSpeechModel("openai", "gpt-4o-mini-tts", http("", "audio/mpeg"), "https://x/v1/audio/speech")

        assertFailsWith<NoContentGeneratedError> { model.doGenerate(SpeechCallOptions(text = "x")) }
    }

    // --- transcription ---------------------------------------------------------------------------

    /** The wire Whisper actually returns: the language is a NAME, not a code. */
    private val verboseJson = """
        {"text":"hello there","language":"english","duration":1.5,
         "segments":[{"start":0.0,"end":0.8,"text":"hello"},{"start":0.8,"end":1.5,"text":"there"}]}
    """.trimIndent()

    @Test
    fun `transcription returns segments, language and duration, not just text`() = runTest {
        val model = OpenAICompatibleTranscriptionModel("openai", "whisper-1", http(verboseJson), "https://x/v1/audio/transcriptions")

        val result = model.doGenerate(
            TranscriptionCallOptions(BinaryData.Bytes("AUDIO".encodeToByteArray()), "audio/mpeg"),
        )

        // The default response_format returns text alone and drops these; a caller cannot ask for them
        // back afterwards, so verbose_json is requested up front.
        assertEquals("hello there", result.text)
        // The wire says "english" and the contract documents ISO-639-1. Passing it through left every
        // caller switching on a language code taking the wrong branch — and the old fixture invented
        // an `"en"` no OpenAI endpoint has ever sent, so the test only ever agreed with itself.
        assertEquals("en", result.language)
        assertEquals(1.5, result.durationInSeconds)
        assertEquals(2, result.segments.size)
        assertEquals(0.8, result.segments[0].endSecond)
    }

    @Test
    fun `the upload is multipart and its filename carries the format`() = runTest {
        val model = OpenAICompatibleTranscriptionModel("openai", "whisper-1", http(verboseJson), "https://x/v1/audio/transcriptions")

        model.doGenerate(TranscriptionCallOptions(BinaryData.Bytes("AUDIO".encodeToByteArray()), "audio/mpeg"))

        val contentType = lastRequest!!.body.contentType.toString()
        assertTrue(contentType.startsWith("multipart/form-data"), contentType)
        // Several servers infer the format from the extension and reject a name without one, which
        // presents as "unsupported file format" for a file that is perfectly supported.
        val sent = (lastRequest!!.body as OutgoingContent.WriteChannelContent).toBytes().decodeToString()
        assertTrue(sent.contains("audio.mpeg"), "no extension in the upload filename")
        assertTrue(sent.contains("verbose_json"), "response_format was not requested")
    }

    @Test
    fun `a response with words and no segments still carries a timeline`() = runTest {
        // The reference's own recorded gpt-4o-transcribe fixture: `words`, no `segments`, and a language
        // NAME. Reading only `segments` returned an empty timeline against the newest endpoint.
        val words = """
            {"task":"transcribe","language":"english","duration":1.2,"text":"Galileo was",
             "words":[{"word":"Galileo","start":0.0,"end":0.66},{"word":"was","start":0.66,"end":0.9}]}
        """.trimIndent()
        val model = OpenAICompatibleTranscriptionModel(
            "openai", "gpt-4o-transcribe", http(words), "https://x/v1/audio/transcriptions",
        )

        val result = model.doGenerate(
            TranscriptionCallOptions(BinaryData.Bytes("AUDIO".encodeToByteArray()), "audio/mpeg"),
        )

        assertEquals(listOf("Galileo", "was"), result.segments.map { it.text })
        assertEquals(0.66, result.segments[0].endSecond)
        assertEquals("en", result.language)
        // gpt-4o-transcribe REJECTS verbose_json, so asking for it 400s the model this fixture came from.
        val sent = (lastRequest!!.body as OutgoingContent.WriteChannelContent).toBytes().decodeToString()
        assertTrue(!sent.contains("verbose_json"), "verbose_json was requested from a model that rejects it")
    }

    @Test
    fun `base64 audio is decoded before upload, not sent as text`() = runTest {
        val model = OpenAICompatibleTranscriptionModel("openai", "whisper-1", http(verboseJson), "https://x/v1/audio/transcriptions")

        // Uploading the base64 TEXT produces a confident transcription of noise.
        model.doGenerate(TranscriptionCallOptions(BinaryData.Base64("QVVESU8="), "audio/wav"))

        val sent = (lastRequest!!.body as OutgoingContent.WriteChannelContent).toBytes().decodeToString()
        assertTrue(sent.contains("AUDIO"), "the payload was not decoded before upload")
        assertTrue(!sent.contains("QVVESU8="), "the base64 text was uploaded verbatim")
    }

    // --- provider wiring -------------------------------------------------------------------------

    @Test
    fun `a provider offers a modality only when it was declared`() {
        fun provider(modalities: Set<OpenAICompatibleModality>) = OpenAICompatibleProvider(
            client = HttpClient(MockEngine { respond(content = "{}") }),
            providerId = "openai",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "k",
            modalities = modalities,
        )

        val full = provider(OpenAICompatibleModality.entries.toSet())
        assertEquals("gpt-image-1", full.imageModel("gpt-image-1")?.modelId)
        assertEquals("tts-1", full.speechModel("tts-1")?.modelId)
        assertEquals("text-embedding-3-small", full.embeddingModel("text-embedding-3-small")?.modelId)
        assertEquals("whisper-1", full.transcriptionModel("whisper-1")?.modelId)

        // Chat-only is the default, and null means "this server has no such endpoint" — an answer the
        // caller can act on, rather than a model that 404s the first time it is used.
        val chatOnly = provider(setOf(OpenAICompatibleModality.Chat))
        assertNull(chatOnly.imageModel("gpt-image-1"))
        assertNull(chatOnly.speechModel("tts-1"))
        assertNull(chatOnly.embeddingModel("text-embedding-3-small"))
        assertNull(chatOnly.transcriptionModel("whisper-1"))
    }

    /** Renders a streamed multipart body so its parts can be asserted on. */
    private suspend fun OutgoingContent.WriteChannelContent.toBytes(): ByteArray {
        val channel = ByteChannel(autoFlush = true)
        writeTo(channel)
        channel.close()
        return channel.readRemaining().readByteArray()
    }

    @Test
    fun `the OpenAI image dialect warns on input files instead of silently generating from scratch`() = runTest {
        val model = OpenAICompatibleImageModel(
            "openai", "dall-e-3", http("""{"data":[{"b64_json":"aGk="}]}"""), "https://x/v1/images/generations",
        )

        val result = model.doGenerate(
            ImageCallOptions(prompt = "edit this", files = listOf(ImageFile.Url("https://img/cat.png"))),
        )

        // Silence here was an edit call that quietly ran as text-to-image.
        result.warnings.assertUnsupported("files", "/images/generations takes no input images; an edit endpoint would.")
    }

    @Test
    fun `the ByteDance dialect maps its options, carries input images, and pins b64_json`() = runTest {
        val model = OpenAICompatibleImageModel(
            "bytedance", "seedream-4", http("""{"data":[{"b64_json":"aGk="}]}"""),
            "https://ark/api/v3/images/generations",
            dialect = ImageRequestDialect.ByteDance,
        )

        model.doGenerate(
            ImageCallOptions(
                prompt = "a cat",
                files = listOf(ImageFile.Url("https://img/in.png")),
                providerOptions = mapOf(
                    "bytedance" to buildJsonObject {
                        put("sequentialImageGeneration", "auto")
                        put("maxImages", 3)
                        put("optimizePromptMode", "standard")
                        put("watermark", false)
                    },
                ),
            ),
        )

        val body = sentBody()
        assertTrue("n" !in body, body.toString())
        assertEquals("https://img/in.png", body["image"]!!.jsonPrimitive.content)
        assertEquals("auto", body["sequential_image_generation"]!!.jsonPrimitive.content)
        assertEquals(
            3,
            body["sequential_image_generation_options"]!!.jsonObject["max_images"]!!.jsonPrimitive.content.toInt(),
        )
        assertEquals(
            "standard",
            body["optimize_prompt_options"]!!.jsonObject["mode"]!!.jsonPrimitive.content,
        )
        assertEquals("b64_json", body["response_format"]!!.jsonPrimitive.content)
        // The camelCase spellings must not leak beside their translations.
        listOf("sequentialImageGeneration", "maxImages", "optimizePromptMode").forEach {
            assertTrue(it !in body, body.toString())
        }
    }

    // --- ai@7.0.102 -----------------------------------------------------------------------------

    private val diarizedJson = OpenAICompatibleTranscriptionFixtures.DIARIZED

    private val transcriptionsUrl = "https://x/v1/audio/transcriptions"

    private fun audio() = TranscriptionCallOptions(BinaryData.Bytes("AUDIO".encodeToByteArray()), "audio/mpeg")

    /** One multipart field's value, off the raw upload. */
    private suspend fun sentField(name: String): String? {
        val raw = (lastRequest!!.body as OutgoingContent.WriteChannelContent).toBytes().decodeToString()
        val at = raw.indexOf("name=\"$name\"").takeIf { it >= 0 } ?: return null
        val start = raw.indexOf("\r\n\r\n", at) + 4
        return raw.substring(start, raw.indexOf("\r\n--", start))
    }

    @Test
    fun `the diarization model asks for its own format and auto chunking, and reports who spoke`() = runTest {
        val model = OpenAICompatibleTranscriptionModel("openai", "gpt-4o-transcribe-diarize", http(diarizedJson), transcriptionsUrl)

        val result = model.doGenerate(audio())

        assertEquals("diarized_json", sentField("response_format"))
        assertEquals("auto", sentField("chunking_strategy"))
        assertEquals("Hello from Alice. Hello from Bob.", result.text)
        assertEquals(3.2, result.durationInSeconds)
        assertEquals(listOf("Hello from Alice.", "Hello from Bob."), result.segments.map { it.text })
        assertEquals(listOf(0.0 to 1.5, 1.5 to 3.2), result.segments.map { it.startSecond to it.endSecond })
        // The speaker has no field in the contract's segment, so it travels under the vendor.
        val spoken = result.providerMetadata!!.getValue("openai").getValue("segments").jsonArray.map { it.jsonObject }
        assertEquals(listOf("A", "B"), spoken.map { it["speaker"]?.jsonPrimitive?.content })
        assertEquals(listOf("Hello from Alice.", "Hello from Bob."), spoken.map { it["text"]?.jsonPrimitive?.content })
        assertEquals(listOf(0.0, 1.5), spoken.map { it["startSecond"]?.jsonPrimitive?.content?.toDouble() })
        assertEquals(listOf(1.5, 3.2), spoken.map { it["endSecond"]?.jsonPrimitive?.content?.toDouble() })
    }

    @Test
    fun `a caller's response format and chunking strategy win over the diarization defaults`() = runTest {
        val model = OpenAICompatibleTranscriptionModel("openai", "gpt-4o-transcribe-diarize", http(verboseJson), transcriptionsUrl)

        model.doGenerate(
            audio().copy(
                providerOptions = mapOf(
                    "openai" to buildJsonObject {
                        put("responseFormat", "json")
                        put("chunkingStrategy", "auto")
                    },
                ),
            ),
        )

        assertEquals("json", sentField("response_format"))
        assertEquals("auto", sentField("chunking_strategy"))
        // Translated, not spread: the camelCase spellings never reach the form.
        assertNull(sentField("responseFormat"))
        assertNull(sentField("chunkingStrategy"))
    }

    @Test
    fun `a server VAD chunking strategy goes out as the JSON text OpenAI documents`() = runTest {
        val model = OpenAICompatibleTranscriptionModel("openai", "gpt-4o-transcribe-diarize", http(diarizedJson), transcriptionsUrl)

        model.doGenerate(
            audio().copy(
                providerOptions = mapOf(
                    "openai" to buildJsonObject {
                        put(
                            "chunkingStrategy",
                            buildJsonObject {
                                put("type", "server_vad")
                                put("threshold", 0.7)
                                put("prefixPaddingMs", 400)
                                put("silenceDurationMs", 300)
                            },
                        )
                    },
                ),
            ),
        )

        assertEquals(
            """{"type":"server_vad","threshold":0.7,"prefix_padding_ms":400,"silence_duration_ms":300}""",
            sentField("chunking_strategy"),
        )
    }

    @Test
    fun `whisper still gets verbose_json and no chunking strategy, and speakerless segments file nothing`() = runTest {
        val model = OpenAICompatibleTranscriptionModel("openai", "whisper-1", http(verboseJson), transcriptionsUrl)

        val result = model.doGenerate(audio())

        assertEquals("verbose_json", sentField("response_format"))
        assertNull(sentField("chunking_strategy"))
        assertNull(result.providerMetadata)
    }

    @Test
    fun `GPT Image 2 point 5 takes the xhigh and max qualities, on the body OpenAI documents`() = runTest {
        // The reference's table (`8487955`): both Flare and Sunburst, both new levels, exact body.
        for (modelId in listOf("gpt-image-2.5-flare", "gpt-image-2.5-sunburst")) {
            for (quality in listOf("xhigh", "max")) {
                val model = OpenAICompatibleImageModel(
                    "openai", modelId, http("""{"data":[{"b64_json":"aGk="}]}"""), "https://x/v1/images/generations",
                )

                model.doGenerate(
                    ImageCallOptions(
                        prompt = "a cat",
                        n = 1,
                        size = "1024x1024",
                        providerOptions = mapOf("openai" to buildJsonObject { put("quality", quality) }),
                    ),
                )

                val body = sentBody()
                assertEquals(setOf("model", "prompt", "n", "size", "quality"), body.keys, "$modelId $quality")
                assertEquals(modelId, body["model"]?.jsonPrimitive?.content)
                assertEquals(quality, body["quality"]?.jsonPrimitive?.content)
            }
        }
    }
}
