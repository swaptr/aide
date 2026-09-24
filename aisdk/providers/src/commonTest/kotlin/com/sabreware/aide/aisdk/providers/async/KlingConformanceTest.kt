package com.sabreware.aide.aisdk.providers.async

import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.NoSuchModelError
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoFile
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.providers.kling.KlingProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
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
 * Kling AI's video models, against the reference's own `klingai-video-model.test.ts`.
 *
 * Kling routes by the SUFFIX of the model id and then sends a `model_name` that is not the id it was
 * asked for — the suffix comes off, the version is hyphenated, and a trailing `.0` disappears entirely.
 * Each of those three transformations, got wrong, is a "model not found" that reads like an unavailable
 * model rather than a client bug, which is why they are pinned one at a time here.
 */
class KlingConformanceTest {

    private val prompt = "A character performs a graceful dance"
    private val baseUrl = "https://api-singapore.klingai.com"

    private fun TestServer.provider() = KlingProvider(
        client = HttpClient(engine()),
        apiKey = "test-jwt-token",
        baseUrl = baseUrl,
    )

    private fun created() = TestServer(TestServer.json(KlingFixtures.TASK_CREATED))

    private fun options(
        prompt: String? = this.prompt,
        n: Int = 1,
        aspectRatio: String? = null,
        durationInSeconds: Double? = null,
        image: VideoFile? = null,
        kling: JsonObject? = null,
    ) = VideoCallOptions(
        prompt = prompt,
        n = n,
        aspectRatio = aspectRatio,
        durationInSeconds = durationInSeconds,
        image = image,
        providerOptions = kling?.let { mapOf("klingai" to it) },
    )

    private val motionControlOptions = buildJsonObject {
        put("videoUrl", "https://example.com/reference-motion.mp4")
        put("characterOrientation", "image")
        put("mode", "std")
    }

    private val stdMode = buildJsonObject { put("mode", "std") }

    // --- routing ------------------------------------------------------------------------------------

    /**
     * The mode is not a parameter; it is the last part of the id. An id with no recognised suffix has no
     * endpoint to send to, so it is a typo rather than a capability this port lacks.
     */
    @Test
    fun `an id with no mode suffix has no endpoint, and says so as a missing model`() = runTest {
        val server = created()
        assertFailsWith<NoSuchModelError> {
            server.provider().videoModel("unknown-model").doStart(options(kling = stdMode))
        }
    }

    @Test
    fun `each suffix picks its own endpoint`() = runTest {
        val routes = listOf(
            "kling-v2.6-t2v" to "$baseUrl/v1/videos/text2video",
            "kling-v2.6-i2v" to "$baseUrl/v1/videos/image2video",
            "kling-v2.6-motion-control" to "$baseUrl/v1/videos/motion-control",
        )
        for ((modelId, url) in routes) {
            val server = created()
            server.provider().videoModel(modelId).doStart(
                options(
                    image = VideoFile.Url("https://example.com/start-frame.png")
                        .takeIf { modelId.endsWith("-i2v") },
                    kling = if (modelId.endsWith("-motion-control")) motionControlOptions else stdMode,
                ),
            )
            assertEquals(url, server.request().url, modelId)
        }
    }

    /**
     * `kling-v3.0-t2v` is `kling-v3` on the wire — not `kling-v3-0`, and not the id itself. The suffix
     * names the endpoint, the version is hyphenated, and a trailing `.0` is dropped.
     */
    @Test
    fun `the wire model name drops the suffix, hyphenates the version and loses a trailing zero`() =
        runTest {
            val names = listOf(
                "kling-v2.6-t2v" to "kling-v2-6",
                "kling-v3.0-t2v" to "kling-v3",
                "kling-v3.0-i2v" to "kling-v3",
                "kling-v3.0-motion-control" to "kling-v3",
                "klingv1-t2v" to "klingv1",
            )
            for ((modelId, expected) in names) {
                val server = created()
                server.provider().videoModel(modelId).doStart(
                    options(
                        image = VideoFile.Url("https://example.com/start-frame.png")
                            .takeIf { modelId.endsWith("-i2v") },
                        kling = if (modelId.endsWith("-motion-control")) {
                            motionControlOptions
                        } else {
                            stdMode
                        },
                    ),
                )
                assertEquals(
                    expected,
                    server.request().bodyJson()["model_name"].toString().trim('"'),
                    modelId,
                )
            }
        }

    // --- request bodies -------------------------------------------------------------------------

    @Test
    fun `motion control sends the driving video, the orientation and the mode`() = runTest {
        val server = created()
        server.provider().videoModel("kling-v2.6-motion-control")
            .doStart(options(kling = motionControlOptions))

        server.request().assertBodyEquals(
            """
            {
              "model_name": "kling-v2-6",
              "prompt": "$prompt",
              "video_url": "https://example.com/reference-motion.mp4",
              "character_orientation": "image",
              "mode": "std"
            }
            """,
        )
    }

    /**
     * Motion control without the driving video is a request with no subject, and sending it anyway
     * produces a 400 that names none of the three missing fields. So it is refused here instead.
     */
    @Test
    fun `motion control refuses to send a request that has no subject`() = runTest {
        val server = created()
        assertFailsWith<InvalidArgumentError> {
            server.provider().videoModel("kling-v2.6-motion-control")
                .doStart(options(kling = buildJsonObject { }))
        }
        assertFailsWith<InvalidArgumentError> {
            server.provider().videoModel("kling-v2.6-motion-control").doStart(options())
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `text-to-video sends the ratio and the duration Kling wants, as a whole-second string`() =
        runTest {
            val server = created()
            server.provider().videoModel("kling-v2.6-t2v").doStart(
                options(
                    prompt = "A sunset over the ocean",
                    aspectRatio = "16:9",
                    durationInSeconds = 5.0,
                    kling = stdMode,
                ),
            )

            // `5`, not `5.0`: Kling rejects the decimal form.
            server.request().assertBodyEquals(
                """
                {
                  "model_name": "kling-v2-6",
                  "prompt": "A sunset over the ocean",
                  "mode": "std",
                  "aspect_ratio": "16:9",
                  "duration": "5"
                }
                """,
            )
        }

    @Test
    fun `the camelCase video options are renamed to Kling's own`() = runTest {
        val server = created()
        server.provider().videoModel("kling-v2.6-t2v").doStart(
            options(
                kling = buildJsonObject {
                    put("mode", "pro")
                    put("negativePrompt", "blurry")
                    put("sound", true)
                    put("cfgScale", 0.5)
                    put("watermarkEnabled", true)
                },
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "model_name": "kling-v2-6",
              "prompt": "$prompt",
              "mode": "pro",
              "negative_prompt": "blurry",
              "sound": true,
              "cfg_scale": 0.5,
              "watermark_info": { "enabled": true }
            }
            """,
        )
    }

    @Test
    fun `image-to-video sends the start frame as bare base64, never a data URI`() = runTest {
        val fromUrl = created()
        fromUrl.provider().videoModel("kling-v2.6-i2v").doStart(
            options(
                image = VideoFile.Url("https://example.com/start-frame.png"),
                kling = stdMode,
            ),
        )
        fromUrl.request().assertBodyEquals(
            """
            {
              "model_name": "kling-v2-6",
              "prompt": "$prompt",
              "image": "https://example.com/start-frame.png",
              "mode": "std"
            }
            """,
        )

        val fromBytes = created()
        fromBytes.provider().videoModel("kling-v2.6-i2v").doStart(
            options(
                image = VideoFile.Data(
                    com.sabreware.aide.aisdk.BinaryData.Bytes(byteArrayOf(65, 66, 67)),
                    "image/png",
                ),
                kling = stdMode,
            ),
        )
        assertEquals("QUJD", fromBytes.request().bodyJson()["image"].toString().trim('"'))
    }

    /**
     * The output dimensions of an image-to-video generation are decided by the input image. Accepting an
     * aspect ratio there would silently produce something other than what was asked for.
     */
    @Test
    fun `image-to-video refuses an aspect ratio the input image already decides`() = runTest {
        val server = created()
        val result = server.provider().videoModel("kling-v2.6-i2v").doStart(
            options(
                aspectRatio = "16:9",
                image = VideoFile.Url("https://example.com/start-frame.png"),
                kling = stdMode,
            ),
        )!!

        result.warnings.assertUnsupported("aspectRatio")
        assertTrue("aspect_ratio" !in server.request().bodyJson())
    }

    @Test
    fun `text-to-video takes no ratio warning, because it decides its own dimensions`() = runTest {
        val server = created()
        val result = server.provider().videoModel("kling-v2.6-t2v")
            .doStart(options(aspectRatio = "16:9", kling = stdMode))!!

        assertTrue(result.warnings.none { it.toString().contains("aspectRatio") })
    }

    @Test
    fun `the options Kling has no field for each say so`() = runTest {
        val server = created()
        val result = server.provider().videoModel("kling-v2.6-t2v").doStart(
            VideoCallOptions(
                prompt = prompt,
                n = 2,
                resolution = "1920x1080",
                fps = 30,
                seed = 7,
                providerOptions = mapOf("klingai" to stdMode),
            ),
        )!!

        result.warnings.assertUnsupported("n")
        result.warnings.assertUnsupported("resolution")
        result.warnings.assertUnsupported("fps")
        result.warnings.assertUnsupported("seed")
    }

    // --- the operation handle ---------------------------------------------------------------------

    /**
     * The handle is `{taskId, endpointPath}` and not the task id alone: a status query goes back to the
     * endpoint the job was submitted to, so an id by itself cannot be polled once the process that knew
     * which mode was used has gone.
     */
    @Test
    fun `the handle carries the endpoint the job was submitted to, not just its id`() = runTest {
        val cases = listOf(
            "kling-v2.6-t2v" to "/v1/videos/text2video",
            "kling-v2.6-i2v" to "/v1/videos/image2video",
            "kling-v2.6-motion-control" to "/v1/videos/motion-control",
        )
        for ((modelId, path) in cases) {
            val server = created()
            val result = server.provider().videoModel(modelId).doStart(
                options(
                    image = VideoFile.Url("https://example.com/start-frame.png")
                        .takeIf { modelId.endsWith("-i2v") },
                    kling = if (modelId.endsWith("-motion-control")) motionControlOptions else stdMode,
                ),
            )!!
            assertEquals(
                parseJsonObject("""{ "taskId": "task-abc-123", "endpointPath": "$path" }"""),
                result.operation,
                modelId,
            )
        }
    }

    @Test
    fun `a 200 that names no task is a failure rather than an unpollable handle`() = runTest {
        val server = TestServer(TestServer.json(KlingFixtures.NO_TASK_ID))
        assertFailsWith<NoContentGeneratedError> {
            server.provider().videoModel("kling-v2.6-t2v").doStart(options(kling = stdMode))
        }
    }

    // --- doStatus ---------------------------------------------------------------------------------

    private fun handle(path: String = "/v1/videos/text2video"): JsonElement = buildJsonObject {
        put("taskId", "task-abc-123")
        put("endpointPath", path)
    }

    @Test
    fun `the poll goes back to the endpoint the handle names`() = runTest {
        val server = TestServer(TestServer.json(KlingFixtures.TASK_SUCCEEDED))
        server.provider().videoModel("kling-v2.6-i2v").doStatus(handle("/v1/videos/image2video"))

        assertEquals("$baseUrl/v1/videos/image2video/task-abc-123", server.request().url)
        server.request().assertHeader("Authorization", "Bearer test-jwt-token")
    }

    @Test
    fun `succeed is the finished state, and the clip and its watermark both survive`() = runTest {
        val server = TestServer(TestServer.json(KlingFixtures.TASK_SUCCEEDED))
        val status = assertIs<VideoStatusResult.Completed>(
            server.provider().videoModel("kling-v2.6-t2v").doStatus(handle()),
        )

        assertEquals(
            listOf(VideoData.Url("https://p1.a.kwimgs.com/output/video-001.mp4", "video/mp4")),
            status.videos,
        )
        assertEquals(
            parseJsonObject(
                """
                {
                  "taskId": "task-abc-123",
                  "videos": [
                    {
                      "id": "video-001",
                      "url": "https://p1.a.kwimgs.com/output/video-001.mp4",
                      "watermarkUrl": "https://p1.a.kwimgs.com/output/video-001-watermark.mp4",
                      "duration": "5.0"
                    }
                  ]
                }
                """,
            ),
            status.providerMetadata!!["klingai"],
        )
    }

    @Test
    fun `submitted is still-working, not an answer`() = runTest {
        val server = TestServer(TestServer.json(KlingFixtures.TASK_SUBMITTED_STATUS))
        assertIs<VideoStatusResult.Pending>(
            server.provider().videoModel("kling-v2.6-t2v").doStatus(handle()),
        )
    }

    @Test
    fun `a failed task carries Kling's own reason as a status, not as a throw`() = runTest {
        val server = TestServer(TestServer.json(KlingFixtures.TASK_FAILED))
        val status = assertIs<VideoStatusResult.Failed>(
            server.provider().videoModel("kling-v2.6-t2v").doStatus(handle()),
        )
        assertTrue("The reference video is too long" in status.error)
    }

    @Test
    fun `a handle missing either half is refused rather than polled at a guessed URL`() = runTest {
        val server = TestServer(TestServer.json(KlingFixtures.TASK_SUCCEEDED))
        val model = server.provider().videoModel("kling-v2.6-t2v")

        assertFailsWith<NoContentGeneratedError> {
            model.doStatus(buildJsonObject { put("endpointPath", "/v1/videos/text2video") })
        }
        assertFailsWith<NoContentGeneratedError> {
            model.doStatus(buildJsonObject { put("taskId", "task-abc-123") })
        }
    }

    /**
     * The same property the fal and Replicate suites pin. Kling is the case that makes it clearest: the
     * endpoint path lives only in the handle, so a caller who serialized the id alone could never poll.
     */
    @Test
    fun `a handle survives being serialized, stored and polled by a different caller`() = runTest {
        val starting = created()
        val started = starting.provider().videoModel("kling-v2.6-i2v").doStart(
            options(image = VideoFile.Url("https://example.com/start-frame.png"), kling = stdMode),
        )!!

        val restored = Json.parseToJsonElement(
            Json.encodeToString(JsonElement.serializer(), started.operation),
        )

        val collecting = TestServer(TestServer.json(KlingFixtures.TASK_SUCCEEDED))
        val status = assertIs<VideoStatusResult.Completed>(
            collecting.provider().videoModel("kling-v2.6-i2v").doStatus(restored),
        )

        assertEquals(
            listOf(VideoData.Url("https://p1.a.kwimgs.com/output/video-001.mp4", "video/mp4")),
            status.videos,
        )
        assertEquals("$baseUrl/v1/videos/image2video/task-abc-123", collecting.request().url)
    }

    @Test
    fun `Kling renders one clip per call`() = runTest {
        assertEquals(1, created().provider().videoModel("kling-v2.6-t2v").maxVideosPerCall())
    }
}
