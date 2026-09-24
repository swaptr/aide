package com.sabreware.aide.aisdk.providers.async

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoFile
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.replicate.ReplicateProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.util.JobFailedError
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Replicate's image and video models, against the reference's own two test files.
 *
 * Replicate is the vendor whose id decides its transport — `owner/name` posts to
 * `/models/{id}/predictions`, `owner/name:sha` posts to `/predictions` with the sha in the body — and
 * whose output field is a union of a bare string and an array. Both are exactly the sort of thing a
 * per-key assertion cannot see, so every request case below compares the whole body.
 */
class ReplicateConformanceTest {

    private val prompt = "The Loch Ness monster getting a manicure"
    private val modelId = "black-forest-labs/flux-schnell"
    private val baseUrl = "https://api.replicate.com/v1"
    private val imageBytes = "test-binary-content".encodeToByteArray()

    private fun TestServer.provider() =
        ReplicateProvider(HttpClient(engine()), apiToken = "test-api-token", baseUrl = baseUrl)

    private fun server(
        prediction: String = ReplicateFixtures.prediction(),
        downloads: Int = 1,
    ) = TestServer(
        TestServer.json(prediction),
        *Array(downloads) { TestServer.bytes(imageBytes, "image/webp") },
    )

    private fun options(
        prompt: String? = this.prompt,
        n: Int = 1,
        size: String? = null,
        aspectRatio: String? = null,
        seed: Int? = null,
        files: List<ImageFile>? = null,
        mask: ImageFile? = null,
        replicate: JsonObject? = null,
        other: JsonObject? = null,
        headers: Map<String, String>? = null,
    ) = ImageCallOptions(
        prompt = prompt,
        n = n,
        size = size,
        aspectRatio = aspectRatio,
        seed = seed,
        files = files,
        mask = mask,
        providerOptions = buildMap {
            replicate?.let { put("replicate", it) }
            other?.let { put("other", it) }
        }.takeIf { it.isNotEmpty() },
        headers = headers,
    )

    // --- image: the request ----------------------------------------------------------------------

    /**
     * Everything the model takes goes under `input`, and another provider's options never leak into it.
     * A stray key there is not ignored by Replicate; it is an "unexpected parameter" rejection.
     */
    @Test
    fun `every model input goes under input, and another provider's options stay out of it`() =
        runTest {
            val server = server()
            server.provider().imageModel(modelId).doGenerate(
                options(
                    size = "1024x768",
                    aspectRatio = "3:4",
                    seed = 123,
                    replicate = buildJsonObject { put("style", "realistic_image") },
                    other = buildJsonObject { put("something", "else") },
                ),
            )

            server.request().assertBodyEquals(
                """
                {
                  "input": {
                    "prompt": "$prompt",
                    "num_outputs": 1,
                    "aspect_ratio": "3:4",
                    "size": "1024x768",
                    "seed": 123,
                    "style": "realistic_image"
                  }
                }
                """,
            )
        }

    @Test
    fun `an unversioned id posts to the model's own predictions endpoint`() = runTest {
        val server = server()
        server.provider().imageModel(modelId).doGenerate(options())

        val call = server.request()
        assertEquals("POST", call.method)
        assertEquals("$baseUrl/models/$modelId/predictions", call.url)
    }

    /**
     * A versioned id pins a sha and must post to the bare `/predictions` with `version` in the body.
     * Building the `/models/...` URL for it is a 404 that reads as a deleted model.
     */
    @Test
    fun `a versioned id posts to predictions with the sha in the body, not in the path`() = runTest {
        val server = server()
        val versioned = "bytedance/sdxl-lightning-4step:" +
            "5599ed30703defd1d160a25a63321b4dec97101d98b4674bcc56e41f62f35637"
        server.provider().imageModel(versioned).doGenerate(options())

        val call = server.request()
        assertEquals("$baseUrl/predictions", call.url)
        call.assertBodyEquals(
            """
            {
              "input": { "prompt": "$prompt", "num_outputs": 1 },
              "version": "5599ed30703defd1d160a25a63321b4dec97101d98b4674bcc56e41f62f35637"
            }
            """,
        )
    }

    /** `Prefer: wait` is what makes an image call one round trip instead of a poll loop. */
    @Test
    fun `the prefer header asks Replicate to hold the request open`() = runTest {
        val server = server()
        server.provider().imageModel(modelId).doGenerate(
            options(headers = mapOf("Custom-Request-Header" to "request-header-value")),
        )

        val call = server.request()
        call.assertHeader("Authorization", "Bearer test-api-token")
        call.assertHeader("prefer", "wait")
        call.assertHeader("Custom-Request-Header", "request-header-value")
    }

    @Test
    fun `a wait budget becomes the number on the prefer header`() = runTest {
        val server = server()
        server.provider().imageModel(modelId).doGenerate(
            options(replicate = buildJsonObject { put("maxWaitTimeInSeconds", 120) }),
        )
        server.request().assertHeader("prefer", "wait=120")
    }

    /** The wait budget steers the transport; sent as a model input it is a rejected parameter. */
    @Test
    fun `the wait budget never reaches the model's own inputs`() = runTest {
        val server = server()
        server.provider().imageModel(modelId).doGenerate(
            options(
                replicate = buildJsonObject {
                    put("maxWaitTimeInSeconds", 120)
                    put("guidance_scale", 7.5)
                },
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "input": { "prompt": "$prompt", "num_outputs": 1, "guidance_scale": 7.5 }
            }
            """,
        )
    }

    // --- image: the response ----------------------------------------------------------------------

    @Test
    fun `an array output is fetched and handed back as bytes`() = runTest {
        val server = server()
        val result = server.provider().imageModel(modelId).doGenerate(options())

        assertEquals(1, result.images.size)
        assertTrue(imageBytes contentEquals (result.images.single() as BinaryData.Bytes).value)
        assertEquals("GET", server.request(1).method)
        assertEquals("https://replicate.delivery/xezq/abc/out-0.webp", server.request(1).url)
    }

    /**
     * A single-output model answers with a BARE STRING where the others answer with an array. Reading
     * only the array shape drops every image those models produce, and does it silently.
     */
    @Test
    fun `a bare string output is the same one image, not nothing`() = runTest {
        val server = server(
            ReplicateFixtures.prediction(output = "\"https://replicate.delivery/xezq/abc/out-0.webp\""),
        )
        val result = server.provider().imageModel(modelId).doGenerate(options())

        assertEquals(1, result.images.size)
        assertEquals("https://replicate.delivery/xezq/abc/out-0.webp", server.request(1).url)
    }

    /**
     * The recorded wire says `"status": "processing"` while carrying a finished `output`. A status check
     * that overrules a present output turns every ordinary generation into a spurious timeout.
     */
    @Test
    fun `an output that is already present beats whatever the status field says`() = runTest {
        val server = server(ReplicateFixtures.prediction(status = "processing"))
        val result = server.provider().imageModel(modelId).doGenerate(options())
        assertEquals(1, result.images.size)
    }

    @Test
    fun `the prediction id and the billed metrics survive as metadata`() = runTest {
        val server = server()
        val result = server.provider().imageModel(modelId).doGenerate(options())

        assertEquals(
            "s7x1e3dcmhrmc0cm8rbatcneec",
            result.providerMetadata!!["replicate"]!!["predictionId"].toString().trim('"'),
        )
        assertEquals("s7x1e3dcmhrmc0cm8rbatcneec", result.response.id)
        assertEquals(modelId, result.response.modelId)
    }

    /**
     * `Prefer: wait` answers 200 for a prediction that produced nothing, so a refusal arrives as a
     * successful HTTP call. Reading only the status code reports a success with no images.
     */
    @Test
    fun `a refused prediction is a failure even though the call returned 200`() = runTest {
        val server = server(
            """
            {
              "id": "abc",
              "status": "failed",
              "output": null,
              "error": "NSFW content detected"
            }
            """,
        )
        val error = assertFailsWith<JobFailedError> {
            server.provider().imageModel(modelId).doGenerate(options())
        }
        assertTrue("NSFW content detected" in error.message!!)
    }

    @Test
    fun `an aborted image prediction reports the deadline rather than an empty result`() = runTest {
        // Before this was named, `aborted` fell through to the generic no-output error — true, but
        // silent about the one fact a caller can act on. Replicate documents it as the prediction
        // exceeding its deadline before it started running, which a retry often clears.
        val server = server(
            """
            {
              "id": "abc",
              "status": "aborted",
              "output": null
            }
            """,
        )
        val error = assertFailsWith<JobFailedError> {
            server.provider().imageModel(modelId).doGenerate(options())
        }
        assertTrue("deadline" in error.message!!, error.message!!)
    }

    /**
     * A JSON null is not a URL. Read as a bare string it becomes the four characters `null`, which then
     * gets requested — a download of a path that does not exist, reported as a network failure rather
     * than as the empty result it is.
     */
    @Test
    fun `a null output is no content, not the string null`() = runTest {
        val server = server(ReplicateFixtures.prediction(output = "null", status = "succeeded"))
        assertFailsWith<NoContentGeneratedError> {
            server.provider().imageModel(modelId).doGenerate(options())
        }
    }

    // --- image: editing ---------------------------------------------------------------------------

    @Test
    fun `a URL source is sent as the image input untouched`() = runTest {
        val server = server()
        server.provider().imageModel(modelId).doGenerate(
            options(
                prompt = "Add a hat to the person",
                files = listOf(ImageFile.Url("https://example.com/input.jpg")),
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "input": {
                "image": "https://example.com/input.jpg",
                "num_outputs": 1,
                "prompt": "Add a hat to the person"
              }
            }
            """,
        )
    }

    @Test
    fun `bytes become a data URI and base64 is wrapped rather than re-encoded`() = runTest {
        val already = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk" +
            "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val fromBytes = server()
        fromBytes.provider().imageModel(modelId).doGenerate(
            options(
                prompt = "Transform this image",
                files = listOf(
                    ImageFile.Data(
                        BinaryData.Bytes(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)),
                        "image/png",
                    ),
                ),
            ),
        )
        assertEquals(
            "data:image/png;base64,iVBORw0KGgo=",
            fromBytes.request().bodyJson()["input"]!!.let { parseJsonObject(it.toString()) }["image"]
                .toString().trim('"'),
        )

        val fromBase64 = server()
        fromBase64.provider().imageModel(modelId).doGenerate(
            options(
                prompt = "Edit this",
                files = listOf(ImageFile.Data(BinaryData.Base64(already), "image/png")),
            ),
        )
        fromBase64.request().assertBodyEquals(
            """
            {
              "input": {
                "image": "data:image/png;base64,$already",
                "num_outputs": 1,
                "prompt": "Edit this"
              }
            }
            """,
        )
    }

    @Test
    fun `a mask rides beside the source it applies to`() = runTest {
        val server = server()
        server.provider().imageModel(modelId).doGenerate(
            options(
                prompt = "Replace the masked area with a tree",
                files = listOf(ImageFile.Url("https://example.com/input.jpg")),
                mask = ImageFile.Url("https://example.com/mask.png"),
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "input": {
                "image": "https://example.com/input.jpg",
                "mask": "https://example.com/mask.png",
                "num_outputs": 1,
                "prompt": "Replace the masked area with a tree"
              }
            }
            """,
        )
    }

    @Test
    fun `a second source is dropped with a warning, not silently`() = runTest {
        val server = server()
        val result = server.provider().imageModel(modelId).doGenerate(
            options(
                prompt = "Edit multiple images",
                files = listOf(
                    ImageFile.Url("https://example.com/input1.jpg"),
                    ImageFile.Url("https://example.com/input2.jpg"),
                ),
            ),
        )

        assertEquals(
            listOf(
                Warning.Other(
                    "This Replicate model only supports a single input image. " +
                        "Additional images are ignored.",
                ),
            ),
            result.warnings,
        )
    }

    // --- image: flux-2 ------------------------------------------------------------------------------

    private val flux2 = "black-forest-labs/flux-2-pro"

    @Test
    fun `flux-2 takes eight inputs where every other model takes one`() = runTest {
        val provider = server().provider()
        assertEquals(8, provider.imageModel(flux2).maxImagesPerCall())
        assertEquals(1, provider.imageModel(modelId).maxImagesPerCall())
    }

    @Test
    fun `flux-2 numbers its inputs from the second one, and the first has no number`() = runTest {
        val server = server()
        server.provider().imageModel(flux2).doGenerate(
            options(
                prompt = "Combine styles from reference images",
                files = listOf(
                    ImageFile.Url("https://example.com/reference1.jpg"),
                    ImageFile.Url("https://example.com/reference2.jpg"),
                    ImageFile.Url("https://example.com/reference3.jpg"),
                ),
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "input": {
                "input_image": "https://example.com/reference1.jpg",
                "input_image_2": "https://example.com/reference2.jpg",
                "input_image_3": "https://example.com/reference3.jpg",
                "num_outputs": 1,
                "prompt": "Combine styles from reference images"
              }
            }
            """,
        )
    }

    @Test
    fun `a ninth flux-2 input is dropped with a warning`() = runTest {
        val server = server()
        val result = server.provider().imageModel(flux2).doGenerate(
            options(
                prompt = "Too many images",
                files = List(9) { ImageFile.Url("https://example.com/img${it + 1}.jpg") },
            ),
        )

        assertEquals(
            listOf(
                Warning.Other(
                    "Flux-2 models support up to 8 input images. Additional images are ignored.",
                ),
            ),
            result.warnings,
        )
        val input = parseJsonObject(server.request().bodyJson()["input"].toString())
        assertEquals("https://example.com/img1.jpg", input["input_image"].toString().trim('"'))
        assertEquals("https://example.com/img8.jpg", input["input_image_8"].toString().trim('"'))
        assertTrue("input_image_9" !in input)
    }

    @Test
    fun `flux-2 has no mask input, so a mask is refused rather than quietly sent`() = runTest {
        val server = server()
        val result = server.provider().imageModel(flux2).doGenerate(
            options(
                prompt = "Edit with mask",
                files = listOf(ImageFile.Url("https://example.com/input.jpg")),
                mask = ImageFile.Url("https://example.com/mask.png"),
            ),
        )

        assertEquals(
            listOf(
                Warning.Other("Flux-2 models do not support mask input. The mask will be ignored."),
            ),
            result.warnings,
        )
        assertTrue("mask" !in parseJsonObject(server.request().bodyJson()["input"].toString()))
    }

    @Test
    fun `flux-2 is still addressed by its model endpoint`() = runTest {
        val server = server()
        server.provider().imageModel(flux2).doGenerate(options(prompt = "Generate something"))
        assertEquals("$baseUrl/models/$flux2/predictions", server.request().url)
    }

    // --- video ------------------------------------------------------------------------------------

    private val videoModelId = "minimax/video-01"
    private val videoPrompt = "A rocket launching into space"
    private val getUrl = "$baseUrl/predictions/test-prediction-id"

    private fun videoOptions(
        n: Int = 1,
        seed: Int? = null,
        aspectRatio: String? = null,
        resolution: String? = null,
        durationInSeconds: Double? = null,
        fps: Int? = null,
        image: VideoFile? = null,
        replicate: JsonObject? = null,
    ) = VideoCallOptions(
        prompt = videoPrompt,
        n = n,
        seed = seed,
        aspectRatio = aspectRatio,
        resolution = resolution,
        durationInSeconds = durationInSeconds,
        fps = fps,
        image = image,
        providerOptions = replicate?.let { mapOf("replicate" to it) },
    )

    private fun videoServer(body: String = ReplicateFixtures.videoPrediction()) =
        TestServer(TestServer.json(body))

    @Test
    fun `the video handle is the poll URL Replicate itself named`() = runTest {
        val server = videoServer()
        val result = server.provider().videoModel(videoModelId).doStart(videoOptions())!!

        assertEquals(parseJsonObject("""{ "getUrl": "$getUrl" }"""), result.operation)
        result.warnings.assertNoWarnings()
        assertEquals(videoModelId, result.response.modelId)
        assertEquals(1, server.provider().videoModel(videoModelId).maxVideosPerCall())
    }

    /**
     * A clip takes minutes, so holding the request open is not an option — and asking Replicate to do it
     * anyway means the start call times out with the job already running and no handle to reach it by.
     */
    @Test
    fun `video never asks Replicate to wait`() = runTest {
        val server = videoServer()
        server.provider().videoModel(videoModelId).doStart(videoOptions())
        server.request().assertNoHeader("prefer")
    }

    @Test
    fun `every video parameter lands under its Replicate name`() = runTest {
        val server = videoServer()
        server.provider().videoModel(videoModelId).doStart(
            videoOptions(
                seed = 42,
                aspectRatio = "16:9",
                // Replicate spells a clip's pixel dimensions `size`, not `resolution`.
                resolution = "1920x1080",
                durationInSeconds = 5.0,
                fps = 30,
                image = VideoFile.Url("https://example.com/image.png"),
                replicate = buildJsonObject {
                    put("guidance_scale", 7.5)
                    put("motion_bucket_id", 127)
                    put("prompt_optimizer", true)
                    put("custom_param", "custom_value")
                },
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "input": {
                "prompt": "$videoPrompt",
                "image": "https://example.com/image.png",
                "aspect_ratio": "16:9",
                "size": "1920x1080",
                "duration": 5.0,
                "fps": 30,
                "seed": 42,
                "guidance_scale": 7.5,
                "motion_bucket_id": 127,
                "prompt_optimizer": true,
                "custom_param": "custom_value"
              }
            }
            """,
        )
    }

    @Test
    fun `a versioned video id pins its sha in the body`() = runTest {
        val server = videoServer()
        server.provider().videoModel("stability-ai/stable-video-diffusion:abc123")
            .doStart(videoOptions())

        val call = server.request()
        assertEquals("$baseUrl/predictions", call.url)
        assertEquals("abc123", call.bodyJson()["version"].toString().trim('"'))
    }

    /**
     * Without the events filter Replicate calls the webhook on every intermediate log line, so a
     * listener written for "the clip is ready" fires dozens of times per job.
     */
    @Test
    fun `a webhook is registered for the completed event alone`() = runTest {
        val server = videoServer()
        server.provider().videoModel(videoModelId)
            .doStart(videoOptions(), webhookUrl = "https://example.com/webhook")

        server.request().assertBodyEquals(
            """
            {
              "input": { "prompt": "$videoPrompt" },
              "webhook": "https://example.com/webhook",
              "webhook_events_filter": ["completed"]
            }
            """,
        )
    }

    @Test
    fun `no webhook URL means no webhook keys at all`() = runTest {
        val server = videoServer()
        server.provider().videoModel(videoModelId).doStart(videoOptions())
        server.request().assertBodyEquals("""{ "input": { "prompt": "$videoPrompt" } }""")
    }

    private fun handle(url: String = getUrl): JsonElement = buildJsonObject { put("getUrl", url) }

    @Test
    fun `a succeeded prediction is the clip URL`() = runTest {
        val server = videoServer(
            ReplicateFixtures.videoPrediction(
                output = "\"https://replicate.delivery/video-output.mp4\"",
            ),
        )
        val status = assertIs<VideoStatusResult.Completed>(
            server.provider().videoModel(videoModelId).doStatus(handle()),
        )
        assertEquals(
            listOf(VideoData.Url("https://replicate.delivery/video-output.mp4", "video/mp4")),
            status.videos,
        )
    }

    @Test
    fun `starting and processing are both still-working, not answers`() = runTest {
        for (state in listOf("starting", "processing")) {
            val server = videoServer(ReplicateFixtures.videoPrediction(status = state))
            assertIs<VideoStatusResult.Pending>(
                server.provider().videoModel(videoModelId).doStatus(handle()),
                state,
            )
        }
    }

    /**
     * A refused job and a cancelled one are answers, not transport problems. Throwing them would have a
     * retry policy repeat a generation that will be refused identically every time.
     */
    @Test
    fun `a failed prediction carries Replicate's own reason as a status, not as a throw`() = runTest {
        val server = videoServer(
            ReplicateFixtures.videoPrediction(status = "failed", error = "\"GPU out of memory\""),
        )
        val status = assertIs<VideoStatusResult.Failed>(
            server.provider().videoModel(videoModelId).doStatus(handle()),
        )
        assertTrue("GPU out of memory" in status.error)
    }

    @Test
    fun `a cancelled prediction says so`() = runTest {
        val server = videoServer(ReplicateFixtures.videoPrediction(status = "canceled"))
        val status = assertIs<VideoStatusResult.Failed>(
            server.provider().videoModel(videoModelId).doStatus(handle()),
        )
        assertTrue("canceled" in status.error)
    }

    @Test
    fun `an aborted prediction is named as a deadline, not lumped in with cancellation`() = runTest {
        // Replicate documents six statuses; `aborted` is the one absent from the schema this port was
        // written against. It means the prediction exceeded its deadline BEFORE it started running —
        // which is why it is billed at nothing, where a cancellation is billed for the time that ran.
        val server = videoServer(ReplicateFixtures.videoPrediction(status = "aborted"))
        val status = assertIs<VideoStatusResult.Failed>(
            server.provider().videoModel(videoModelId).doStatus(handle()),
        )
        assertTrue("deadline" in status.error, status.error)
    }

    @Test
    fun `a status this port has never seen is still terminal`() = runTest {
        // The permissive fallback is deliberate and better than the reference's strict enum, which
        // fails to PARSE an unknown status. Treating it as pending would poll to the caller's timeout
        // and then report the wrong failure.
        val server = videoServer(ReplicateFixtures.videoPrediction(status = "some_future_status"))
        val status = assertIs<VideoStatusResult.Failed>(
            server.provider().videoModel(videoModelId).doStatus(handle()),
        )
        assertTrue("some_future_status" in status.error, status.error)
    }

    @Test
    fun `a success carrying a null output is no content, not the string null`() = runTest {
        val server = videoServer(ReplicateFixtures.videoPrediction(output = "null"))
        assertFailsWith<NoContentGeneratedError> {
            server.provider().videoModel(videoModelId).doStatus(handle())
        }
    }

    @Test
    fun `the completed status carries the prediction id and the billed time`() = runTest {
        val server = videoServer(
            ReplicateFixtures.videoPrediction(
                id = "meta-pred-456",
                metrics = """{ "predict_time": 30.2 }""",
            ),
        )
        val status = assertIs<VideoStatusResult.Completed>(
            server.provider().videoModel(videoModelId).doStatus(handle()),
        )

        assertEquals(
            parseJsonObject(
                """
                {
                  "videos": [{ "url": "https://replicate.delivery/video.mp4" }],
                  "predictionId": "meta-pred-456",
                  "metrics": { "predict_time": 30.2 }
                }
                """,
            ),
            status.providerMetadata!!["replicate"],
        )
    }

    /**
     * The same property the fal suite pins, for the same reason: the caller that collects a clip is
     * routinely not the process that started it, so the handle has to go through JSON and come back
     * pointing at the same job.
     */
    @Test
    fun `a handle survives being serialized, stored and polled by a different caller`() = runTest {
        val starting = videoServer()
        val started = starting.provider().videoModel(videoModelId).doStart(videoOptions())!!

        val restored = Json.parseToJsonElement(
            Json.encodeToString(JsonElement.serializer(), started.operation),
        )

        val collecting = videoServer()
        val status = assertIs<VideoStatusResult.Completed>(
            collecting.provider().videoModel(videoModelId).doStatus(restored),
        )
        assertEquals(
            listOf(VideoData.Url("https://replicate.delivery/video.mp4", "video/mp4")),
            status.videos,
        )
        assertEquals(getUrl, collecting.request().url)
    }
}
