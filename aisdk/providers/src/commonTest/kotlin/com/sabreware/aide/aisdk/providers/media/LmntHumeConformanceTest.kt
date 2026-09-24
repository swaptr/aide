package com.sabreware.aide.aisdk.providers.media

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.providers.hume.HUME_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.hume.HumeProvider
import com.sabreware.aide.aisdk.providers.lmnt.LMNT_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.lmnt.LmntProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * LMNT and Hume, ported from `lmnt-speech-model.test.ts` and `hume-speech-model.test.ts`.
 *
 * Hume is where the audit found a warning that was simply false — `speed` reported as unsupported when
 * Hume takes a rate per utterance — pinned by a test that asserted a warning existed without asserting
 * what it said. Every warning assertion here is against the variant and its details.
 */
class LmntHumeConformanceTest {

    private fun audioServer(format: String = "mp3") =
        TestServer(TestServer.bytes(ByteArray(100), "audio/$format"))

    private fun speech() = SpeechCallOptions(text = "Hello from the AI SDK!")

    // --- LMNT ----------------------------------------------------------------------------------------

    private fun lmnt(server: TestServer, headers: Map<String, String> = emptyMap()) = LmntProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-api-key",
        headers = headers,
    )

    @Test
    fun `the model and text travel in the body, with LMNT's own default voice`() = runTest {
        val server = audioServer()

        val result = lmnt(server).speechModel("aurora").doGenerate(speech())

        val call = server.request()
        assertEquals("v1/ai/speech/bytes", call.path)
        call.assertBodyKeys("model", "text", "voice", "response_format")
        assertEquals("aurora", call.bodyJson()["model"]?.toString()?.trim('"'))
        assertEquals(LmntProvider.DEFAULT_VOICE, call.bodyJson()["voice"]?.toString()?.trim('"'))
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `LMNT authenticates with x-api-key and merges both header layers`() = runTest {
        val server = audioServer()

        lmnt(server, headers = mapOf("Custom-Provider-Header" to "provider-header-value"))
            .speechModel("aurora")
            .doGenerate(
                speech().copy(headers = mapOf("Custom-Request-Header" to "request-header-value")),
            )

        val call = server.request()
        call.assertHeader("x-api-key", "test-api-key")
        call.assertNoHeader("Authorization")
        call.assertHeader("Content-Type", "application/json")
        call.assertHeader("Custom-Provider-Header", "provider-header-value")
        call.assertHeader("Custom-Request-Header", "request-header-value")
    }

    @Test
    fun `voice, speed and format each reach the body under LMNT's own name`() = runTest {
        val server = audioServer()

        val result = lmnt(server).speechModel("aurora").doGenerate(
            speech().copy(voice = "nova", outputFormat = "mp3", speed = 1.5),
        )

        // `response_format`, not `format` — the whole body is compared because the one key that is
        // spelled differently is the one an assertion about the keys we agree on cannot see.
        assertEquals(
            buildJsonObject {
                put("model", "aurora")
                put("text", "Hello from the AI SDK!")
                put("voice", "nova")
                put("response_format", "mp3")
                put("speed", 1.5)
            },
            server.request().bodyJson(),
        )
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `every format LMNT serves passes through and returns its audio`() = runTest {
        for (format in listOf("aac", "mp3", "mulaw", "raw", "wav")) {
            val server = audioServer(format)

            val result = lmnt(server).speechModel("aurora")
                .doGenerate(speech().copy(outputFormat = format))

            assertEquals(
                format,
                server.request().bodyJson()["response_format"]?.toString()?.trim('"'),
            )
            assertEquals(100, (result.audio as BinaryData.Bytes).value.size)
            result.warnings.assertNoWarnings()
        }
    }

    @Test
    fun `the vendor's own speed outranks the generic one`() = runTest {
        val server = audioServer()

        lmnt(server).speechModel("aurora").doGenerate(
            speech().copy(
                speed = 1.5,
                providerOptions = mapOf(LMNT_PROVIDER_ID to buildJsonObject { put("speed", 0.8) }),
            ),
        )

        // The reference's rule for every option both layers name: the vendor-namespaced one wins,
        // because a caller who reached for it did so to say something the generic option could not.
        assertEquals("0.8", server.request().bodyJson()["speed"]?.toString())
    }

    @Test
    fun `the response carries the model and the audio's own content type`() = runTest {
        val server = TestServer(
            TestServer.bytes(ByteArray(100), "audio/mp3").withHeaders(
                "x-request-id" to "test-request-id",
                "x-ratelimit-remaining" to "123",
            ),
        )

        val result = lmnt(server).speechModel("aurora").doGenerate(speech())

        assertEquals("aurora", result.response.modelId)
        assertEquals("audio/mp3", result.response.headers?.get("content-type"))
        assertEquals("test-request-id", result.response.headers?.get("x-request-id"))
        assertEquals("123", result.response.headers?.get("x-ratelimit-remaining"))
    }

    // --- Hume ----------------------------------------------------------------------------------------

    private fun hume(server: TestServer, headers: Map<String, String> = emptyMap()) = HumeProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-api-key",
        headers = headers,
    )

    @Test
    fun `the text is wrapped in an utterance whose voice names its library`() = runTest {
        val server = audioServer()

        val result = hume(server).speechModel("").doGenerate(speech())

        val call = server.request()
        assertEquals("v0/tts/file", call.path)
        assertEquals(
            listOf(
                buildJsonObject {
                    put("text", "Hello from the AI SDK!")
                    put(
                        "voice",
                        buildJsonObject {
                            put("id", HumeProvider.DEFAULT_VOICE_ID)
                            // Omitting the provider resolves the id against the CALLER's custom voices,
                            // so a built-in id comes back as not found.
                            put("provider", "HUME_AI")
                        },
                    )
                },
            ),
            call.bodyJson().arr("utterances"),
        )
        assertEquals(
            buildJsonObject { put("type", "mp3") },
            call.bodyJson()["format"] as JsonObject,
        )
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `Hume authenticates with its own key header and merges both header layers`() = runTest {
        val server = audioServer()

        hume(server, headers = mapOf("Custom-Provider-Header" to "provider-header-value"))
            .speechModel("")
            .doGenerate(
                speech().copy(headers = mapOf("Custom-Request-Header" to "request-header-value")),
            )

        val call = server.request()
        call.assertHeader("X-Hume-Api-Key", "test-api-key")
        call.assertNoHeader("Authorization")
        call.assertHeader("Content-Type", "application/json")
        call.assertHeader("Custom-Provider-Header", "provider-header-value")
        call.assertHeader("Custom-Request-Header", "request-header-value")
    }

    @Test
    fun `speed is a per-utterance rate Hume actually has, so it warns about nothing`() = runTest {
        val server = audioServer()

        val result = hume(server).speechModel("").doGenerate(
            speech().copy(voice = "test-voice", outputFormat = "mp3", speed = 1.5),
        )

        assertEquals(
            listOf(
                buildJsonObject {
                    put("text", "Hello from the AI SDK!")
                    put("speed", 1.5)
                    put(
                        "voice",
                        buildJsonObject { put("id", "test-voice"); put("provider", "HUME_AI") },
                    )
                },
            ),
            server.request().bodyJson().arr("utterances"),
        )
        // The audit found a `speed` warning here that was simply false, kept green by a test that
        // asserted a warning existed without asserting what it said.
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `every container Hume returns passes through as a format object`() = runTest {
        for (format in listOf("mp3", "pcm", "wav")) {
            val server = audioServer(format)

            val result = hume(server).speechModel("")
                .doGenerate(speech().copy(outputFormat = format))

            // An object with a `type`, not a bare string: Hume rejects the string form.
            assertEquals(
                buildJsonObject { put("type", format) },
                server.request().bodyJson()["format"] as JsonObject,
            )
            assertEquals(100, (result.audio as BinaryData.Bytes).value.size)
            result.warnings.assertNoWarnings()
        }
    }

    @Test
    fun `Hume is the one vendor here with somewhere to put delivery instructions`() = runTest {
        val server = audioServer()

        val result = hume(server).speechModel("")
            .doGenerate(speech().copy(instructions = "Speak cheerfully"))

        val utterance = server.request().bodyJson().arr("utterances")?.first() as JsonObject
        assertEquals("Speak cheerfully", utterance["description"]?.toString()?.trim('"'))
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `a prior generation is continued by id alone, never merged with utterances`() = runTest {
        val server = audioServer()

        hume(server).speechModel("").doGenerate(
            speech().copy(
                providerOptions = mapOf(
                    HUME_PROVIDER_ID to buildJsonObject {
                        put(
                            "context",
                            buildJsonObject {
                                put("generationId", "gen-1")
                                put("utterances", kotlinx.serialization.json.buildJsonArray { })
                            },
                        )
                    },
                ),
            ),
        )

        // The union is one-or-the-other on the wire: a `context` carrying both is rejected, so the
        // generation id short-circuits rather than merging.
        assertEquals(
            buildJsonObject { put("generation_id", "gen-1") },
            server.request().bodyJson()["context"] as JsonObject,
        )
    }

    @Test
    fun `the response carries the audio's own content type and the vendor's request id`() = runTest {
        val server = TestServer(
            TestServer.bytes(ByteArray(100), "audio/mp3").withHeaders(
                "x-request-id" to "test-request-id",
                "x-ratelimit-remaining" to "123",
            ),
        )

        val result = hume(server).speechModel("").doGenerate(speech())

        assertEquals("", result.response.modelId)
        assertEquals("audio/mp3", result.response.headers?.get("content-type"))
        assertEquals("test-request-id", result.response.headers?.get("x-request-id"))
    }
}
