package com.sabreware.aide.aisdk.providers.xai

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoFile
import com.sabreware.aide.aisdk.VideoFrameImage
import com.sabreware.aide.aisdk.VideoFrameType
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.put

/**
 * xAI video generation, pinned against `xai-video-model.ts`.
 *
 * The endpoint choice is the load-bearing assertion: xAI accepts an edit body at the generations
 * endpoint and answers with a video generated from the prompt alone, so a wrong URL is not a failure a
 * caller sees — it is a different video.
 */
class XaiVideoModelTest {

    private fun xai(server: TestServer) = XaiProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-api-key",
    )

    private fun started() = TestServer(TestServer.json("""{"request_id":"req-123"}"""))

    private fun vendor(build: JsonObjectBuilder.() -> Unit) =
        mapOf(XAI_PROVIDER_ID to buildJsonObject(build))

    private fun handle(id: String = "req-123"): JsonElement = buildJsonObject { put("requestId", id) }

    @Test
    fun `a plain generation posts to videos generations`() = runTest {
        val server = started()

        val result = xai(server).videoModel("grok-video-1")
            .doStart(VideoCallOptions(prompt = "a cat", aspectRatio = "16:9", durationInSeconds = 6.0))

        val call = server.request()
        assertEquals("v1/videos/generations", call.path)
        assertEquals("grok-video-1", call.bodyJson()["model"].string())
        assertEquals("a cat", call.bodyJson()["prompt"].string())
        assertEquals("16:9", call.bodyJson()["aspect_ratio"].string())
        // The handle is what a caller persists; without it, resuming after a restart is impossible.
        assertEquals(handle(), result?.operation)
    }

    @Test
    fun `an edit posts to videos edits and carries the source clip`() = runTest {
        val server = started()

        xai(server).videoModel("grok-video-1").doStart(
            VideoCallOptions(
                prompt = "make it rain",
                providerOptions = vendor {
                    put("mode", "edit-video")
                    put("videoUrl", "https://cdn.example.com/clip.mp4")
                },
            ),
        )

        val call = server.request()
        assertEquals("v1/videos/edits", call.path)
        assertEquals(
            "https://cdn.example.com/clip.mp4",
            call.bodyJson()["video"].let { (it as kotlinx.serialization.json.JsonObject)["url"] }.string(),
        )
    }

    @Test
    fun `an extension posts to videos extensions`() = runTest {
        val server = started()

        xai(server).videoModel("grok-video-1").doStart(
            VideoCallOptions(
                prompt = "keep going",
                providerOptions = vendor {
                    put("mode", "extend-video")
                    put("videoUrl", "https://cdn.example.com/clip.mp4")
                },
            ),
        )

        assertEquals("v1/videos/extensions", server.request().path)
    }

    @Test
    fun `an edit that sets duration is warned about rather than silently ignored`() = runTest {
        val server = started()

        val result = xai(server).videoModel("grok-video-1").doStart(
            VideoCallOptions(
                prompt = "make it rain",
                durationInSeconds = 12.0,
                aspectRatio = "1:1",
                providerOptions = vendor {
                    put("mode", "edit-video")
                    put("videoUrl", "https://cdn.example.com/clip.mp4")
                },
            ),
        )

        // xAI refuses these by IGNORING them, so the clip comes back at the source length with nothing
        // to explain why the request looked honoured.
        val features = result!!.warnings.filterIsInstance<Warning.Unsupported>().map { it.feature }
        assertTrue("duration" in features)
        assertTrue("aspectRatio" in features)
        assertNull(server.request().bodyJson()["duration"])
        assertNull(server.request().bodyJson()["aspect_ratio"])
    }

    @Test
    fun `a pixel resolution is translated and an unknown one warns`() = runTest {
        val translated = started()
        xai(translated).videoModel("grok-video-1")
            .doStart(VideoCallOptions(prompt = "x", resolution = "1280x720"))
        assertEquals("720p", translated.request().bodyJson()["resolution"].string())

        val unknown = started()
        val result = xai(unknown).videoModel("grok-video-1")
            .doStart(VideoCallOptions(prompt = "x", resolution = "1234x567"))
        assertNull(unknown.request().bodyJson()["resolution"])
        assertTrue(
            result!!.warnings.filterIsInstance<Warning.Unsupported>().any { it.feature == "resolution" },
        )
    }

    @Test
    fun `a video handed to the start frame is refused rather than decoded as a still`() = runTest {
        val server = started()

        val result = xai(server).videoModel("grok-video-1").doStart(
            VideoCallOptions(
                prompt = "x",
                image = VideoFile.Url("https://cdn.example.com/clip.mp4", mediaType = "video/mp4"),
            ),
        )

        // Sent, xAI would read one frame of it and the caller's intent — continue this video — is lost.
        assertNull(server.request().bodyJson()["image"])
        assertTrue(result!!.warnings.filterIsInstance<Warning.Unsupported>().any { it.feature == "image" })
    }

    @Test
    fun `a last frame is warned about and points at the mode that does it`() = runTest {
        val server = started()

        val result = xai(server).videoModel("grok-video-1").doStart(
            VideoCallOptions(
                prompt = "x",
                frameImages = listOf(
                    VideoFrameImage(
                        VideoFile.Data(BinaryData.Base64("aGk="), "image/png"),
                        VideoFrameType.LastFrame,
                    ),
                ),
            ),
        )

        val warning = result!!.warnings.filterIsInstance<Warning.Unsupported>()
            .single { it.feature == "frameImages" }
        assertTrue(warning.details!!.contains("extend-video"))
    }

    @Test
    fun `a finished job reports the clip and its cost`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"status":"done","video":{"url":"https://cdn.x.ai/v.mp4","duration":6},
                   "usage":{"cost_in_usd_ticks":42},"progress":100}""",
            ),
        )

        val status = xai(server).videoModel("grok-video-1").doStatus(handle())

        val completed = assertIs<VideoStatusResult.Completed>(status)
        assertEquals(VideoData.Url("https://cdn.x.ai/v.mp4", "video/mp4"), completed.videos.single())
        assertEquals("v1/videos/req-123", server.request().path)
        val metadata = completed.providerMetadata!!.getValue(XAI_PROVIDER_ID)
        assertEquals("req-123", metadata["requestId"].string())
        assertEquals("42", metadata["costInUsdTicks"].toString())
    }

    @Test
    fun `a moderated result is a refusal, not an empty success`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"status":"done","video":{"url":"https://cdn.x.ai/v.mp4","respect_moderation":false}}""",
            ),
        )

        val status = xai(server).videoModel("grok-video-1").doStatus(handle())

        assertTrue(assertIs<VideoStatusResult.Failed>(status).error.contains("content policy"))
    }

    @Test
    fun `an expired job and a failed job are told apart`() = runTest {
        val expired = TestServer(TestServer.json("""{"status":"expired"}"""))
        assertTrue(
            assertIs<VideoStatusResult.Failed>(
                xai(expired).videoModel("m").doStatus(handle()),
            ).error.contains("expired"),
        )

        val failed = TestServer(
            TestServer.json("""{"status":"failed","error":{"message":"prompt rejected"}}"""),
        )
        assertTrue(
            assertIs<VideoStatusResult.Failed>(
                xai(failed).videoModel("m").doStatus(handle()),
            ).error.contains("prompt rejected"),
        )
    }

    @Test
    fun `a status with no video is still pending`() = runTest {
        val server = TestServer(TestServer.json("""{"status":"processing","progress":30}"""))

        assertIs<VideoStatusResult.Pending>(xai(server).videoModel("m").doStatus(handle()))
    }

    @Test
    fun `the older shape - a video with no status - counts as done`() = runTest {
        // Treating a missing status as "still running" would poll a finished job until the caller's
        // budget ran out.
        val server = TestServer(TestServer.json("""{"video":{"url":"https://cdn.x.ai/v.mp4"}}"""))

        assertIs<VideoStatusResult.Completed>(xai(server).videoModel("m").doStatus(handle()))
    }

    @Test
    fun `a request id is percent-encoded into the path`() = runTest {
        val server = TestServer(TestServer.json("""{"status":"processing"}"""))

        xai(server).videoModel("m").doStatus(handle("../admin"))

        // A vendor-supplied id that rewrote the URL it was placed into would reach another endpoint.
        assertTrue(server.request().path.startsWith("v1/videos/"))
        assertTrue(!server.request().path.contains("/admin"))
    }

    @Test
    fun `references are sent as image urls and a video reference is dropped with a warning`() = runTest {
        val server = started()

        val result = xai(server).videoModel("grok-video-1").doStart(
            VideoCallOptions(
                prompt = "x",
                references = listOf(
                    VideoFile.Url("https://cdn.example.com/a.png", mediaType = "image/png"),
                    VideoFile.Url("https://cdn.example.com/b.mp4", mediaType = "video/mp4"),
                ),
                providerOptions = vendor { put("mode", "reference-to-video") },
            ),
        )

        val references = server.request().bodyJson()["reference_images"]
        assertTrue(references.toString().contains("a.png"))
        assertTrue(!references.toString().contains("b.mp4"))
        assertTrue(
            result!!.warnings.filterIsInstance<Warning.Unsupported>().any { it.feature == "references" },
        )
    }
}
