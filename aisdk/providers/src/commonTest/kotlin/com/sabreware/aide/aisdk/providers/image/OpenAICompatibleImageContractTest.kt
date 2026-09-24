package com.sabreware.aide.aisdk.providers.image

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleImageModel
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.util.ProviderHttp
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `/images/generations` contract, as the reference's own OpenAI and OpenAI-compatible image tests
 * state it, checked against recorded wire in [OpenAIImageFixtures].
 *
 * This is the contract-level half of the image port: the same endpoint is served by OpenAI, xAI,
 * ByteDance, DeepInfra, Fireworks and a dozen others through one model class, so a mistake here is not
 * one vendor's mistake. The implementation lives in the `openaicompatible` package, which this sweep does
 * not own — the two cases below that name a defect are therefore marked rather than fixed, and are
 * called out in the report.
 */
class OpenAICompatibleImageContractTest {

    private val prompt = "A cute baby sea otter"
    private val url = "https://api.example.com/v1/images/generations"

    private fun TestServer.model(modelId: String = "dall-e-3") = OpenAICompatibleImageModel(
        provider = "openai",
        modelId = modelId,
        http = ProviderHttp(HttpClient(engine())),
        url = url,
        headers = mapOf("Authorization" to "Bearer test-key"),
    )

    private fun options(
        prompt: String? = this.prompt,
        n: Int = 1,
        size: String? = null,
        aspectRatio: String? = null,
        seed: Int? = null,
        provider: JsonObject? = null,
        headers: Map<String, String>? = null,
    ) = ImageCallOptions(
        prompt = prompt,
        n = n,
        size = size,
        aspectRatio = aspectRatio,
        seed = seed,
        providerOptions = provider?.let { mapOf("openai" to it) },
        headers = headers,
    )

    // --- the request ------------------------------------------------------------------------------

    @Test
    fun `the model, prompt, count and size are the whole of an ordinary request`() = runTest {
        val server = TestServer(TestServer.json(OpenAIImageFixtures.TWO_IMAGES))
        server.model().doGenerate(options(n = 2, size = "1024x1024"))

        val body = server.request().bodyJson()
        assertEquals("dall-e-3", body["model"].toString().trim('"'))
        assertEquals(prompt, body["prompt"].toString().trim('"'))
        assertEquals("2", body["n"].toString())
        assertEquals("1024x1024", body["size"].toString().trim('"'))
    }

    @Test
    fun `a provider option is merged into the request beside the fields the spec names`() = runTest {
        val server = TestServer(TestServer.json(OpenAIImageFixtures.TWO_IMAGES))
        server.model().doGenerate(
            options(
                provider = buildJsonObject {
                    put("quality", "high")
                    put("style", "vivid")
                    put("user", "user-123")
                },
            ),
        )

        val body = server.request().bodyJson()
        assertEquals("high", body["quality"].toString().trim('"'))
        assertEquals("vivid", body["style"].toString().trim('"'))
        assertEquals("user-123", body["user"].toString().trim('"'))
    }

    /** A user id is only sent when the caller sets one; a synthesized one is data we invented. */
    @Test
    fun `no user is sent when the caller named none`() = runTest {
        val server = TestServer(TestServer.json(OpenAIImageFixtures.TWO_IMAGES))
        server.model().doGenerate(options())
        server.request().assertBodyMissing("user")
    }

    @Test
    fun `the call's own headers ride alongside the credential`() = runTest {
        val server = TestServer(TestServer.json(OpenAIImageFixtures.TWO_IMAGES))
        server.model().doGenerate(
            options(headers = mapOf("Custom-Request-Header" to "request-header-value")),
        )

        server.request().assertHeader("Authorization", "Bearer test-key")
        server.request().assertHeader("Custom-Request-Header", "request-header-value")
    }

    /**
     * This endpoint takes an explicit `size` and has no field for a ratio. Ignoring one silently
     * produces a correctly generated image of the wrong shape, which is a result nobody inspects.
     */
    @Test
    fun `an aspect ratio and a seed are things this endpoint has no field for, and both say so`() =
        runTest {
            val server = TestServer(TestServer.json(OpenAIImageFixtures.TWO_IMAGES))
            val result = server.model().doGenerate(options(aspectRatio = "16:9", seed = 42))

            result.warnings.assertUnsupported("aspectRatio")
            result.warnings.assertUnsupported("seed")
        }

    @Test
    fun `nothing warns about a request that asked for nothing unsupported`() = runTest {
        val server = TestServer(TestServer.json(OpenAIImageFixtures.TWO_IMAGES))
        server.model().doGenerate(options(size = "1024x1024")).warnings.assertNoWarnings()
    }

    // --- the response -----------------------------------------------------------------------------

    /**
     * The recorded body carries two images and a `revised_prompt` on one of them. Both images must come
     * back, and the base64 must arrive byte-for-byte as OpenAI sent it rather than decoded and re-encoded
     * on the way through.
     */
    @Test
    fun `both recorded images come back with their base64 untouched`() = runTest {
        val server = TestServer(TestServer.json(OpenAIImageFixtures.TWO_IMAGES))
        val result = server.model().doGenerate(options(n = 2))

        assertEquals(2, result.images.size)
        assertEquals(
            OpenAIImageFixtures.FIRST_IMAGE_B64,
            assertIs<BinaryData.Base64>(result.images.first()).value,
        )
    }

    @Test
    fun `the edit endpoint's recorded answer reads the same way`() = runTest {
        val server = TestServer(TestServer.json(OpenAIImageFixtures.EDIT))
        val result = server.model("gpt-image-1").doGenerate(options())

        assertEquals(1, result.images.size)
        assertIs<BinaryData.Base64>(result.images.single())
    }

    /**
     * A URL is NOT base64. Wrapping one as if it were hands every downstream decoder garbage instead of
     * an error — and this is reachable today rather than theoretical, because xAI, DALL-E 2 and DALL-E 3
     * all answer with a URL as their normal shape.
     */
    @Test
    fun `a URL answer is fetched rather than handed on as if it were base64`() = runTest {
        val bytes = "png-bytes".encodeToByteArray()
        val server = TestServer(
            TestServer.json("""{ "data": [{ "url": "https://api.example.com/v1/out.png" }] }"""),
            TestServer.bytes(bytes, "image/png"),
        )
        val result = server.model().doGenerate(options())

        assertTrue(bytes contentEquals assertIs<BinaryData.Bytes>(result.images.single()).value)
        assertEquals("https://api.example.com/v1/out.png", server.request(1).url)
    }

    @Test
    fun `an answer with no images at all is a generation failure, not an empty list`() = runTest {
        val server = TestServer(TestServer.json("""{ "data": [] }"""))
        assertFailsWith<NoContentGeneratedError> { server.model().doGenerate(options()) }
    }

    @Test
    fun `the token counts the vendor reported are carried`() = runTest {
        val server = TestServer(
            TestServer.json(
                """
                {
                  "data": [{ "b64_json": "test1234" }],
                  "usage": { "input_tokens": 12, "output_tokens": 4, "total_tokens": 16 }
                }
                """,
            ),
        )
        val usage = server.model().doGenerate(options()).usage!!

        assertEquals(12, usage.inputTokens)
        assertEquals(4, usage.outputTokens)
    }

    @Test
    fun `a vendor that reports no usage produces no usage, rather than a block of zeroes`() = runTest {
        val server = TestServer(TestServer.json("""{ "data": [{ "b64_json": "test1234" }] }"""))
        assertNull(server.model().doGenerate(options()).usage)
    }

    @Test
    fun `a usage field the vendor sent as null is absent rather than zero`() = runTest {
        val server = TestServer(
            TestServer.json(
                """
                {
                  "data": [{ "b64_json": "test1234" }],
                  "usage": { "input_tokens": 7, "output_tokens": null, "total_tokens": null }
                }
                """,
            ),
        )
        val usage = server.model().doGenerate(options()).usage!!

        assertEquals(7, usage.inputTokens)
        assertNull(usage.outputTokens)
    }

    @Test
    fun `the response names the model that produced it`() = runTest {
        val server = TestServer(TestServer.json(OpenAIImageFixtures.TWO_IMAGES))
        val result = server.model().doGenerate(options())

        assertEquals("dall-e-3", result.response.modelId)
        assertTrue(result.response.headers!!.isNotEmpty())
    }

    @Test
    fun `a rejected request surfaces the vendor's own message`() = runTest {
        val server = TestServer(
            TestServer.json(
                OpenAIImageFixtures.RESPONSE_FORMAT_REJECTED,
                HttpStatusCode.BadRequest,
            ),
        )
        val error = assertFailsWith<APICallError> { server.model("gpt-image-1").doGenerate(options()) }

        assertEquals(400, error.statusCode)
        assertTrue(error.message!!.endsWith("Unknown parameter: 'response_format'."))
    }

    // --- defects in a package this sweep does not own ---------------------------------------------

    /**
     * DEFECT, `openaicompatible` package — reported, not fixed here.
     *
     * The `gpt-image-*` and `chatgpt-image-*` families default to base64 and REJECT `response_format`
     * outright; [OpenAIImageFixtures.RESPONSE_FORMAT_REJECTED] is OpenAI's own recorded answer to a
     * request carrying it. `OpenAICompatibleImageModel` sends `"response_format": "b64_json"`
     * unconditionally, so every generation on those models is a 400 — and the compatible model sends it
     * to arbitrary third-party vendors that have never heard of the parameter either. The reference's
     * own compatible image model does not send it at all, and its OpenAI model gates it on the model id
     * prefix.
     */
    @Test
    fun `response_format is not sent to a model that rejects it`() = runTest {
        val server = TestServer(TestServer.json(OpenAIImageFixtures.EDIT))
        server.model("gpt-image-1").doGenerate(options())
        server.request().assertBodyMissing("response_format")
    }

    /**
     * DEFECT, `openaicompatible` package — reported, not fixed here.
     *
     * `ImageUsage.totalTokens` exists precisely because several vendors bill a third number that is not
     * the sum, so it must be carried rather than derived. The compatible image model reads
     * `input_tokens` and `output_tokens` and drops `total_tokens` on the floor, which leaves the one
     * figure a caller actually gets billed for unreadable.
     */
    @Test
    fun `the total the vendor billed is carried rather than dropped`() = runTest {
        val server = TestServer(
            TestServer.json(
                """
                {
                  "data": [{ "b64_json": "test1234" }],
                  "usage": { "input_tokens": 12, "output_tokens": 4, "total_tokens": 16 }
                }
                """,
            ),
        )
        assertEquals(16, server.model().doGenerate(options()).usage!!.totalTokens)
    }
}
