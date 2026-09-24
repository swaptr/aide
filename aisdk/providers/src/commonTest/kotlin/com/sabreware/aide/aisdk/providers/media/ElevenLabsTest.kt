package com.sabreware.aide.aisdk.providers.media

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.providers.elevenlabs.ELEVENLABS_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.elevenlabs.ElevenLabsProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.double
import com.sabreware.aide.aisdk.providers.testing.int
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ElevenLabs speech and batch transcription, ported from the reference's own two test files.
 *
 * The transcription half runs against `__fixtures__/elevenlabs-transcription.json` — a response the
 * reference recorded off the live endpoint. Its `language_code` is `eng`, which is exactly the kind of
 * detail a fixture written from the docs invents as `en`.
 */
class ElevenLabsTest {

    private fun provider(server: TestServer, headers: Map<String, String> = emptyMap()) =
        ElevenLabsProvider(
            client = HttpClient(server.engine()),
            apiKey = "test-api-key",
            headers = headers,
        )

    private fun audioServer() = TestServer(TestServer.bytes(ByteArray(100), "audio/mpeg"))

    private fun transcriptServer() =
        TestServer(TestServer.json(ElevenLabsFixtures.TRANSCRIPTION))

    private fun speech(
        server: TestServer,
        text: String = "Hello, world!",
        build: SpeechCallOptions.() -> SpeechCallOptions = { this },
    ) = SpeechCallOptions(text = text, voice = "test-voice-id").build()

    private fun vendor(build: JsonObjectBuilder.() -> Unit) =
        mapOf(ELEVENLABS_PROVIDER_ID to buildJsonObject(build))

    // --- Speech --------------------------------------------------------------------------------------

    @Test
    fun `the body names the model and the query names the format, always`() = runTest {
        val server = audioServer()

        provider(server).speechModel("eleven_multilingual_v2")
            .doGenerate(speech(server))

        val call = server.request()
        // The voice is the PATH and the model is the BODY — the reverse of OpenAI's shape, so a straight
        // port of that call produces a 404 rather than a validation error naming the field.
        assertEquals("v1/text-to-speech/test-voice-id", call.path)
        call.assertBodyKeys("text", "model_id")
        assertEquals("Hello, world!", call.bodyJson()["text"].string())
        assertEquals("eleven_multilingual_v2", call.bodyJson()["model_id"].string())
        // `mp3` is the spec's own documented name and ElevenLabs has no such enum value; the format
        // parameter names a codec, a sample rate and a bitrate at once.
        assertEquals("mp3_44100_128", call.query["output_format"])
    }

    @Test
    fun `a format the table already spells the vendor's way is forwarded unchanged`() = runTest {
        val server = audioServer()

        provider(server).speechModel("eleven_multilingual_v2")
            .doGenerate(speech(server) { copy(outputFormat = "pcm_44100") })

        assertEquals("pcm_44100", server.request().query["output_format"])
    }

    @Test
    fun `a language reaches the body as language_code`() = runTest {
        val server = audioServer()

        provider(server).speechModel("eleven_multilingual_v2")
            .doGenerate(speech(server, text = "Hola, mundo!") { copy(language = "es") })

        val call = server.request()
        call.assertBodyKeys("text", "model_id", "language_code")
        assertEquals("es", call.bodyJson()["language_code"].string())
        assertEquals("mp3_44100_128", call.query["output_format"])
    }

    @Test
    fun `speed is a voice setting, not a top-level field`() = runTest {
        val server = audioServer()

        provider(server).speechModel("eleven_multilingual_v2")
            .doGenerate(speech(server) { copy(speed = 1.5) })

        val call = server.request()
        // ElevenLabs steers delivery through `voice_settings`; a top-level `speed` is ignored, so the
        // audio comes back at the default rate with no error to explain it.
        call.assertBodyKeys("text", "model_id", "voice_settings")
        assertEquals(1.5, call.bodyJson().obj("voice_settings")?.get("speed").double())
    }

    @Test
    fun `instructions have nowhere to go, and the warning says exactly that`() = runTest {
        val server = audioServer()

        val result = provider(server).speechModel("eleven_multilingual_v2")
            .doGenerate(speech(server) { copy(instructions = "Speak slowly") })

        // The details are the assertion. `toString().contains("instructions")` passes for a
        // Compatibility warning saying something else entirely, which is how four swapped variants
        // stayed green across this module.
        result.warnings.assertUnsupported(
            feature = "instructions",
            details = "ElevenLabs speech models do not support instructions. " +
                "Instructions parameter was ignored.",
        )
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `the vendor's voice settings and seed reach the body under their wire names`() = runTest {
        val server = audioServer()

        provider(server).speechModel("eleven_multilingual_v2").doGenerate(
            speech(server) {
                copy(
                    providerOptions = vendor {
                        put(
                            "voiceSettings",
                            buildJsonObject {
                                put("stability", 0.5)
                                put("similarityBoost", 0.75)
                            },
                        )
                        put("seed", 123)
                    },
                )
            },
        )

        val call = server.request()
        call.assertBodyKeys("text", "model_id", "voice_settings", "seed")
        assertEquals(
            buildJsonObject { put("stability", 0.5); put("similarity_boost", 0.75) },
            call.bodyJson().obj("voice_settings"),
        )
        assertEquals(123, call.bodyJson()["seed"].int())
        assertEquals("mp3_44100_128", call.query["output_format"])
    }

    @Test
    fun `an empty voice_settings is never sent, because it overrides the saved voice`() = runTest {
        val server = audioServer()

        provider(server).speechModel("eleven_multilingual_v2")
            .doGenerate(speech(server) { copy(providerOptions = vendor { put("seed", 1) }) })

        // Sending `{}` replaces the voice's own stability and similarity with nothing, which is audible.
        server.request().assertBodyMissing("voice_settings")
    }

    // --- Batch transcription -------------------------------------------------------------------------

    private fun transcribe() = TranscriptionCallOptions(
        audio = BinaryData.Bytes("AUDIO".encodeToByteArray()),
        mediaType = "audio/wav",
    )

    @Test
    fun `the realtime model refuses a file rather than posting one`() = runTest {
        // The batch endpoint rejects the id outright, so this is not a slower path to the same
        // transcript. Naming the combination beats a vendor 4xx about a model the caller believes in.
        assertFailsWith<UnsupportedFunctionalityError> {
            provider(transcriptServer()).transcriptionModel("scribe_v2_realtime")
                .doGenerate(transcribe())
        }
    }

    @Test
    fun `streaming options handed to the batch endpoint are warned about, not sent`() = runTest {
        val server = transcriptServer()

        val result = provider(server).transcriptionModel("scribe_v1").doGenerate(
            transcribe().copy(
                providerOptions = vendor {
                    put("streaming", buildJsonObject { put("includeTimestamps", true) })
                },
            ),
        )

        result.warnings.assertUnsupported(
            feature = "providerOptions.elevenlabs.streaming",
            details = "ElevenLabs batch transcription does not support streaming options.",
        )
        assertEquals(1, result.warnings.size)
        // Warned about AND absent: a streaming block forwarded as a form field is a 422 on top of the
        // warning.
        assertEquals(null, server.request().multipart["streaming"])
    }

    @Test
    fun `the model is a form field here, and speaker labels are asked for by default`() = runTest {
        val server = transcriptServer()

        provider(server).transcriptionModel("scribe_v1").doGenerate(transcribe())

        val call = server.request()
        assertEquals("v1/speech-to-text", call.path)
        call.assertMultipartField("model_id", "scribe_v1")
        // Per-word timings are worth having for the speaker labels, and ElevenLabs does not bill
        // separately for them.
        call.assertMultipartField("diarize", "true")
        assertEquals(setOf("model_id", "diarize"), call.multipart.keys)
    }

    @Test
    fun `every batch provider option is one form field, spelled the vendor's way`() = runTest {
        val server = transcriptServer()

        provider(server).transcriptionModel("scribe_v1").doGenerate(
            transcribe().copy(
                providerOptions = vendor {
                    put("languageCode", "en")
                    put("fileFormat", "pcm_s16le_16")
                    put("tagAudioEvents", false)
                    put("numSpeakers", 2)
                    put("timestampsGranularity", "character")
                    put("diarize", true)
                },
            ),
        )

        // The whole field map, not a lookup per key: a field that is never read is a field whose absence
        // no assertion can see, which is how an option that silently did nothing stayed green.
        assertEquals(
            mapOf(
                "model_id" to "scribe_v1",
                "diarize" to "true",
                "language_code" to "en",
                "tag_audio_events" to "false",
                "num_speakers" to "2",
                "timestamps_granularity" to "character",
                "file_format" to "pcm_s16le_16",
            ),
            server.request().multipart,
        )
    }

    @Test
    fun `ElevenLabs authenticates with xi-api-key and merges both header layers`() = runTest {
        val server = transcriptServer()

        provider(server, headers = mapOf("Custom-Provider-Header" to "provider-header-value"))
            .transcriptionModel("scribe_v1")
            .doGenerate(
                transcribe().copy(headers = mapOf("Custom-Request-Header" to "request-header-value")),
            )

        val call = server.request()
        call.assertHeader("xi-api-key", "test-api-key")
        call.assertNoHeader("Authorization")
        call.assertHeader("Custom-Provider-Header", "provider-header-value")
        call.assertHeader("Custom-Request-Header", "request-header-value")
        assertEquals(true, call.header("Content-Type")?.startsWith("multipart/form-data"))
    }

    @Test
    fun `the recorded response maps to text, segments, language and duration`() = runTest {
        val server = transcriptServer()

        val result = provider(server).transcriptionModel("scribe_v1").doGenerate(transcribe())

        assertEquals("Hello from the Vercel AI SDK.", result.text)
        // `eng`, not `en`. The vendor's own three-letter code is passed through rather than normalized,
        // and a fixture written from the documentation would have said `en`.
        assertEquals("eng", result.language)
        // There is no duration field on this response: the last word's end is all it says about how
        // long the audio was.
        assertEquals(2.479, result.durationInSeconds)
        assertEquals(11, result.segments.size)
        // Spacing entries are segments too — dropping them makes the timings no longer tile the audio.
        assertEquals(
            listOf(
                TranscriptionResult.Segment("Hello", 0.199, 0.479),
                TranscriptionResult.Segment(" ", 0.479, 0.499),
                TranscriptionResult.Segment("from", 0.5, 0.639),
            ),
            result.segments.take(3),
        )
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `the response body is the untouched payload, keeping what the contract cannot hold`() =
        runTest {
            val server = transcriptServer()

            val result = provider(server).transcriptionModel("scribe_v1").doGenerate(transcribe())

            assertEquals("scribe_v1", result.response.modelId)
            val body = parseJsonObject(assertNotNull(result.response.body))
            // `speaker_id`, `logprob` and the language probability have nowhere to go in the contract;
            // re-serializing a parsed shape would drop all three without saying so.
            assertEquals("dRgXxwt3SzzliHA3nDAQ", body["transcription_id"].string())
            assertEquals(0.8905608654022217, body["language_probability"].double())
        }
}
