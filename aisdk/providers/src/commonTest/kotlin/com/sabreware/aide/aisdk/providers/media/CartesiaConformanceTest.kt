package com.sabreware.aide.aisdk.providers.media

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.providers.cartesia.CARTESIA_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.cartesia.CartesiaProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.obj
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Cartesia, ported from the reference's `cartesia-speech-model.test.ts` and the batch half of
 * `cartesia-transcription-model.test.ts`.
 *
 * The output format is a triple whose members contradict each other — mp3 takes a bitrate and no
 * encoding, raw and wav take an encoding and no bitrate — so every format case below compares the whole
 * `output_format` object rather than the member it is nominally about.
 *
 * The reference's `doStream` cases are not ported: this port implements no Cartesia WebSocket, so
 * `doStream` returns null and there is nothing for them to exercise. See the report.
 */
class CartesiaConformanceTest {

    private fun cartesia(server: TestServer, headers: Map<String, String> = emptyMap()) =
        CartesiaProvider(
            client = HttpClient(server.engine()),
            apiKey = "test-api-key",
            headers = headers,
        )

    private fun audioServer() = TestServer(TestServer.bytes(ByteArray(100), "audio/mp3"))

    private fun transcriptServer() = TestServer(TestServer.json(CartesiaFixtures.TRANSCRIPTION))

    private fun vendor(build: JsonObjectBuilder.() -> Unit) =
        mapOf(CARTESIA_PROVIDER_ID to buildJsonObject(build))

    private fun speech() = SpeechCallOptions(text = "Hello, world!", voice = "test-voice-id")

    private fun transcribe() = TranscriptionCallOptions(
        audio = BinaryData.Bytes("AUDIO".encodeToByteArray()),
        mediaType = "audio/wav",
    )

    // --- Speech --------------------------------------------------------------------------------------

    @Test
    fun `the whole request body, for the plainest possible call`() = runTest {
        val server = audioServer()

        cartesia(server).speechModel("sonic-3.5").doGenerate(speech())

        // Compared entire rather than key by key: `bit_rate` was missing here and every per-key
        // assertion about the keys we did send passed. Cartesia calls the input `transcript`, and the
        // voice is an object with a mode rather than a bare id.
        assertEquals(
            buildJsonObject {
                put("model_id", "sonic-3.5")
                put("transcript", "Hello, world!")
                putJsonObject("voice") { put("mode", "id"); put("id", "test-voice-id") }
                putJsonObject("output_format") {
                    put("container", "mp3")
                    put("sample_rate", 44_100)
                    put("bit_rate", 128_000)
                }
            },
            server.request().bodyJson(),
        )
        assertEquals("tts/bytes", server.request().path)
    }

    @Test
    fun `a call with no voice is refused rather than given one we picked`() = runTest {
        val server = audioServer()

        val error = assertFailsWith<InvalidArgumentError> {
            cartesia(server).speechModel("sonic-3.5")
                .doGenerate(speech().copy(voice = null))
        }

        // There is no server-side default, so inventing one bills the caller for a speaker they never
        // chose — and it sounds like the library working.
        assertContains(
            assertNotNull(error.message),
            "Cartesia speech models require a `voice` to be set.",
        )
        assertEquals(0, server.callCount)
    }

    @Test
    fun `wav is an encoding and no bit rate, because the two contradict each other`() = runTest {
        val server = audioServer()

        cartesia(server).speechModel("sonic-3.5")
            .doGenerate(speech().copy(outputFormat = "wav"))

        assertEquals(
            buildJsonObject {
                put("container", "wav")
                put("encoding", "pcm_s16le")
                put("sample_rate", 44_100)
            },
            server.request().bodyJson().obj("output_format"),
        )
    }

    @Test
    fun `a sample rate suffixed onto the format name is parsed rather than swallowed`() = runTest {
        val server = audioServer()

        cartesia(server).speechModel("sonic-3.5")
            .doGenerate(speech().copy(outputFormat = "pcm_24000"))

        // Looking `pcm_24000` up as a key drops the rate silently and returns 44.1 kHz to a caller who
        // asked for 24k — audio that is right in every respect except the one they specified.
        assertEquals(
            buildJsonObject {
                put("container", "raw")
                put("encoding", "pcm_f32le")
                put("sample_rate", 24_000)
            },
            server.request().bodyJson().obj("output_format"),
        )
    }

    @Test
    fun `a rate Cartesia does not serve falls back with a warning naming both`() = runTest {
        val server = audioServer()

        val result = cartesia(server).speechModel("sonic-3.5")
            .doGenerate(speech().copy(outputFormat = "wav_12345"))

        assertEquals("wav", server.request().bodyJson().obj("output_format")?.get("container")
            ?.toString()?.trim('"'))
        assertEquals("44100", server.request().bodyJson().obj("output_format")?.get("sample_rate")
            ?.toString())
        result.warnings.assertUnsupported(
            feature = "outputFormat",
            details = "Unsupported Cartesia sample rate in output format \"wav_12345\". " +
                "Using 44100 Hz instead.",
        )
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `a language reaches the body beside the transcript`() = runTest {
        val server = audioServer()

        cartesia(server).speechModel("sonic-3.5")
            .doGenerate(speech().copy(text = "Hola, mundo!", language = "es"))

        val body = server.request().bodyJson()
        assertEquals("Hola, mundo!", body["transcript"]?.toString()?.trim('"'))
        assertEquals("es", body["language"]?.toString()?.trim('"'))
    }

    @Test
    fun `speed is generation_config, and one outside the range is dropped rather than clamped`() =
        runTest {
            val inRange = audioServer()
            cartesia(inRange).speechModel("sonic-3.5").doGenerate(speech().copy(speed = 1.5))
            assertEquals(
                buildJsonObject { put("speed", 1.5) },
                inRange.request().bodyJson().obj("generation_config"),
            )

            val outOfRange = audioServer()
            val result = cartesia(outOfRange).speechModel("sonic-3.5")
                .doGenerate(speech().copy(speed = 2.0))
            // Cartesia rejects a speed outside its range rather than clamping, so sending it anyway
            // fails the whole call over an option the caller could have done without.
            outOfRange.request().assertBodyMissing("generation_config")
            result.warnings.assertUnsupported(
                feature = "speed",
                details = "Cartesia speed must be between 0.6 and 1.5. The speed option was ignored.",
            )
            assertEquals(1, result.warnings.size)
        }

    @Test
    fun `instructions have nowhere to go on this vendor either`() = runTest {
        val server = audioServer()

        val result = cartesia(server).speechModel("sonic-3.5")
            .doGenerate(speech().copy(instructions = "Speak slowly"))

        result.warnings.assertUnsupported(
            feature = "instructions",
            details = "Cartesia speech models do not support instructions. " +
                "Instructions parameter was ignored.",
        )
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `each member of the format triple can be set directly by the caller`() = runTest {
        val server = audioServer()

        cartesia(server).speechModel("sonic-3.5").doGenerate(
            speech().copy(
                providerOptions = vendor {
                    put("container", "raw")
                    put("encoding", "pcm_s16le")
                    put("sampleRate", 16_000)
                    put("speed", 0.8)
                },
            ),
        )

        val body = server.request().bodyJson()
        assertEquals(
            buildJsonObject {
                put("container", "raw")
                put("encoding", "pcm_s16le")
                put("sample_rate", 16_000)
            },
            body.obj("output_format"),
        )
        assertEquals(buildJsonObject { put("speed", 0.8) }, body.obj("generation_config"))
    }

    @Test
    fun `an encoding for mp3 output is dropped, and the triple stays consistent`() = runTest {
        val server = audioServer()

        val result = cartesia(server).speechModel("sonic-3.5")
            .doGenerate(speech().copy(providerOptions = vendor { put("encoding", "pcm_s16le") }))

        // Not merely absent from the object we send: the whole triple is compared, because an encoding
        // that leaked in beside an mp3 container is a 400 about a parameter the caller thought was fine.
        assertEquals(
            buildJsonObject {
                put("container", "mp3")
                put("sample_rate", 44_100)
                put("bit_rate", 128_000)
            },
            server.request().bodyJson().obj("output_format"),
        )
        result.warnings.assertUnsupported(
            feature = "providerOptions.cartesia.encoding",
            details = "Cartesia MP3 output does not accept an encoding. " +
                "The encoding option was ignored.",
        )
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `the API version is a required header, not an optional one`() = runTest {
        val server = audioServer()

        cartesia(server, headers = mapOf("Custom-Provider-Header" to "provider-header-value"))
            .speechModel("sonic-3.5")
            .doGenerate(
                speech().copy(headers = mapOf("Custom-Request-Header" to "request-header-value")),
            )

        val call = server.request()
        // Omitting it is rejected rather than defaulted, which is a failure that looks nothing like a
        // missing header.
        call.assertHeader("Cartesia-Version", CartesiaProvider.DEFAULT_API_VERSION)
        call.assertHeader("Authorization", "Bearer test-api-key")
        call.assertHeader("Custom-Provider-Header", "provider-header-value")
        call.assertHeader("Custom-Request-Header", "request-header-value")
    }

    @Test
    fun `the audio and the response identity come back on the result`() = runTest {
        val server = TestServer(
            TestServer.bytes(ByteArray(100), "audio/mp3")
                .withHeaders("x-request-id" to "test-request-id"),
        )

        val result = cartesia(server).speechModel("sonic-3.5").doGenerate(speech())

        assertEquals(100, (result.audio as BinaryData.Bytes).value.size)
        assertEquals("sonic-3.5", result.response.modelId)
        assertEquals("audio/mp3", result.response.headers?.get("content-type"))
        assertEquals("test-request-id", result.response.headers?.get("x-request-id"))
        result.warnings.assertNoWarnings()
    }

    // --- Batch transcription -------------------------------------------------------------------------

    @Test
    fun `the model is a form field and the version header rides along`() = runTest {
        val server = transcriptServer()

        cartesia(server, headers = mapOf("Custom-Provider-Header" to "provider-header-value"))
            .transcriptionModel("ink-whisper")
            .doGenerate(
                transcribe().copy(headers = mapOf("Custom-Request-Header" to "request-header-value")),
            )

        val call = server.request()
        assertEquals("stt", call.path)
        call.assertMultipartField("model", "ink-whisper")
        call.assertMultipartFile("file", contentType = "audio/wav")
        call.assertHeader("Authorization", "Bearer test-api-key")
        call.assertHeader("Cartesia-Version", CartesiaProvider.DEFAULT_API_VERSION)
        call.assertHeader("Custom-Provider-Header", "provider-header-value")
        call.assertHeader("Custom-Request-Header", "request-header-value")
    }

    @Test
    fun `the recorded transcript maps to text, language, duration and per-word segments`() =
        runTest {
            val server = transcriptServer()

            val result =
                cartesia(server).transcriptionModel("ink-whisper").doGenerate(transcribe())

            assertEquals("Hello from the Vercel AI SDK.", result.text)
            assertEquals("en", result.language)
            assertEquals(2.479, result.durationInSeconds)
            // Cartesia calls the field `word` where the contract calls it `text`, so a mapper that
            // reads `text` produces the right number of segments with nothing in them.
            assertEquals(
                TranscriptionResult.Segment("Hello", 0.199, 0.479),
                result.segments.first(),
            )
            assertEquals(6, result.segments.size)
            assertEquals("ink-whisper", result.response.modelId)
        }

    @Test
    fun `a granularity list is repeated fields under one bracketed name`() = runTest {
        val server = transcriptServer()

        cartesia(server).transcriptionModel("ink-whisper").doGenerate(
            transcribe().copy(
                providerOptions = vendor {
                    put("language", "en")
                    put("timestampGranularities", buildJsonArray { add("word") })
                },
            ),
        )

        // The brackets are part of the name Cartesia reads, and a comma-joined value is rejected as an
        // unknown granularity rather than expanded.
        assertEquals(
            mapOf(
                "model" to "ink-whisper",
                "language" to "en",
                "timestamp_granularities[]" to "word",
            ),
            server.request().multipart,
        )
    }

    @Test
    fun `streaming options handed to the batch endpoint are warned about, not sent`() = runTest {
        val server = transcriptServer()

        val result = cartesia(server).transcriptionModel("ink-whisper").doGenerate(
            transcribe().copy(
                providerOptions = vendor {
                    put("streaming", buildJsonObject { put("turnDetection", false) })
                },
            ),
        )

        result.warnings.assertUnsupported(
            feature = "providerOptions.cartesia.streaming",
            details = "Cartesia batch transcription does not support streaming options.",
        )
        assertNull(server.request().multipart["streaming"])
    }

    @Test
    fun `the socket-only family refuses a file rather than posting one`() = runTest {
        val server = transcriptServer()

        // `ink-2` has no batch endpoint at all, so this is not a slower path to the same transcript.
        assertFailsWith<UnsupportedFunctionalityError> {
            cartesia(server).transcriptionModel("ink-2").doGenerate(transcribe())
        }
        assertFailsWith<UnsupportedFunctionalityError> {
            cartesia(server).transcriptionModel("ink-2-mini").doGenerate(transcribe())
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `a Cartesia failure keeps the title that says which parameter was wrong`() = runTest {
        val server = TestServer(
            TestServer.error(
                400,
                """{"error_code":"invalid_request","title":"Invalid output format",
                   "message":"mp3 does not accept an encoding.","request_id":"req-1"}""",
            ),
        )

        val error = assertFailsWith<Throwable> {
            cartesia(server).speechModel("sonic-3.5").doGenerate(speech())
        }

        // Cartesia spells an error `{error_code, title, message, request_id}` with no `error` wrapper,
        // so the OpenAI-shaped default finds `message` alone and drops the half that names the field.
        assertContains(
            assertNotNull(error.message),
            "Invalid output format: mp3 does not accept an encoding.",
        )
    }
}
