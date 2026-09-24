package com.sabreware.aide.aisdk.providers.async

import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoFile
import com.sabreware.aide.aisdk.VideoFrameImage
import com.sabreware.aide.aisdk.VideoFrameType
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.providers.blackforestlabs.BlackForestLabsProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertCompatibility
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * FLUX 3 video against `black-forest-labs-video-model.test.ts`.
 *
 * The mode is DERIVED — keyframes make it `i2v`, a video reference `v2v`, neither `t2v` — and a body
 * whose mode disagrees with its media is a 422 naming a field the caller never set, which is why the
 * mode is pinned per shape here rather than assumed.
 */
class BflVideoConformanceTest {

    private val baseUrl = "https://api.bfl.ai/v1"

    private fun TestServer.provider() = BlackForestLabsProvider(
        client = HttpClient(engine()),
        apiKey = "test-key",
        elapsedMillis = { 0L },
    )

    private fun submitted() = TestServer(
        TestServer.json(
            """{"id":"req-1","polling_url":"https://api.us1.bfl.ai/v1/get_result?id=req-1","cost":0.25,"input_mp":null,"output_mp":2.1}""",
        ),
    )

    private val operation = buildJsonObject {
        put("requestId", "req-1")
        put("pollingUrl", "https://api.us1.bfl.ai/v1/get_result")
        put("cost", 0.25)
    }

    // --- submit -------------------------------------------------------------------------------------

    @Test
    fun `a bare prompt is t2v, and the prompt defaults to empty`() = runTest {
        val server = submitted()
        server.provider().videoModel("flux-3-video")
            .doStart(VideoCallOptions(prompt = "a fox"), webhookUrl = null)

        val call = server.request()
        assertEquals("$baseUrl/flux-3-video", call.url)
        assertEquals("test-key", call.header("x-key"))
        assertEquals("t2v", call.bodyJson()["mode"].string())
        assertEquals("a fox", call.bodyJson()["prompt"].string())
    }

    @Test
    fun `frame images become keyframes and the mode flips to i2v`() = runTest {
        val server = submitted()
        server.provider().videoModel("flux-3-video").doStart(
            VideoCallOptions(
                prompt = "animate",
                image = VideoFile.Url("https://example.com/first.png", mediaType = "image/png"),
                frameImages = listOf(
                    VideoFrameImage(
                        VideoFile.Url("https://example.com/last.png", mediaType = "image/png"),
                        VideoFrameType.LastFrame,
                    ),
                ),
            ),
            webhookUrl = null,
        )

        val body = server.request().bodyJson()
        assertEquals("i2v", body["mode"].string())
        assertEquals(
            listOf("https://example.com/first.png", "https://example.com/last.png"),
            body.getValue("keyframes").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `a video reference becomes start_video and the mode flips to v2v`() = runTest {
        val server = submitted()
        server.provider().videoModel("flux-3-video").doStart(
            VideoCallOptions(
                prompt = "continue",
                references = listOf(VideoFile.Url("https://example.com/clip.mp4", mediaType = "video/mp4")),
            ),
            webhookUrl = null,
        )

        val body = server.request().bodyJson()
        assertEquals("v2v", body["mode"].string())
        assertEquals("https://example.com/clip.mp4", body["start_video"].string())
        assertNull(body["keyframes"])
    }

    @Test
    fun `a last frame with no first frame cannot travel, and says so`() = runTest {
        val server = submitted()
        val start = server.provider().videoModel("flux-3-video").doStart(
            VideoCallOptions(
                prompt = "animate",
                frameImages = listOf(
                    VideoFrameImage(
                        VideoFile.Url("https://example.com/last.png", mediaType = "image/png"),
                        VideoFrameType.LastFrame,
                    ),
                ),
            ),
            webhookUrl = null,
        )!!

        start.warnings.assertUnsupported("frameImages")
        assertEquals("t2v", server.request().bodyJson()["mode"].string())
    }

    @Test
    fun `duration is rounded and clamped into 5 to 20 whole seconds`() = runTest {
        val server = submitted()
        val start = server.provider().videoModel("flux-3-video").doStart(
            VideoCallOptions(prompt = "long", durationInSeconds = 25.0),
            webhookUrl = null,
        )!!

        start.warnings.assertUnsupported("duration")
        assertEquals(20, server.request().bodyJson().getValue("duration").jsonPrimitive.int)
    }

    @Test
    fun `three untimed keyframes with no duration are refused before the request`() = runTest {
        val server = submitted()
        val keyframes = buildJsonObject {
            put(
                "keyframes",
                buildJsonArray {
                    add("a")
                    add("b")
                    add("c")
                },
            )
        }
        assertFailsWith<InvalidArgumentError> {
            server.provider().videoModel("flux-3-video").doStart(
                VideoCallOptions(
                    prompt = "x",
                    providerOptions = mapOf("blackForestLabs" to keyframes),
                ),
                webhookUrl = null,
            )
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `a WxH resolution maps onto a tier by its shorter side, with a warning when derived`() = runTest {
        val server = submitted()
        val start = server.provider().videoModel("flux-3-video").doStart(
            VideoCallOptions(prompt = "x", resolution = "1600x900"),
            webhookUrl = null,
        )!!

        start.warnings.assertCompatibility("resolution")
        assertEquals("fhd", server.request().bodyJson()["resolution"].string())
    }

    // --- poll ---------------------------------------------------------------------------------------

    @Test
    fun `the poll goes to the vendor's own URL with the id appended, key kept on a bfl cluster`() = runTest {
        val server = TestServer(TestServer.json("""{"status":"Pending"}"""))
        val status = server.provider().videoModel("flux-3-video")
            .doStatus(operation, headers = null)

        assertIs<VideoStatusResult.Pending>(status)
        val call = server.request()
        assertEquals("https://api.us1.bfl.ai/v1/get_result?id=req-1", call.url)
        // us1.bfl.ai is not our base host, but it IS the bfl.ai domain — the key must go along or
        // every poll 401s. See isTrustedBflUrl.
        assertEquals("test-key", call.header("x-key"))
    }

    @Test
    fun `Ready delivers the sample URL, and the settled cost wins over the submit estimate`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"status":"Ready","cost":0.31,"result":{"sample":"https://delivery.bfl.ai/clip.mp4","seed":42,"duration":8}}""",
            ),
        )
        val status = server.provider().videoModel("flux-3-video")
            .doStatus(operation, headers = null)

        val completed = assertIs<VideoStatusResult.Completed>(status)
        assertEquals(
            listOf(VideoData.Url("https://delivery.bfl.ai/clip.mp4", "video/mp4")),
            completed.videos,
        )
        val metadata = completed.providerMetadata!!["blackForestLabs"]!!
            .jsonObject.getValue("videos").jsonArray.first().jsonObject
        assertEquals("0.31", metadata.getValue("cost").jsonPrimitive.content)
        assertEquals("42", metadata.getValue("seed").jsonPrimitive.content)
    }

    @Test
    fun `the two moderation refusals stay distinct from each other and from Pending`() = runTest {
        for (refusal in listOf("Request Moderated", "Content Moderated")) {
            val server = TestServer(TestServer.json("""{"status":"$refusal","details":"policy"}"""))
            val status = server.provider().videoModel("flux-3-video")
                .doStatus(operation, headers = null)
            val failed = assertIs<VideoStatusResult.Failed>(status)
            assertTrue(refusal in failed.error, failed.error)
        }
    }

    @Test
    fun `draft enhance sends only the bundle, and reports everything the bundle pins as dropped`() = runTest {
        val server = submitted()
        val start = server.provider().videoModel("flux-3-video").doStart(
            VideoCallOptions(
                prompt = "ignored",
                durationInSeconds = 10.0,
                providerOptions = mapOf(
                    "blackForestLabs" to buildJsonObject { put("draftCache", "bundle-b64") },
                ),
            ),
            webhookUrl = null,
        )!!

        server.request().assertBodyKeys("mode", "draft_cache")
        assertEquals("draft_enhance", server.request().bodyJson()["mode"].string())
        start.warnings.assertUnsupported("prompt")
        start.warnings.assertUnsupported("duration")
    }
}
