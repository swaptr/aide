package com.sabreware.aide.aisdk.providers.media

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.bool
import com.sabreware.aide.aisdk.providers.testing.double
import com.sabreware.aide.aisdk.providers.testing.int
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.providers.xai.XAI_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.xai.XaiProvider
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/** xAI's `/tts` and `/stt`, both read from the vendored reference. */
@OptIn(ExperimentalEncodingApi::class)
class XaiAudioTest {

    private fun xai(server: TestServer) =
        XaiProvider(server.http(), "k", XaiProvider.DEFAULT_BASE_URL, emptyMap())

    private fun audio() = TestServer(TestServer.bytes("AUDIO".encodeToByteArray(), "audio/mpeg"))

    // --- Speech ---------------------------------------------------------------------------------

    @Test
    fun `xAI names a voice and a language even when the caller sets neither`() = runTest {
        val server = audio()

        val result = xai(server).speechModel("").doGenerate(SpeechCallOptions(text = "hi"))

        // xAI has no server-side default voice and no detect-by-omission: leaving either off is a 400,
        // so the defaults are `eve` and its own `auto` sentinel rather than an absent field.
        val call = server.request()
        call.assertBodyKeys("text", "voice_id", "language", "output_format")
        call.assertBodyJson {
            assertEquals("hi", it["text"].string())
            assertEquals("eve", it["voice_id"].string())
            assertEquals("auto", it["language"].string())
            assertEquals("mp3", it.obj("output_format")!!["codec"].string())
        }
        assertEquals("v1/tts", call.path)
        call.assertHeader("Authorization", "Bearer k")
        assertEquals("AUDIO", (result.audio as BinaryData.Bytes).value.decodeToString())
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `the format is a codec inside an object, with the rate and bit rate beside it`() = runTest {
        val server = audio()

        val result = xai(server).speechModel("").doGenerate(
            SpeechCallOptions(
                text = "hi",
                voice = "ara",
                outputFormat = "mp3",
                language = "en",
                speed = 1.2,
                providerOptions = mapOf(
                    XAI_PROVIDER_ID to buildJsonObject {
                        put("sampleRate", JsonPrimitive(44100))
                        put("bitRate", JsonPrimitive(192000))
                        put("optimizeStreamingLatency", JsonPrimitive(1))
                        put("textNormalization", JsonPrimitive(true))
                    },
                ),
            ),
        )

        // A bare `"mp3"` where xAI wants `{codec: ...}` is a 400 on every call that names a format.
        val call = server.request()
        call.assertBodyKeys(
            "text",
            "voice_id",
            "language",
            "output_format",
            "speed",
            "optimize_streaming_latency",
            "text_normalization",
        )
        call.assertBodyJson {
            assertEquals("ara", it["voice_id"].string())
            assertEquals("en", it["language"].string())
            assertEquals(1.2, it["speed"].double())
            assertEquals(1, it["optimize_streaming_latency"].int())
            assertEquals(true, it["text_normalization"].bool())
            val format = it.obj("output_format")!!
            assertEquals("mp3", format["codec"].string())
            assertEquals(44100, format["sample_rate"].int())
            assertEquals(192000, format["bit_rate"].int())
        }
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `a bit rate the codec cannot carry is dropped with a warning, not sent`() = runTest {
        val server = audio()

        val result = xai(server).speechModel("").doGenerate(
            SpeechCallOptions(
                text = "hi",
                outputFormat = "wav",
                providerOptions = mapOf(
                    XAI_PROVIDER_ID to buildJsonObject { put("bitRate", JsonPrimitive(192000)) },
                ),
            ),
        )

        // xAI rejects `bit_rate` outright on the PCM-shaped codecs, so sending it turns a container
        // change into a 400 on a request that looks unrelated to it.
        server.request().assertBodyJson {
            val format = it.obj("output_format")!!
            assertEquals("wav", format["codec"].string())
            assertNull(format["bit_rate"])
        }
        result.warnings.assertUnsupported(
            feature = "providerOptions",
            details = "xAI `bitRate` is supported only for mp3 output. It was ignored.",
        )
    }

    @Test
    fun `a codec xAI does not serve falls back to mp3 with a warning`() = runTest {
        val server = audio()

        val result = xai(server).speechModel("")
            .doGenerate(SpeechCallOptions(text = "hi", outputFormat = "flac"))

        server.request().assertBodyJson {
            assertEquals("mp3", it.obj("output_format")!!["codec"].string())
        }
        result.warnings.assertUnsupported(
            feature = "outputFormat",
            details = "Unsupported output format: flac. Using mp3 instead.",
        )
    }

    @Test
    fun `instructions have nowhere to go, so they are warned about rather than dropped silently`() =
        runTest {
            val server = audio()

            val result = xai(server).speechModel("")
                .doGenerate(SpeechCallOptions(text = "hi", instructions = "Speak cheerfully"))

            server.request().assertBodyMissing("instructions")
            result.warnings.assertUnsupported(
                feature = "instructions",
                details = "xAI speech models do not support the `instructions` option. " +
                    "Use xAI speech tags in `text` to control delivery.",
            )
        }

    @Test
    fun `with_timestamps answers with a JSON envelope, not audio bytes`() = runTest {
        val envelope = """
            {"audio":"${Base64.encode("AUDIO".encodeToByteArray())}","content_type":"audio/mpeg",
             "duration":1.19,
             "audio_timestamps":{"graph_chars":["H","i"],"graph_times":[[0.04,0.06],[0.06,0.1]]}}
        """.trimIndent()
        val server = TestServer(
            TestServer.json(envelope).withHeaders("x-trace-id" to "993675dc"),
        )

        val result = xai(server).speechModel("").doGenerate(
            SpeechCallOptions(
                text = "Hi",
                providerOptions = mapOf(
                    XAI_PROVIDER_ID to buildJsonObject { put("withTimestamps", JsonPrimitive(true)) },
                ),
            ),
        )

        // The same endpoint answers with raw audio or with this envelope depending on one flag, so a
        // reader that always takes the bytes hands the caller a JSON document as an mp3: silence.
        server.request().assertBodyJson { assertEquals(true, it["with_timestamps"].bool()) }
        assertEquals("AUDIO", (result.audio as BinaryData.Bytes).value.decodeToString())
        val metadata = result.providerMetadata?.get(XAI_PROVIDER_ID)
        assertEquals("993675dc", metadata?.get("traceId").string())
        assertEquals(1.19, metadata?.get("duration").double())
        assertEquals("audio/mpeg", metadata?.get("contentType").string())
        assertEquals(
            listOf("H", "i"),
            metadata?.obj("audioTimestamps")?.get("graphChars")?.jsonArray?.map { it.string() },
        )
    }

    @Test
    fun `the trace id is namespaced off a binary response too, because a ticket is answered by it`() =
        runTest {
            val server = TestServer(
                TestServer.bytes("AUDIO".encodeToByteArray(), "audio/mpeg")
                    .withHeaders("x-trace-id" to "06e3dab5"),
            )

            val result = xai(server).speechModel("").doGenerate(SpeechCallOptions(text = "hi"))

            assertEquals(
                "06e3dab5",
                result.providerMetadata?.get(XAI_PROVIDER_ID)?.get("traceId").string(),
            )
        }

    // --- Transcription --------------------------------------------------------------------------

    private val transcript = """
        {"text":"Hello from the AI SDK!","language":"en","duration":2.5,
         "words":[{"text":"Hello","start":0.0,"end":0.5},{"text":"from","start":0.5,"end":0.8}]}
    """.trimIndent()

    @Test
    fun `xAI uploads the audio and returns one segment per word`() = runTest {
        val server = TestServer(TestServer.json(transcript))

        val result = xai(server).transcriptionModel("").doGenerate(
            TranscriptionCallOptions(BinaryData.Bytes("AUDIO".encodeToByteArray()), "audio/wav"),
        )

        val call = server.request()
        assertEquals("v1/stt", call.path)
        assertTrue("""name="file"""" in call.bodyText, "the audio must be the `file` part")
        assertEquals("Hello from the AI SDK!", result.text)
        // Words are the only granularity `/stt` offers, so segments are words here by the vendor's
        // choice rather than by ours.
        assertEquals(2, result.segments.size)
        assertEquals("Hello", result.segments[0].text)
        assertEquals(0.5, result.segments[0].endSecond)
        assertEquals("en", result.language)
        assertEquals(2.5, result.durationInSeconds)
    }

    @Test
    fun `every option is a form field, and the file is appended after all of them`() = runTest {
        val server = TestServer(TestServer.json(transcript))

        xai(server).transcriptionModel("").doGenerate(
            TranscriptionCallOptions(
                audio = BinaryData.Bytes("AUDIO".encodeToByteArray()),
                mediaType = "audio/pcm",
                providerOptions = mapOf(
                    XAI_PROVIDER_ID to buildJsonObject {
                        put("audioFormat", JsonPrimitive("pcm"))
                        put("sampleRate", JsonPrimitive(16000))
                        put("language", JsonPrimitive("en"))
                        put("format", JsonPrimitive(true))
                        put("diarize", JsonPrimitive(true))
                        put("fillerWords", JsonPrimitive(true))
                        put("keyterm", buildJsonArray { add(JsonPrimitive("AI SDK")); add(JsonPrimitive("Grok")) })
                    },
                ),
            ),
        )

        val call = server.request()
        // Numbers and booleans are strings on a form; sending them as JSON literals is a 422.
        call.assertMultipartField("audio_format", "pcm")
        call.assertMultipartField("sample_rate", "16000")
        call.assertMultipartField("language", "en")
        call.assertMultipartField("format", "true")
        call.assertMultipartField("diarize", "true")
        call.assertMultipartField("filler_words", "true")
        // A repeated name is how xAI reads a list; one field holding a JSON array biases the transcript
        // toward the literal text `["AI SDK","Grok"]`.
        assertEquals(2, Regex("""name="keyterm"""").findAll(call.bodyText).count())
        // The settings apply to the upload as the parts stream past, so a `file` that arrives first is
        // transcribed with every one of them ignored — a success with the wrong result, not an error.
        assertTrue(
            call.bodyText.indexOf("""name="file"""") > call.bodyText.indexOf("""name="language""""),
            "the file part must be appended after every option field",
        )
    }

    @Test
    fun `multichannel without a channel count is warned about, because xAI silently uses one`() =
        runTest {
            val server = TestServer(TestServer.json(transcript))

            val result = xai(server).transcriptionModel("").doGenerate(
                TranscriptionCallOptions(
                    audio = BinaryData.Bytes(ByteArray(1)),
                    mediaType = "audio/wav",
                    providerOptions = mapOf(
                        XAI_PROVIDER_ID to buildJsonObject {
                            put("multichannel", JsonPrimitive(true))
                        },
                    ),
                ),
            )

            // Not a rejected request: xAI transcribes the first channel and says nothing, so a stereo
            // interview comes back with one speaker simply missing.
            assertEquals(1, result.warnings.size, result.warnings.toString())
            assertTrue("channels" in result.warnings.single().toString())
        }

    @Test
    fun `an empty language is absent rather than reported as a language`() = runTest {
        val server = TestServer(TestServer.json("""{"text":"hi","language":""}"""))

        val result = xai(server).transcriptionModel("").doGenerate(
            TranscriptionCallOptions(BinaryData.Bytes(ByteArray(1)), "audio/wav"),
        )

        assertNull(result.language)
        assertNull(result.durationInSeconds)
        assertEquals(emptyList(), result.segments)
    }
}
