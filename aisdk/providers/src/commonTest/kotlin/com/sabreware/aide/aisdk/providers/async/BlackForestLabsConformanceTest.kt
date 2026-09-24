package com.sabreware.aide.aisdk.providers.async

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.providers.blackforestlabs.BlackForestLabsProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.util.JobFailedError
import com.sabreware.aide.aisdk.util.JobTimeoutError
import com.sabreware.aide.aisdk.util.PollPolicy
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Black Forest Labs (FLUX), against the reference's own `black-forest-labs-image-model.test.ts`.
 *
 * BFL is the vendor where the interesting behaviour is all in the plumbing between three calls — submit,
 * poll, download — and every one of the three has a way to go wrong that returns a plausible answer: the
 * poll URL is on a different host, the poll is routed by query parameter, and the delivery URL is a CDN
 * the API key must not reach.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlackForestLabsConformanceTest {

    private val prompt = "A cute baby sea otter"
    private val modelId = "test-model"
    private val baseUrl = "https://api.example.com/v1"
    private val imageBytes = "test-binary-content".encodeToByteArray()

    private fun TestScope.provider(
        server: TestServer,
        baseUrl: String = this@BlackForestLabsConformanceTest.baseUrl,
    ) = BlackForestLabsProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-key",
        baseUrl = baseUrl,
        pollPolicy = PollPolicy.Fast,
        elapsedMillis = { testScheduler.currentTime },
    )

    /** Submit, then one poll that is already Ready, then the picture. */
    private fun server(
        submit: String = BflFixtures.submitted(),
        vararg polls: String,
    ): TestServer = TestServer(
        TestServer.json(submit),
        *(polls.takeIf { it.isNotEmpty() } ?: arrayOf(BflFixtures.READY))
            .map { TestServer.json(it) }.toTypedArray(),
        TestServer.bytes(imageBytes, "image/png"),
    )

    private fun options(
        prompt: String? = this.prompt,
        size: String? = null,
        aspectRatio: String? = null,
        seed: Int? = null,
        files: List<ImageFile>? = null,
        mask: ImageFile? = null,
        bfl: JsonObject? = null,
        headers: Map<String, String>? = null,
    ) = ImageCallOptions(
        prompt = prompt,
        n = 1,
        size = size,
        aspectRatio = aspectRatio,
        seed = seed,
        files = files,
        mask = mask,
        providerOptions = bfl?.let { mapOf("blackForestLabs" to it) },
        headers = headers,
    )

    // --- the request ------------------------------------------------------------------------------

    @Test
    fun `a camelCase option is renamed and an option BFL has no field for never reaches it`() =
        runTest {
            val server = server()
            val result = provider(server).imageModel(modelId).doGenerate(
                options(
                    aspectRatio = "16:9",
                    bfl = buildJsonObject {
                        put("promptUpsampling", true)
                        put("unsupportedProperty", "value")
                    },
                ),
            )

            // BFL's submit schema is closed: an unexpected key is a 422 naming the field, not an option
            // it politely ignores. That is why this one is dropped rather than passed through the way
            // fal's and Replicate's are.
            server.request().assertBodyEquals(
                """{ "prompt": "$prompt", "aspect_ratio": "16:9", "prompt_upsampling": true }""",
            )
            result.warnings.assertUnsupported("unsupportedProperty")
        }

    /** The one model that spells the input `image`; every other one spells it `input_image`. */
    @Test
    fun `flux-pro-1_0-fill takes its source under image, and everything else under input_image`() =
        runTest {
            val fill = server()
            provider(fill).imageModel("flux-pro-1.0-fill").doGenerate(
                options(
                    aspectRatio = "1:1",
                    files = listOf(
                        ImageFile.Data(BinaryData.Bytes("test-image".encodeToByteArray()), "image/png"),
                    ),
                    mask = ImageFile.Data(
                        BinaryData.Bytes("test-mask".encodeToByteArray()),
                        "image/png",
                    ),
                ),
            )
            fill.request().assertBodyEquals(
                """
                {
                  "prompt": "$prompt",
                  "aspect_ratio": "1:1",
                  "image": "dGVzdC1pbWFnZQ==",
                  "mask": "dGVzdC1tYXNr"
                }
                """,
            )

            val other = server()
            provider(other).imageModel(modelId).doGenerate(
                options(
                    aspectRatio = "1:1",
                    files = listOf(
                        ImageFile.Data(BinaryData.Bytes("test-image".encodeToByteArray()), "image/png"),
                    ),
                ),
            )
            other.request().assertBodyEquals(
                """
                {
                  "prompt": "$prompt",
                  "aspect_ratio": "1:1",
                  "input_image": "dGVzdC1pbWFnZQ=="
                }
                """,
            )
        }

    /**
     * BARE base64, with no `data:` prefix. The prefix is a 422 naming the image field, which reads as
     * "this picture is unusable" rather than "this envelope is".
     */
    @Test
    fun `an input image is bare base64, never a data URI`() = runTest {
        val server = server()
        provider(server).imageModel(modelId).doGenerate(
            options(files = listOf(ImageFile.Data(BinaryData.Base64("QUJD"), "image/png"))),
        )
        assertEquals(
            "QUJD",
            server.request().bodyJson()["input_image"].toString().trim('"'),
        )
    }

    @Test
    fun `an eleventh input image is refused rather than truncated`() = runTest {
        val server = server()
        assertFailsWith<InvalidArgumentError> {
            provider(server).imageModel(modelId).doGenerate(
                options(files = List(11) { ImageFile.Url("https://example.com/$it.png") }),
            )
        }
    }

    @Test
    fun `a size with no aspect ratio derives one, and says it did`() = runTest {
        val server = server()
        val result = provider(server).imageModel(modelId).doGenerate(options(size = "1024x1024"))

        result.warnings.assertUnsupported(
            "size",
            "Deriving aspect_ratio from size. Use the width and height provider options to specify " +
                "dimensions for models that support them.",
        )
        server.request().assertBodyEquals(
            """
            { "prompt": "$prompt", "aspect_ratio": "1:1", "width": 1024, "height": 1024 }
            """,
        )
    }

    @Test
    fun `a size beside an aspect ratio is ignored, and says so differently`() = runTest {
        val server = server()
        val result = provider(server).imageModel(modelId)
            .doGenerate(options(size = "1920x1080", aspectRatio = "16:9"))

        result.warnings.assertUnsupported(
            "size",
            "Black Forest Labs ignores size when aspectRatio is provided. Use the width and height " +
                "provider options to specify dimensions for models that support them",
        )
    }

    @Test
    fun `nothing warns when nothing was substituted`() = runTest {
        val server = server()
        provider(server).imageModel(modelId).doGenerate(options(aspectRatio = "1:1")).warnings
            .assertNoWarnings()
    }

    // --- the three calls --------------------------------------------------------------------------

    /**
     * Submit, poll, download — and the poll carries `?id=`. BFL routes a poll by query parameter, not by
     * path, so a poll without it answers about no job at all.
     */
    @Test
    fun `the three calls go out in order, and the poll names the job by query parameter`() = runTest {
        val server = server()
        provider(server).imageModel(modelId).doGenerate(options(aspectRatio = "16:9"))

        assertEquals("POST", server.request(0).method)
        assertEquals("$baseUrl/$modelId", server.request(0).url)
        assertEquals("GET", server.request(1).method)
        assertEquals("https://api.example.com/poll?id=req-123", server.request(1).url)
        assertEquals("GET", server.request(2).method)
        assertEquals("https://api.example.com/image.png", server.request(2).url)
    }

    /** `x-key` — not a bearer, and not `api-key`. Getting it wrong reads as an invalid credential. */
    @Test
    fun `the key is the x-key header, and it is carried onto the poll as well as the submit`() =
        runTest {
            val server = server()
            provider(server).imageModel(modelId).doGenerate(
                options(headers = mapOf("Custom-Request-Header" to "request-header-value")),
            )

            server.request(0).assertHeader("x-key", "test-key")
            server.request(0).assertHeader("Custom-Request-Header", "request-header-value")
            server.request(1).assertHeader("x-key", "test-key")
            server.request(1).assertHeader("Custom-Request-Header", "request-header-value")
        }

    /**
     * The delivery URL is usually a CDN on somebody else's host. A vendor response body is not a reason
     * to hand our API key to whatever host it happens to name.
     */
    @Test
    fun `the key does not follow the result URL onto a foreign origin`() = runTest {
        val server = TestServer(
            TestServer.json(BflFixtures.submitted()),
            TestServer.json(
                """{ "status": "Ready", "result": { "sample": "https://cdn.evil.example/image.png" } }""",
            ),
            TestServer.bytes(imageBytes, "image/png"),
        )
        provider(server).imageModel(modelId).doGenerate(options())

        assertEquals("https://cdn.evil.example/image.png", server.request(2).url)
        server.request(2).assertNoHeader("x-key")
    }

    /**
     * Strict same-origin is wrong here, and this is the one vendor where that is not a shortcut: BFL
     * answers a submit on `api.bfl.ai` with a polling URL on whichever cluster picked the job up. A
     * same-origin check drops the key on every poll and every job 401s.
     */
    @Test
    fun `the key does follow the poll onto a sibling bfl_ai cluster host`() = runTest {
        val server = TestServer(
            TestServer.json(
                """
                {
                  "id": "req-123",
                  "polling_url": "https://api.us1.bfl.ai/v1/get_result"
                }
                """,
            ),
            TestServer.json(
                """
                {
                  "status": "Ready",
                  "result": { "sample": "https://delivery-us1.bfl.ai/image.png" }
                }
                """,
            ),
            TestServer.bytes(imageBytes, "image/png"),
        )
        provider(server, baseUrl = "https://api.bfl.ai/v1").imageModel(modelId).doGenerate(options())

        assertTrue(server.request(1).url.startsWith("https://api.us1.bfl.ai/v1/get_result"))
        server.request(1).assertHeader("x-key", "test-key")
    }

    /**
     * The poll URL is RESPONSE DATA on a deliberately different host, so the domain check standing
     * between it and the API key is the only thing that stops a compromised or spoofed response from
     * naming a host of its choosing.
     *
     * `https://api.bfl.ai:x@evil.example/...` is the crafted form: everything before the `@` is
     * userinfo, so the real host is `evil.example`. A parser that strips the port before the userinfo
     * reads it as `api.bfl.ai`, trusts it, and sends the key — which is what this provider's own host
     * parse did until it was replaced by the shared one.
     */
    @Test
    fun `the key does not follow a poll URL that hides a foreign host in its userinfo`() = runTest {
        val server = TestServer(
            TestServer.json(
                """
                {
                  "id": "req-123",
                  "polling_url": "https://api.bfl.ai:x@evil.example/v1/get_result"
                }
                """,
            ),
            TestServer.json(
                """
                {
                  "status": "Ready",
                  "result": { "sample": "https://delivery.bfl.ai/image.png" }
                }
                """,
            ),
            TestServer.bytes(imageBytes, "image/png"),
        )
        provider(server, baseUrl = "https://api.bfl.ai/v1").imageModel(modelId).doGenerate(options())

        assertTrue(server.request(1).url.startsWith("https://api.bfl.ai:x@evil.example/"))
        server.request(1).assertNoHeader("x-key")
    }

    // --- polling ----------------------------------------------------------------------------------

    @Test
    fun `polling continues while BFL says Pending and stops the moment it says Ready`() = runTest {
        val server = server(
            polls = arrayOf(BflFixtures.PENDING, BflFixtures.PENDING, BflFixtures.READY),
        )
        provider(server).imageModel(modelId).doGenerate(options(aspectRatio = "1:1"))

        // Submit, three polls, then the download: nothing is fetched before the job says it is
        // finished.
        assertEquals(5, server.callCount)
        assertEquals("https://api.example.com/image.png", server.request(4).url)
    }

    /** A job that never finishes has to fail rather than hang a caller forever. */
    @Test
    fun `a job that never finishes times out, and never fetches an image`() = runTest {
        val server = TestServer(
            TestServer.json(BflFixtures.submitted()),
            TestServer.json(BflFixtures.PENDING),
        )
        assertFailsWith<JobTimeoutError> {
            provider(server).imageModel(modelId).doGenerate(options(aspectRatio = "1:1"))
        }
        assertTrue(server.calls.none { it.url.startsWith("https://api.example.com/image.png") })
    }

    @Test
    fun `state is read as a second spelling of status, not as an unknown field`() = runTest {
        val server = server(polls = arrayOf(BflFixtures.READY_UNDER_STATE))
        val result = provider(server).imageModel(modelId).doGenerate(options(aspectRatio = "1:1"))

        assertEquals(1, result.images.size)
        assertTrue(imageBytes contentEquals (result.images.single() as BinaryData.Bytes).value)
    }

    @Test
    fun `Ready with no sample is a failure rather than a download of nothing`() = runTest {
        val server = server(polls = arrayOf(BflFixtures.READY_WITH_NO_SAMPLE))
        assertFailsWith<JobFailedError> {
            provider(server).imageModel(modelId).doGenerate(options(aspectRatio = "1:1"))
        }
    }

    @Test
    fun `an Error status is a failed generation`() = runTest {
        val server = server(polls = arrayOf(BflFixtures.ERRORED))
        val error = assertFailsWith<JobFailedError> {
            provider(server).imageModel(modelId).doGenerate(options(aspectRatio = "1:1"))
        }
        assertTrue("Black Forest Labs generation failed" in error.message!!)
    }

    // --- metadata and errors -----------------------------------------------------------------------

    @Test
    fun `the seed the poll reports lands on the image it belongs to`() = runTest {
        val server = server(polls = arrayOf(BflFixtures.READY_WITH_SEED))
        val result = provider(server).imageModel(modelId).doGenerate(options(aspectRatio = "1:1"))

        assertEquals(
            parseJsonObject("""{ "images": [{ "seed": 12345 }] }"""),
            result.providerMetadata!!["blackForestLabs"],
        )
    }

    /**
     * The two halves come from two different responses: the seed and timings are only in the poll
     * result, the billing figures only in the submit. Reading one response gives half the answer.
     */
    @Test
    fun `the billing figures come off the submit and join the seed from the poll`() = runTest {
        val server = server(
            submit = BflFixtures.SUBMITTED_WITH_BILLING,
            polls = arrayOf(BflFixtures.READY_WITH_SEED),
        )
        val result = provider(server).imageModel(modelId).doGenerate(options(aspectRatio = "1:1"))

        assertEquals(
            parseJsonObject(
                """
                {
                  "images": [
                    { "seed": 12345, "cost": 0.08, "inputMegapixels": 1.5, "outputMegapixels": 2.0 }
                  ]
                }
                """,
            ),
            result.providerMetadata!!["blackForestLabs"],
        )
    }

    @Test
    fun `billing figures BFL did not report are absent, not present and empty`() = runTest {
        val server = server()
        val result = provider(server).imageModel(modelId).doGenerate(options(aspectRatio = "1:1"))

        assertEquals(
            parseJsonObject("""{ "images": [{}] }"""),
            result.providerMetadata!!["blackForestLabs"],
        )
    }

    /**
     * BFL fills these with JSON null rather than omitting them. Carrying the null through makes
     * `cost` present with no cost in it, which a caller reads as a free generation.
     */
    @Test
    fun `null billing figures are dropped rather than carried as nulls`() = runTest {
        val server = server(submit = BflFixtures.SUBMITTED_WITH_NULL_BILLING)
        val result = provider(server).imageModel(modelId).doGenerate(options(aspectRatio = "1:1"))

        assertEquals(
            parseJsonObject("""{ "images": [{}] }"""),
            result.providerMetadata!!["blackForestLabs"],
        )
    }

    /** BFL nests the real message under `detail` and puts a less specific one at the top level. */
    @Test
    fun `an error message comes from detail, not from the top-level message`() = runTest {
        val server = TestServer(
            TestServer.json(BflFixtures.ERROR_WITH_DETAIL, HttpStatusCode.BadRequest),
        )
        val error = assertFailsWith<APICallError> {
            provider(server).imageModel(modelId).doGenerate(options())
        }
        assertEquals(400, error.statusCode)
        assertEquals("$baseUrl/$modelId", error.url)
        assertTrue(error.message!!.endsWith("""{"error":"Invalid prompt"}"""), error.message!!)
    }

    @Test
    fun `the response names the model and the request id`() = runTest {
        val server = server()
        val result = provider(server).imageModel(modelId).doGenerate(options(aspectRatio = "1:1"))

        assertEquals(modelId, result.response.modelId)
        assertEquals("req-123", result.response.id)
        assertEquals(1, provider(server).imageModel(modelId).maxImagesPerCall())
    }
}
