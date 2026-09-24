package com.sabreware.aide.aisdk.providers.async

import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoFile
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.providers.minimax.MiniMaxProvider
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
 * MiniMax Hailuo video against `minimax-video-model.test.ts`.
 *
 * The deliberate divergence under test everywhere here: the reference polls INSIDE `doGenerate`; this
 * port serves the `doStart`/`doStatus` pair, so the task id is a persistable handle. The wire per call
 * stays byte-identical to the reference's — only who drives the loop moved.
 */
class MiniMaxVideoConformanceTest {

    private val baseUrl = "https://api.minimax.io"

    private fun TestServer.provider() = MiniMaxProvider(
        client = HttpClient(engine()),
        apiKey = "test-key",
    )

    private fun created() = TestServer(TestServer.json("""{"task_id":"task-9"}"""))

    private val operation = buildJsonObject { put("taskId", "task-9") }

    @Test
    fun `the submit uses BEARER auth on the video surface, unlike the chat endpoint`() = runTest {
        val server = created()
        server.provider().videoModel("MiniMax-H3").doStart(
            VideoCallOptions(prompt = "a fox"),
            webhookUrl = null,
        )

        val call = server.request()
        assertEquals("$baseUrl/v2/video_generation", call.url)
        // The same key the chat surface takes as `x-api-key`; each endpoint rejects the other's spelling.
        assertEquals("Bearer test-key", call.header("Authorization"))
        assertNull(call.header("x-api-key"))
    }

    @Test
    fun `text-to-video fills the defaults the API needs stated`() = runTest {
        val server = created()
        server.provider().videoModel("MiniMax-H3").doStart(
            VideoCallOptions(prompt = "a fox"),
            webhookUrl = null,
        )

        val body = server.request().bodyJson()
        assertEquals("MiniMax-H3", body["model"].string())
        assertEquals("2K", body["resolution"].string())
        assertEquals(5, body.getValue("duration").jsonPrimitive.int)
        assertEquals("16:9", body["ratio"].string())
        val text = body.getValue("content").jsonArray.first().jsonObject
        assertEquals("a fox", text["text"].string())
    }

    @Test
    fun `H3-Max serves a different resolution menu and defaults to 768P`() = runTest {
        val server = created()
        val start = server.provider().videoModel("MiniMax-H3-Max").doStart(
            VideoCallOptions(prompt = "x", resolution = "2048x2048"),
            webhookUrl = null,
        )!!

        // 2048x2048 maps to 2K, which H3-Max does not serve — the menu default steps in, with a warning.
        start.warnings.assertUnsupported("resolution")
        assertEquals("768P", server.request().bodyJson()["resolution"].string())
    }

    @Test
    fun `duration is rounded, then clamped to the model's own floor`() = runTest {
        val server = created()
        val start = server.provider().videoModel("MiniMax-H3").doStart(
            VideoCallOptions(prompt = "x", durationInSeconds = 2.4),
            webhookUrl = null,
        )!!

        // Rounded 2.4 → 2, then clamped to H3's floor of 4 (H3-Max would clamp to 5).
        assertEquals(4, server.request().bodyJson().getValue("duration").jsonPrimitive.int)
        assertTrue(start.warnings.size >= 2, "expected both a rounding and a clamping warning")
    }

    @Test
    fun `H3-Max refuses reference inputs outright`() = runTest {
        val server = created()
        val start = server.provider().videoModel("MiniMax-H3-Max").doStart(
            VideoCallOptions(
                prompt = "x",
                references = listOf(VideoFile.Url("https://example.com/ref.png", mediaType = "image/png")),
            ),
            webhookUrl = null,
        )!!

        start.warnings.assertUnsupported("references")
        // Nothing but the text entry made it into the content array.
        assertEquals(1, server.request().bodyJson().getValue("content").jsonArray.size)
    }

    @Test
    fun `the inputs that survived are reported as resolvedInputs on the START result`() = runTest {
        val server = created()
        val start = server.provider().videoModel("MiniMax-H3").doStart(
            VideoCallOptions(
                prompt = "in this style",
                references = listOf(
                    VideoFile.Url("https://example.com/a.png", mediaType = "image/png"),
                    VideoFile.Url("https://example.com/b.mp4", mediaType = "video/mp4"),
                ),
            ),
            webhookUrl = null,
        )!!

        val resolved = start.providerMetadata!!["minimax"]!!
            .jsonObject.getValue("resolvedInputs").jsonObject
        assertEquals(1, resolved.getValue("imageCount").jsonPrimitive.int)
        assertEquals(
            listOf("https://example.com/b.mp4"),
            resolved.getValue("referenceVideoUrls").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `succeeded delivers the URL with the task's usage in the metadata`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"task":{"id":"task-9","status":"succeeded","content":{"url":"https://cdn.minimax.io/clip.mp4"},"resolution":"2K","duration":5,"ratio":"16:9","usage":{"total_seconds":5,"input_seconds":0,"output_seconds":5}}}""",
            ),
        )
        val status = server.provider().videoModel("MiniMax-H3")
            .doStatus(operation, headers = null)

        val completed = assertIs<VideoStatusResult.Completed>(status)
        assertEquals("$baseUrl/v2/query/video_generation/task-9", server.request().url)
        assertEquals(
            listOf(VideoData.Url("https://cdn.minimax.io/clip.mp4", "video/mp4")),
            completed.videos,
        )
        val usage = completed.providerMetadata!!["minimax"]!!
            .jsonObject.getValue("usage").jsonObject
        assertEquals(5, usage.getValue("totalSeconds").jsonPrimitive.int)
    }

    @Test
    fun `failed, cancelled and expired are answers, not retries`() = runTest {
        for (state in listOf("failed", "cancelled", "expired")) {
            val server = TestServer(
                TestServer.json(
                    """{"task":{"id":"task-9","status":"$state","error":{"code":1027,"message":"content policy"}}}""",
                ),
            )
            val status = server.provider().videoModel("MiniMax-H3")
                .doStatus(operation, headers = null)
            val failed = assertIs<VideoStatusResult.Failed>(status)
            assertTrue(state in failed.error, failed.error)
            assertTrue("content policy" in failed.error, failed.error)
        }
    }

    @Test
    fun `queued and running keep polling`() = runTest {
        for (state in listOf("queued", "running")) {
            val server = TestServer(TestServer.json("""{"task":{"id":"task-9","status":"$state"}}"""))
            val status = server.provider().videoModel("MiniMax-H3")
                .doStatus(operation, headers = null)
            assertIs<VideoStatusResult.Pending>(status)
        }
    }
}
