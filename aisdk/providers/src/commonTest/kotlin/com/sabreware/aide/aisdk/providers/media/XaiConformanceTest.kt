package com.sabreware.aide.aisdk.providers.media

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.providers.xai.XAI_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.xai.XaiProvider
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
 * The xAI cases the reference pins by comparing a WHOLE object, ported from `xai-speech-model.test.ts`
 * and the batch half of `xai-transcription-model.test.ts`.
 *
 * The reference's `doStream` cases are not ported: this port implements no xAI transcription socket, so
 * `doStream` returns null and there is nothing for them to exercise. See the report.
 */
class XaiConformanceTest {

    private fun xai(server: TestServer, headers: Map<String, String> = emptyMap()) = XaiProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-api-key",
        headers = headers,
    )

    private fun audioServer() = TestServer(TestServer.bytes(byteArrayOf(1, 2, 3, 4), "audio/mpeg"))

    /** The reference's recorded `with_timestamps` envelope: four bytes of audio, base64, plus timings. */
    private val timestampsEnvelope = """
        {"audio":"AQIDBA==","content_type":"audio/mpeg","duration":1.19,
         "audio_timestamps":{"graph_chars":["H","i"],"graph_times":[[0.04,0.06],[0.06,0.1]]}}
    """.trimIndent()

    private fun vendor(build: JsonObjectBuilder.() -> Unit) =
        mapOf(XAI_PROVIDER_ID to buildJsonObject(build))

    private fun speech() = SpeechCallOptions(text = "Hello from the AI SDK!")

    // --- Speech --------------------------------------------------------------------------------------

    @Test
    fun `the whole body for a call that sets nothing but the text`() = runTest {
        val server = audioServer()

        xai(server).speechModel("").doGenerate(speech())

        // Two fields the caller did not set are still sent: xAI has no server-side default voice, so
        // omitting one is a 400 rather than a house voice, and `auto` is its own detect sentinel — an
        // absent `language` would mean something different from asking it to detect.
        assertEquals(
            buildJsonObject {
                put("text", "Hello from the AI SDK!")
                put("voice_id", "eve")
                put("language", "auto")
                putJsonObject("output_format") { put("codec", "mp3") }
            },
            server.request().bodyJson(),
        )
        assertEquals("POST", server.request().method)
        assertEquals("v1/tts", server.request().path)
    }

    @Test
    fun `the whole body when every standard option is set`() = runTest {
        val server = audioServer()

        xai(server).speechModel("").doGenerate(
            speech().copy(voice = "ara", language = "en", outputFormat = "wav", speed = 1.2),
        )

        assertEquals(
            buildJsonObject {
                put("text", "Hello from the AI SDK!")
                put("voice_id", "ara")
                put("language", "en")
                putJsonObject("output_format") { put("codec", "wav") }
                put("speed", 1.2)
            },
            server.request().bodyJson(),
        )
    }

    @Test
    fun `every codec xAI serves reaches the format object`() = runTest {
        for (codec in listOf("mp3", "wav", "pcm", "mulaw", "alaw")) {
            val server = audioServer()

            val result = xai(server).speechModel("")
                .doGenerate(speech().copy(outputFormat = codec))

            assertEquals(
                buildJsonObject { put("codec", codec) },
                server.request().bodyJson()["output_format"],
            )
            result.warnings.assertNoWarnings()
        }
    }

    @Test
    fun `the rate and bit rate sit inside the format object, the rest beside it`() = runTest {
        val server = audioServer()

        xai(server).speechModel("").doGenerate(
            speech().copy(
                providerOptions = vendor {
                    put("sampleRate", 44_100)
                    put("bitRate", 192_000)
                    put("optimizeStreamingLatency", 1)
                    put("textNormalization", true)
                },
            ),
        )

        val body = server.request().bodyJson()
        assertEquals(
            buildJsonObject {
                put("codec", "mp3")
                put("sample_rate", 44_100)
                put("bit_rate", 192_000)
            },
            body["output_format"],
        )
        assertEquals("1", body["optimize_streaming_latency"]?.toString())
        assertEquals("true", body["text_normalization"]?.toString())
    }

    @Test
    fun `with_timestamps and a pronunciation table both reach the body`() = runTest {
        val server = TestServer(TestServer.json(timestampsEnvelope))

        xai(server).speechModel("").doGenerate(
            speech().copy(
                providerOptions = vendor {
                    put("withTimestamps", true)
                    put("replace", buildJsonObject { put("nginx", "/ˈɛndʒɪn ˈɛks/") })
                },
            ),
        )

        val body = server.request().bodyJson()
        assertEquals("true", body["with_timestamps"]?.toString())
        // A caller-authored map, so its keys are cargo and travel untouched.
        assertEquals(
            buildJsonObject { put("nginx", "/ˈɛndʒɪn ˈɛks/") },
            body["replace"],
        )
    }

    @Test
    fun `the timestamps envelope is decoded, and every part of it is namespaced`() = runTest {
        val server = TestServer(
            TestServer.json(timestampsEnvelope)
                .withHeaders("x-trace-id" to "993675dc-8ea6-4f54-b4ad-a59ac2615026"),
        )

        val result = xai(server).speechModel("")
            .doGenerate(SpeechCallOptions(text = "Hi", providerOptions = vendor {
                put("withTimestamps", true)
            }))

        // Reading bytes unconditionally hands the caller a JSON document as if it were an mp3, which
        // plays as silence rather than failing.
        assertEquals(
            byteArrayOf(1, 2, 3, 4).toList(),
            (result.audio as BinaryData.Bytes).value.toList(),
        )
        assertEquals(
            buildJsonObject {
                put("traceId", "993675dc-8ea6-4f54-b4ad-a59ac2615026")
                put("duration", 1.19)
                put("contentType", "audio/mpeg")
                putJsonObject("audioTimestamps") {
                    put("graphChars", buildJsonArray { add("H"); add("i") })
                    put(
                        "graphTimes",
                        buildJsonArray {
                            add(buildJsonArray { add(0.04); add(0.06) })
                            add(buildJsonArray { add(0.06); add(0.1) })
                        },
                    )
                }
            },
            result.providerMetadata?.get(XAI_PROVIDER_ID),
        )
    }

    @Test
    fun `the trace id is namespaced off a binary response too, and is empty when absent`() =
        runTest {
            val withTrace = TestServer(
                TestServer.bytes(byteArrayOf(1, 2, 3, 4), "audio/mpeg")
                    .withHeaders("x-trace-id" to "06e3dab5-e3ba-4c6b-83a6-1e9ea11d78af"),
            )
            val traced = xai(withTrace).speechModel("").doGenerate(speech())
            // A support ticket is answered against this id, and a binary response has no body to carry
            // it, so leaving it in the raw headers means every caller has to know to look.
            assertEquals(
                buildJsonObject { put("traceId", "06e3dab5-e3ba-4c6b-83a6-1e9ea11d78af") },
                traced.providerMetadata?.get(XAI_PROVIDER_ID),
            )

            val plain = audioServer()
            val untraced = xai(plain).speechModel("").doGenerate(speech())
            assertEquals(
                buildJsonObject { },
                untraced.providerMetadata?.get(XAI_PROVIDER_ID),
            )
            untraced.warnings.assertNoWarnings()
        }

    @Test
    fun `xAI authenticates as a bearer and merges both header layers`() = runTest {
        val server = audioServer()

        xai(server, headers = mapOf("Custom-Provider-Header" to "provider-header-value"))
            .speechModel("")
            .doGenerate(
                speech().copy(headers = mapOf("Custom-Request-Header" to "request-header-value")),
            )

        val call = server.request()
        call.assertHeader("Authorization", "Bearer test-api-key")
        call.assertHeader("Content-Type", "application/json")
        call.assertHeader("Custom-Provider-Header", "provider-header-value")
        call.assertHeader("Custom-Request-Header", "request-header-value")
    }

    @Test
    fun `an xAI failure carries the vendor's own message and status`() = runTest {
        val server = TestServer(
            TestServer.error(
                400,
                """{"error":{"message":"Invalid text","type":"invalid_request_error"}}""",
            ),
        )

        val error = assertFailsWith<APICallError> {
            xai(server).speechModel("").doGenerate(speech())
        }

        assertContains(assertNotNull(error.message), "Invalid text")
        assertEquals(400, error.statusCode)
    }

    // --- Batch transcription -------------------------------------------------------------------------

    @Test
    fun `the audio is a file part whose name carries the container`() = runTest {
        val server = TestServer(TestServer.json("""{"text":"Hello from the AI SDK!"}"""))

        xai(server).transcriptionModel("").doGenerate(
            TranscriptionCallOptions(
                audio = BinaryData.Bytes("RIFF....WAVEfmt ".encodeToByteArray()),
                mediaType = "audio/wav",
            ),
        )

        val call = server.request()
        assertEquals("v1/stt", call.path)
        // The extension is what several servers key their decoder off, so it is derived from the bytes
        // rather than from the caller's declared type, which can be absent or wrong.
        call.assertMultipartFile("file", fileName = "audio.wav", contentType = "audio/wav")
    }

    @Test
    fun `a response with no words, no duration and an empty language reports none of them`() =
        runTest {
            val server = TestServer(
                TestServer.json("""{"text":"Hello from the AI SDK!","language":""}"""),
            )

            val result = xai(server).transcriptionModel("").doGenerate(
                TranscriptionCallOptions(BinaryData.Bytes(byteArrayOf(1)), "audio/wav"),
            )

            assertEquals("Hello from the AI SDK!", result.text)
            // An empty string is not a language. Reporting it as one makes a `when` over language codes
            // take a branch for a value the vendor meant as "we do not know".
            assertNull(result.language)
            assertNull(result.durationInSeconds)
            assertEquals(emptyList(), result.segments)
            result.warnings.assertNoWarnings()
        }
}
