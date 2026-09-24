package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoFile
import com.sabreware.aide.aisdk.VideoFrameImage
import com.sabreware.aide.aisdk.VideoFrameType
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Veo on Vertex: same model family as the Gemini API's Veo, DIFFERENT operation protocol — the poll is
 * a POST to `:fetchPredictOperation` with the name in the body, the clips arrive as `response.videos[]`,
 * and no `key=` is appended anywhere because auth is the bearer header on the poll itself.
 */
class VertexVideoModelTest {

    private fun provider(server: TestServer) = VertexProvider(
        client = HttpClient(server.engine()),
        projectId = "test-project",
        location = "us-central1",
        accessToken = { "test-token" },
    )

    private val startedOperation =
        """{"name":"projects/test-project/locations/us-central1/publishers/google/models/veo-3.0-generate-001/operations/op-123"}"""

    @Test
    fun `doStart pins the full instances-and-parameters body`() = runTest {
        val server = TestServer(TestServer.json(startedOperation))

        val result = assertNotNull(
            provider(server).videoModel("veo-3.0-generate-001").doStart(
                VideoCallOptions(
                    prompt = "A calico cat playing a piano",
                    n = 2,
                    aspectRatio = "16:9",
                    resolution = "1920x1080",
                    durationInSeconds = 8.0,
                    seed = 42,
                    generateAudio = true,
                    image = VideoFile.Data(BinaryData.Base64("QUJD"), "image/png"),
                    providerOptions = mapOf(
                        VERTEX_PROVIDER_ID to buildJsonObject {
                            put("personGeneration", "allow_adult")
                            put("negativePrompt", "blurry")
                            put("pollIntervalMs", 1000)
                        },
                    ),
                ),
            ),
        )

        val call = server.request()
        assertTrue(call.url.endsWith("/models/veo-3.0-generate-001:predictLongRunning"))
        // 1920x1080 becomes the endpoint's `1080p` name; whole seconds go as integers; the polling
        // knob is consumed silently (polling is the caller's here); nothing else leaks.
        call.assertBodyEquals(
            """{"instances":[{"prompt":"A calico cat playing a piano",
                "image":{"bytesBase64Encoded":"QUJD","mimeType":"image/png"}}],
                "parameters":{"sampleCount":2,"aspectRatio":"16:9","resolution":"1080p",
                "durationSeconds":8,"seed":42,"generateAudio":true,
                "personGeneration":"allow_adult","negativePrompt":"blurry"}}""",
        )
        assertEquals(
            "projects/test-project/locations/us-central1/publishers/google" +
                "/models/veo-3.0-generate-001/operations/op-123",
            result.operation.jsonObject["operationName"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `frame images pin both ends and suppress references`() = runTest {
        val server = TestServer(TestServer.json(startedOperation))

        provider(server).videoModel("veo-3.1-generate-001").doStart(
            VideoCallOptions(
                prompt = "sunrise",
                frameImages = listOf(
                    VideoFrameImage(
                        VideoFile.Data(BinaryData.Base64("Rk9P"), "image/jpeg"),
                        VideoFrameType.FirstFrame,
                    ),
                    VideoFrameImage(
                        VideoFile.Url("gs://bucket/last.png"),
                        VideoFrameType.LastFrame,
                    ),
                ),
                references = listOf(VideoFile.Data(BinaryData.Base64("QkFS"), "image/png")),
            ),
        )

        server.request().assertBodyEquals(
            """{"instances":[{"prompt":"sunrise",
                "image":{"bytesBase64Encoded":"Rk9P","mimeType":"image/jpeg"},
                "lastFrame":{"gcsUri":"gs://bucket/last.png","mimeType":"image/png"}}],
                "parameters":{"sampleCount":1}}""",
        )
    }

    @Test
    fun `references become referenceImages with the asset role`() = runTest {
        val server = TestServer(TestServer.json(startedOperation))

        provider(server).videoModel("veo-3.1-generate-001").doStart(
            VideoCallOptions(
                prompt = "style study",
                references = listOf(VideoFile.Data(BinaryData.Base64("QkFS"), "image/png")),
            ),
        )

        server.request().assertBodyEquals(
            """{"instances":[{"prompt":"style study",
                "referenceImages":[{"image":{"bytesBase64Encoded":"QkFS","mimeType":"image/png"},
                "referenceType":"asset"}]}],
                "parameters":{"sampleCount":1}}""",
        )
    }

    @Test
    fun `an ordinary URL image warns and is dropped, and fps warns`() = runTest {
        val server = TestServer(TestServer.json(startedOperation))

        val result = assertNotNull(
            provider(server).videoModel("veo-3.0-generate-001").doStart(
                VideoCallOptions(
                    prompt = "cat",
                    fps = 24,
                    image = VideoFile.Url("https://example.com/cat.png"),
                ),
            ),
        )

        result.warnings.assertUnsupported("URL-based image input")
        result.warnings.assertUnsupported("fps")
        server.request().assertBodyEquals(
            """{"instances":[{"prompt":"cat"}],"parameters":{"sampleCount":1}}""",
        )
    }

    @Test
    fun `the poll is a POST to fetchPredictOperation, and pending stays pending`() = runTest {
        val server = TestServer(
            TestServer.json(startedOperation),
            TestServer.json("""{"name":"op-123","done":false}"""),
        )
        val model = provider(server).videoModel("veo-3.0-generate-001")

        val started = assertNotNull(model.doStart(VideoCallOptions(prompt = "cat")))
        val status = model.doStatus(started.operation)

        assertIs<VideoStatusResult.Pending>(status)
        val poll = server.request(1)
        // The Gemini API polls with a GET at `{base}/{operationName}`; Vertex polls with a POST and
        // the name in the BODY. Pointing either at the other is a 404.
        assertEquals("POST", poll.method)
        assertTrue(poll.url.endsWith("/models/veo-3.0-generate-001:fetchPredictOperation"))
        poll.assertBodyEquals(
            """{"operationName":"projects/test-project/locations/us-central1/publishers/google/models/veo-3.0-generate-001/operations/op-123"}""",
        )
    }

    @Test
    fun `completed clips arrive as inline base64 or gcsUri, metadata under our id`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"name":"op-123","done":true,"response":{"videos":[
                    {"bytesBase64Encoded":"VklERU8=","mimeType":"video/mp4"},
                    {"gcsUri":"gs://bucket/out.mp4","mimeType":"video/mp4"}]}}""",
            ),
        )

        val status = provider(server).videoModel("veo-3.0-generate-001")
            .doStatus(buildJsonObject { put("operationName", "op-123") })

        val completed = assertIs<VideoStatusResult.Completed>(status)
        assertEquals(
            listOf<VideoData>(
                VideoData.Base64("VklERU8=", "video/mp4"),
                VideoData.Url("gs://bucket/out.mp4", "video/mp4"),
            ),
            completed.videos,
        )
        val metadata = completed.providerMetadata?.get(VERTEX_PROVIDER_ID)
        assertEquals(2, metadata?.get("videos")?.let { (it as JsonArray).size })
    }

    @Test
    fun `a refused job is Failed with the vendor's own words, not an exception`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"name":"op-123","done":true,"error":{"code":3,"message":"Prompt violates policy"}}""",
            ),
        )

        val status = provider(server).videoModel("veo-3.0-generate-001")
            .doStatus(buildJsonObject { put("operationName", "op-123") })

        val failed = assertIs<VideoStatusResult.Failed>(status)
        assertEquals("Video generation failed: Prompt violates policy", failed.error)
    }
}
