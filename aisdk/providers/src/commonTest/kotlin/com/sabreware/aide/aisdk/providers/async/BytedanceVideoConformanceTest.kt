package com.sabreware.aide.aisdk.providers.async

import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoFile
import com.sabreware.aide.aisdk.VideoFrameImage
import com.sabreware.aide.aisdk.VideoFrameType
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.providers.bytedance.BytedanceProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * ByteDance Seedance against `bytedance-video-model.test.ts`.
 *
 * The request is a content ARRAY whose `role` fields carry the semantics, and the role rules are the
 * part worth pinning: a start image is role-less until a last frame makes it ambiguous, and a URL
 * reference with no media type is routed as an IMAGE — both silent wrong-video failures if drifted.
 */
class BytedanceVideoConformanceTest {

    private val baseUrl = "https://ark.ap-southeast.bytepluses.com/api/v3"

    private fun TestServer.provider() = BytedanceProvider(
        client = HttpClient(engine()),
        apiKey = "test-key",
    )

    private fun created() = TestServer(TestServer.json("""{"id":"task-1"}"""))

    private val operation = buildJsonObject { put("taskId", "task-1") }

    @Test
    fun `the submit goes to the tasks endpoint with bearer auth and the prompt as a content entry`() = runTest {
        val server = created()
        server.provider().videoModel("seedance-1-5-pro-251215").doStart(
            VideoCallOptions(prompt = "a fox in the snow", aspectRatio = "16:9", seed = 42),
            webhookUrl = null,
        )

        val call = server.request()
        assertEquals("$baseUrl/contents/generations/tasks", call.url)
        assertEquals("Bearer test-key", call.header("Authorization"))
        val body = call.bodyJson()
        assertEquals("seedance-1-5-pro-251215", body["model"].string())
        assertEquals("16:9", body["ratio"].string())
        assertEquals("42", body.getValue("seed").jsonPrimitive.content)
        val text = body.getValue("content").jsonArray.first().jsonObject
        assertEquals("text", text["type"].string())
        assertEquals("a fox in the snow", text["text"].string())
    }

    @Test
    fun `a lone start image carries no role, and gains first_frame only when a last frame joins it`() = runTest {
        val lone = created()
        lone.provider().videoModel("seedance-1-5-pro-251215").doStart(
            VideoCallOptions(
                prompt = "animate",
                image = VideoFile.Url("https://example.com/a.png", mediaType = "image/png"),
            ),
            webhookUrl = null,
        )
        val loneImage = lone.request().bodyJson().getValue("content").jsonArray[1].jsonObject
        assertNull(loneImage["role"], "a lone start image must not narrow the mode with a role")

        val paired = created()
        paired.provider().videoModel("seedance-1-5-pro-251215").doStart(
            VideoCallOptions(
                prompt = "animate",
                image = VideoFile.Url("https://example.com/a.png", mediaType = "image/png"),
                frameImages = listOf(
                    VideoFrameImage(
                        VideoFile.Url("https://example.com/z.png", mediaType = "image/png"),
                        VideoFrameType.LastFrame,
                    ),
                ),
            ),
            webhookUrl = null,
        )
        val content = paired.request().bodyJson().getValue("content").jsonArray
        assertEquals("first_frame", content[1].jsonObject["role"].string())
        assertEquals("last_frame", content[2].jsonObject["role"].string())
    }

    @Test
    fun `references route by media type, and a bare URL is demoted to an image with a warning`() = runTest {
        val server = created()
        val start = server.provider().videoModel("seedance-1-5-pro-251215").doStart(
            VideoCallOptions(
                prompt = "in this style",
                references = listOf(
                    VideoFile.Url("https://example.com/style.mp4", mediaType = "video/mp4"),
                    VideoFile.Url("https://example.com/whatisthis"),
                ),
            ),
            webhookUrl = null,
        )!!

        val content = server.request().bodyJson().getValue("content").jsonArray
        val video = content[1].jsonObject
        assertEquals("video_url", video["type"].string())
        assertEquals("reference_video", video["role"].string())
        val demoted = content[2].jsonObject
        assertEquals("image_url", demoted["type"].string())
        assertEquals("reference_image", demoted["role"].string())
        start.warnings.assertUnsupported("references")
    }

    @Test
    fun `a WxH resolution maps onto the documented tier, and an unlisted one passes through`() = runTest {
        val mapped = created()
        mapped.provider().videoModel("seedance-1-5-pro-251215").doStart(
            VideoCallOptions(prompt = "x", resolution = "1920x1080"),
            webhookUrl = null,
        )
        assertEquals("1080p", mapped.request().bodyJson()["resolution"].string())

        val passthrough = created()
        passthrough.provider().videoModel("seedance-1-5-pro-251215").doStart(
            VideoCallOptions(prompt = "x", resolution = "999x111"),
            webhookUrl = null,
        )
        assertEquals("999x111", passthrough.request().bodyJson()["resolution"].string())
    }

    @Test
    fun `vendor options map to their snake_case fields and unknown ones pass through`() = runTest {
        val server = created()
        server.provider().videoModel("seedance-1-5-pro-251215").doStart(
            VideoCallOptions(
                prompt = "x",
                generateAudio = true,
                providerOptions = mapOf(
                    "bytedance" to buildJsonObject {
                        put("cameraFixed", true)
                        put("serviceTier", "flex")
                        put("someFutureKnob", "kept")
                    },
                ),
            ),
            webhookUrl = null,
        )

        val body = server.request().bodyJson()
        assertEquals("true", body.getValue("generate_audio").jsonPrimitive.content)
        assertEquals("true", body.getValue("camera_fixed").jsonPrimitive.content)
        assertEquals("flex", body["service_tier"].string())
        assertEquals("kept", body["someFutureKnob"].string())
    }

    @Test
    fun `succeeded delivers the URL with usage and last frame in the metadata`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"id":"task-1","status":"succeeded","content":{"video_url":"https://cdn.example.com/clip.mp4","last_frame_url":"https://cdn.example.com/last.png"},"usage":{"completion_tokens":123}}""",
            ),
        )
        val status = server.provider().videoModel("seedance-1-5-pro-251215")
            .doStatus(operation, headers = null)

        val completed = assertIs<VideoStatusResult.Completed>(status)
        assertEquals("$baseUrl/contents/generations/tasks/task-1", server.request().url)
        assertEquals(
            listOf(VideoData.Url("https://cdn.example.com/clip.mp4", "video/mp4")),
            completed.videos,
        )
        val metadata = completed.providerMetadata!!["bytedance"]!!.jsonObject
        assertEquals("https://cdn.example.com/last.png", metadata["lastFrameUrl"].string())
        assertEquals(
            "123",
            metadata.getValue("usage").jsonObject.getValue("completion_tokens").jsonPrimitive.content,
        )
    }

    @Test
    fun `a failed task surfaces the body's error message, not just the status`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"id":"task-1","status":"failed","error":{"code":"ContentPolicy","message":"prompt rejected"}}""",
            ),
        )
        val status = server.provider().videoModel("seedance-1-5-pro-251215")
            .doStatus(operation, headers = null)

        val failed = assertIs<VideoStatusResult.Failed>(status)
        assertTrue("prompt rejected" in failed.error, failed.error)
    }

    @Test
    fun `queued and running keep polling`() = runTest {
        for (state in listOf("queued", "running")) {
            val server = TestServer(TestServer.json("""{"id":"task-1","status":"$state"}"""))
            val status = server.provider().videoModel("seedance-1-5-pro-251215")
                .doStatus(operation, headers = null)
            assertIs<VideoStatusResult.Pending>(status)
        }
    }
}
