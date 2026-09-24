package com.sabreware.aide.aisdk.providers.async

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoFile
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.providers.fal.FalProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * fal's video model, against the reference's own `fal-video-model.test.ts`.
 *
 * Video is where our contract and the reference's agree most exactly and matter most: a generation runs
 * for minutes, so `doStart` hands back an opaque handle and `doStatus` takes it back, and the whole point
 * of that split is that the handle outlives the process that made it. The reference can only pass its
 * operation object straight back in memory; the round-trip case at the bottom of this file is the one it
 * cannot express and the one the split exists for.
 */
class FalVideoConformanceTest {

    private val prompt = "A futuristic city with flying cars"
    private val modelId = "luma-dream-machine"
    private val queueUrl = "https://queue.fal.run"
    private val submitUrl = "$queueUrl/fal-ai/luma-dream-machine"
    private val responseUrl = "$submitUrl/requests/test-request-id-123"

    private fun TestServer.provider(apiKey: String = "test-key") =
        FalProvider(HttpClient(engine()), apiKey = apiKey, queueUrl = queueUrl)

    private fun TestServer.model() = provider().videoModel(modelId)

    private fun options(
        prompt: String? = this.prompt,
        n: Int = 1,
        seed: Int? = null,
        aspectRatio: String? = null,
        durationInSeconds: Double? = null,
        image: VideoFile? = null,
        fal: JsonObject? = null,
        headers: Map<String, String>? = null,
    ) = VideoCallOptions(
        prompt = prompt,
        n = n,
        seed = seed,
        aspectRatio = aspectRatio,
        durationInSeconds = durationInSeconds,
        image = image,
        providerOptions = fal?.let { mapOf("fal" to it) },
        headers = headers,
    )

    private fun submitted() = TestServer(TestServer.json(FalFixtures.QUEUE_SUBMITTED))

    // --- doStart --------------------------------------------------------------------------------

    @Test
    fun `the handle carries the URL to poll and the origin the key belongs to`() = runTest {
        val server = submitted()
        val result = server.model().doStart(options())!!

        assertEquals(
            parseJsonObject("""{ "responseUrl": "$responseUrl", "submitUrl": "$submitUrl" }"""),
            result.operation,
        )
        result.warnings.assertNoWarnings()
        assertEquals(modelId, result.response.modelId)
    }

    @Test
    fun `a bare prompt submits exactly a prompt`() = runTest {
        val server = submitted()
        server.model().doStart(options())
        server.request().assertBodyEquals("""{ "prompt": "$prompt" }""")
    }

    @Test
    fun `seed and aspect ratio go under fal's own names`() = runTest {
        val server = submitted()
        server.model().doStart(options(seed = 42, aspectRatio = "16:9"))
        server.request().assertBodyEquals(
            """{ "prompt": "$prompt", "seed": 42, "aspect_ratio": "16:9" }""",
        )
    }

    /** fal wants `"5s"`. A bare `5` — or worse, `5.0` — is a rejected parameter, not a five-second clip. */
    @Test
    fun `a duration becomes fal's seconds string with no trailing zero`() = runTest {
        val server = submitted()
        server.model().doStart(options(durationInSeconds = 5.0))
        server.request().assertBodyEquals("""{ "prompt": "$prompt", "duration": "5s" }""")
    }

    @Test
    fun `the call's own headers ride alongside the auth header`() = runTest {
        val server = submitted()
        server.model().doStart(
            options(headers = mapOf("Custom-Request-Header" to "request-header-value")),
        )

        val call = server.request()
        call.assertHeader("Authorization", "Key test-key")
        call.assertHeader("Custom-Request-Header", "request-header-value")
    }

    @Test
    fun `a queue answer with no response_url is a failure rather than an unpollable handle`() =
        runTest {
            val server = TestServer(TestServer.json("{}"))
            assertFailsWith<NoContentGeneratedError> { server.model().doStart(options()) }
        }

    @Test
    fun `an error from the queue endpoint is thrown, not turned into a handle`() = runTest {
        val server = TestServer(
            TestServer.json("""{ "error": { "message": "Invalid prompt" } }""", HttpStatusCode.BadRequest),
        )
        val error = assertFailsWith<APICallError> { server.model().doStart(options()) }
        assertEquals(400, error.statusCode)
    }

    /**
     * The colon and slashes of the callback address would otherwise be read as query structure, and fal
     * would notify a truncated URL — a listener that is never called, with nothing logged to say why.
     */
    @Test
    fun `a webhook URL is percent-encoded into the fal_webhook query parameter`() = runTest {
        val server = submitted()
        server.model().doStart(options(), webhookUrl = "https://smee.io/abc123")

        assertEquals(
            "$submitUrl?fal_webhook=https%3A%2F%2Fsmee.io%2Fabc123",
            server.request().url,
        )
    }

    @Test
    fun `fal is one of the vendors that really does notify a webhook`() = runTest {
        assertEquals(true, submitted().model().supportsWebhooks)
    }

    @Test
    fun `a starting frame given as bytes is inlined as a data URI`() = runTest {
        val server = submitted()
        server.model().doStart(
            options(
                image = VideoFile.Data(
                    BinaryData.Bytes(byteArrayOf(137.toByte(), 80, 78, 71)),
                    "image/png",
                ),
            ),
        )
        server.request().assertBodyEquals(
            """{ "prompt": "$prompt", "image_url": "data:image/png;base64,iVBORw==" }""",
        )
    }

    @Test
    fun `a starting frame given as a URL is passed through`() = runTest {
        val server = submitted()
        server.model().doStart(options(image = VideoFile.Url("https://example.com/input-image.png")))
        server.request().assertBodyEquals(
            """{ "prompt": "$prompt", "image_url": "https://example.com/input-image.png" }""",
        )
    }

    @Test
    fun `an option fal already spells in snake_case is left alone`() = runTest {
        val server = submitted()
        server.model().doStart(options(fal = buildJsonObject { put("loop", true) }))
        server.request().assertBodyEquals("""{ "prompt": "$prompt", "loop": true }""")
    }

    @Test
    fun `the camelCase video options are renamed to fal's own`() = runTest {
        val server = submitted()
        server.model().doStart(
            options(
                fal = buildJsonObject {
                    put("motionStrength", 0.8)
                    put("negativePrompt", "blurry, low quality")
                    put("promptOptimizer", true)
                },
            ),
        )
        server.request().assertBodyEquals(
            """
            {
              "prompt": "$prompt",
              "motion_strength": 0.8,
              "negative_prompt": "blurry, low quality",
              "prompt_optimizer": true
            }
            """,
        )
    }

    /**
     * fal's resolutions are per model and it has no shared vocabulary for them, so this one goes through
     * provider options untouched. That is what makes a model this port has never heard of usable.
     */
    @Test
    fun `an option this port has never heard of is passed through verbatim`() = runTest {
        val server = submitted()
        server.model().doStart(
            options(
                fal = buildJsonObject {
                    put("resolution", "1080p")
                    put("custom_param", "custom_value")
                    put("another_param", 123)
                },
            ),
        )
        server.request().assertBodyEquals(
            """
            {
              "prompt": "$prompt",
              "resolution": "1080p",
              "custom_param": "custom_value",
              "another_param": 123
            }
            """,
        )
    }

    @Test
    fun `fal renders one clip per call`() = runTest {
        assertEquals(1, submitted().model().maxVideosPerCall())
    }

    // --- doStatus -------------------------------------------------------------------------------

    private fun handle(url: String = responseUrl): JsonElement = buildJsonObject {
        put("responseUrl", url)
        put("submitUrl", submitUrl)
    }

    @Test
    fun `a finished job hands back the clip URL and its media type`() = runTest {
        val server = TestServer(TestServer.json(FalFixtures.QUEUE_COMPLETED))
        val status = assertIs<VideoStatusResult.Completed>(server.model().doStatus(handle()))

        assertEquals(
            listOf(VideoData.Url("https://fal.media/files/video-output.mp4", "video/mp4")),
            status.videos,
        )
    }

    @Test
    fun `the whole of fal's own answer survives as metadata`() = runTest {
        val server = TestServer(TestServer.json(FalFixtures.QUEUE_COMPLETED))
        val status = assertIs<VideoStatusResult.Completed>(server.model().doStatus(handle()))

        assertEquals(
            parseJsonObject(
                """
                {
                  "videos": [
                    {
                      "url": "https://fal.media/files/video-output.mp4",
                      "width": 1920,
                      "height": 1080,
                      "duration": 5.0,
                      "fps": 24,
                      "contentType": "video/mp4"
                    }
                  ],
                  "seed": 12345,
                  "timings": { "inference": 45.5 }
                }
                """,
            ),
            status.providerMetadata!!["fal"],
        )
    }

    /**
     * fal reports "not finished yet" as a 500 with a `detail` string, not as a status field. Letting that
     * escape turns every poll made before the clip is ready into a failed generation.
     */
    @Test
    fun `a still-rendering clip is pending, even though fal says it with a 500`() = runTest {
        val server = TestServer(
            TestServer.json(FalFixtures.QUEUE_IN_PROGRESS, HttpStatusCode.InternalServerError),
        )
        assertIs<VideoStatusResult.Pending>(server.model().doStatus(handle()))
        // Exactly one request: a pending poll must not become a retry storm against the queue.
        assertEquals(1, server.callCount)
    }

    /** A genuine 500 is a different answer from "still working", and must stay distinguishable. */
    @Test
    fun `a real server error is a failed status carrying the vendor's own message`() = runTest {
        val server = TestServer(
            TestServer.json(FalFixtures.QUEUE_SERVER_ERROR, HttpStatusCode.InternalServerError),
        )
        val status = assertIs<VideoStatusResult.Failed>(server.model().doStatus(handle()))
        assertEquals(true, status.error.endsWith("Internal server error"))
    }

    @Test
    fun `a finished job with no video URL is a generation failure`() = runTest {
        val server = TestServer(TestServer.json("{}"))
        assertFailsWith<NoContentGeneratedError> { server.model().doStatus(handle()) }
    }

    @Test
    fun `a handle with no responseUrl is refused rather than polled blindly`() = runTest {
        val server = TestServer(TestServer.json(FalFixtures.QUEUE_COMPLETED))
        assertFailsWith<NoContentGeneratedError> {
            server.model().doStatus(buildJsonObject { put("submitUrl", submitUrl) })
        }
    }

    /**
     * The key rides only to the origin we submitted to. `doStatus` is handed a URL that came out of a
     * vendor's own response body, so a fal answer naming someone else's host must not be handed our
     * credential — which is the whole reason the handle carries the submit URL as well as the poll URL.
     */
    @Test
    fun `the API key is not sent to a host fal's response merely named`() = runTest {
        val server = TestServer(TestServer.json(FalFixtures.QUEUE_COMPLETED))
        server.model().doStatus(
            buildJsonObject {
                put("responseUrl", "https://attacker.example/requests/1")
                put("submitUrl", submitUrl)
            },
        )
        server.request().assertNoHeader("Authorization")
    }

    // --- the property the split exists for --------------------------------------------------------

    /**
     * A video generation runs for minutes, so the caller that collects it is routinely not the process —
     * or even the machine — that started it. The handle is therefore serialized, stored, and handed back
     * later by somebody else; this pins that a handle which has been through JSON and a fresh model
     * instance polls exactly the same job.
     *
     * The reference cannot state this: its own test passes the operation object straight back in memory,
     * so a handle carrying something unserializable would still pass there and fail here.
     */
    @Test
    fun `a handle survives being serialized, stored and polled by a different caller`() = runTest {
        val starting = submitted()
        val started = starting.model().doStart(options())!!

        val stored = Json.encodeToString(JsonElement.serializer(), started.operation)
        val restored = Json.parseToJsonElement(stored)

        val collecting = TestServer(TestServer.json(FalFixtures.QUEUE_COMPLETED))
        val status = assertIs<VideoStatusResult.Completed>(collecting.model().doStatus(restored))

        assertEquals(
            listOf(VideoData.Url("https://fal.media/files/video-output.mp4", "video/mp4")),
            status.videos,
        )
        // The second process polled the URL the first one was told to, not one it rebuilt for itself.
        assertEquals(responseUrl, collecting.request().url)
    }
}
