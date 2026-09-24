package com.sabreware.aide.aisdk.providers.async

import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoFile
import com.sabreware.aide.aisdk.VideoFrameImage
import com.sabreware.aide.aisdk.VideoFrameType
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.providers.alibaba.AlibabaProvider
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Alibaba Wan video against `alibaba-video-model.test.ts`.
 *
 * Three generations of one API with moved field names, and DashScope IGNORES fields it does not know —
 * a wan2.6 body sent to wan3 generates from the prompt alone rather than failing. So the per-protocol
 * body shapes are what gets pinned: `size` vs `resolution`, `img_url` vs `media`, the async header.
 */
class AlibabaVideoConformanceTest {

    private val baseUrl = "https://dashscope-intl.aliyuncs.com/api/v1"

    private fun TestServer.provider() = AlibabaProvider(
        client = HttpClient(engine()),
        apiKey = "test-key",
    )

    private fun created() = TestServer(
        TestServer.json("""{"output":{"task_status":"PENDING","task_id":"task-5"},"request_id":"r-1"}"""),
    )

    private val operation = buildJsonObject { put("taskId", "task-5") }

    @Test
    fun `the submit is async only because of the X-DashScope-Async header`() = runTest {
        val server = created()
        server.provider().videoModel("wan2.6-t2v").doStart(
            VideoCallOptions(prompt = "a fox"),
            webhookUrl = null,
        )

        val call = server.request()
        assertEquals("$baseUrl/services/aigc/video-generation/video-synthesis", call.url)
        assertEquals("enable", call.header("X-DashScope-Async"))
        assertEquals("Bearer test-key", call.header("Authorization"))
    }

    @Test
    fun `wan2_6 text-to-video takes size as WIDTH-star-HEIGHT`() = runTest {
        val server = created()
        server.provider().videoModel("wan2.6-t2v").doStart(
            VideoCallOptions(prompt = "a fox", resolution = "1280x720", seed = 7),
            webhookUrl = null,
        )

        val body = server.request().bodyJson()
        val parameters = body.getValue("parameters").jsonObject
        // The SDK's `x` becomes the API's `*` — sending the `x` form is silently ignored, not rejected.
        assertEquals("1280*720", parameters["size"].string())
        assertNull(parameters["resolution"])
        assertEquals(7, parameters.getValue("seed").jsonPrimitive.int)
    }

    @Test
    fun `wan2_6 image-to-video takes img_url and a resolution tier instead`() = runTest {
        val server = created()
        server.provider().videoModel("wan2.6-i2v").doStart(
            VideoCallOptions(
                prompt = "animate",
                image = VideoFile.Url("https://example.com/a.png", mediaType = "image/png"),
                resolution = "1280x720",
            ),
            webhookUrl = null,
        )

        val body = server.request().bodyJson()
        assertEquals("https://example.com/a.png", body.getValue("input").jsonObject["img_url"].string())
        assertEquals("720P", body.getValue("parameters").jsonObject["resolution"].string())
    }

    @Test
    fun `wan3 carries frames in input media and takes an audio toggle`() = runTest {
        val server = created()
        server.provider().videoModel("wan3.0-video").doStart(
            VideoCallOptions(
                prompt = "animate",
                image = VideoFile.Url("https://example.com/first.png", mediaType = "image/png"),
                frameImages = listOf(
                    VideoFrameImage(
                        VideoFile.Url("https://example.com/last.png", mediaType = "image/png"),
                        VideoFrameType.LastFrame,
                    ),
                ),
                generateAudio = false,
            ),
            webhookUrl = null,
        )

        val body = server.request().bodyJson()
        val media = body.getValue("input").jsonObject.getValue("media").jsonArray
        assertEquals("first_frame", media[0].jsonObject["type"].string())
        assertEquals("https://example.com/first.png", media[0].jsonObject["url"].string())
        assertEquals("last_frame", media[1].jsonObject["type"].string())
        assertNull(body.getValue("input").jsonObject["img_url"])
        assertEquals("false", body.getValue("parameters").jsonObject.getValue("audio").jsonPrimitive.content)
    }

    @Test
    fun `wan2_7 dropped shot_type, and the option warns instead of riding along`() = runTest {
        val server = created()
        val start = server.provider().videoModel("wan2.7-t2v").doStart(
            VideoCallOptions(
                prompt = "x",
                aspectRatio = "16:9",
                providerOptions = mapOf(
                    "alibaba" to buildJsonObject { put("shotType", "multi") },
                ),
            ),
            webhookUrl = null,
        )!!

        start.warnings.assertUnsupported("shotType")
        val parameters = server.request().bodyJson().getValue("parameters").jsonObject
        assertNull(parameters["shot_type"])
        assertEquals("16:9", parameters["ratio"].string())
    }

    @Test
    fun `wan2_6 reference-to-video takes reference_urls, URLs only`() = runTest {
        val server = created()
        val start = server.provider().videoModel("wan2.6-r2v").doStart(
            VideoCallOptions(
                prompt = "with character1",
                references = listOf(
                    VideoFile.Url("https://example.com/char.png", mediaType = "image/png"),
                    VideoFile.Data(
                        data = com.sabreware.aide.aisdk.BinaryData.Base64("aGk="),
                        mediaType = "video/mp4",
                    ),
                ),
            ),
            webhookUrl = null,
        )!!

        val input = server.request().bodyJson().getValue("input").jsonObject
        assertEquals(
            listOf("https://example.com/char.png"),
            input.getValue("reference_urls").jsonArray.map { it.jsonPrimitive.content },
        )
        start.warnings.assertUnsupported("references")
    }

    @Test
    fun `SUCCEEDED delivers the URL with the usage block reshaped into the metadata`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"output":{"task_id":"task-5","task_status":"SUCCEEDED","video_url":"https://cdn.example.com/clip.mp4","actual_prompt":"a very detailed fox"},"usage":{"duration":5,"output_video_duration":5,"SR":720,"size":"1280*720","fps":24},"request_id":"r-2"}""",
            ),
        )
        val status = server.provider().videoModel("wan2.6-t2v")
            .doStatus(operation, headers = null)

        val completed = assertIs<VideoStatusResult.Completed>(status)
        assertEquals("$baseUrl/tasks/task-5", server.request().url)
        assertEquals(
            listOf(VideoData.Url("https://cdn.example.com/clip.mp4", "video/mp4")),
            completed.videos,
        )
        val metadata = completed.providerMetadata!!["alibaba"]!!.jsonObject
        assertEquals("a very detailed fox", metadata["actualPrompt"].string())
        val usage = metadata.getValue("usage").jsonObject
        assertEquals(720, usage.getValue("resolution").jsonPrimitive.int)
        assertEquals(24, usage.getValue("fps").jsonPrimitive.int)
    }

    @Test
    fun `FAILED and CANCELED surface the task's own message`() = runTest {
        for (state in listOf("FAILED", "CANCELED")) {
            val server = TestServer(
                TestServer.json(
                    """{"output":{"task_id":"task-5","task_status":"$state","code":"InvalidParameter","message":"prompt too long"}}""",
                ),
            )
            val status = server.provider().videoModel("wan2.6-t2v")
                .doStatus(operation, headers = null)
            val failed = assertIs<VideoStatusResult.Failed>(status)
            assertTrue("prompt too long" in failed.error, failed.error)
        }
    }

    @Test
    fun `PENDING and RUNNING keep polling`() = runTest {
        for (state in listOf("PENDING", "RUNNING")) {
            val server = TestServer(
                TestServer.json("""{"output":{"task_id":"task-5","task_status":"$state"}}"""),
            )
            val status = server.provider().videoModel("wan2.6-t2v")
                .doStatus(operation, headers = null)
            assertIs<VideoStatusResult.Pending>(status)
        }
    }
}
