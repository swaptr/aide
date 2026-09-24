package com.sabreware.aide.aisdk.providers.async

import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoFile
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.providers.fal.FalProvider
import com.sabreware.aide.aisdk.providers.kling.KlingProvider
import com.sabreware.aide.aisdk.providers.replicate.ReplicateProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The video contract, which is the one modality where the interesting behaviour is the HANDLE.
 *
 * `doStart` and `doStatus` are tested as two separate calls on purpose: that separation is the whole
 * point of the operation model, and a test that only ever ran them back to back inside one function
 * would pass just as well against the welded-in poll loop this replaced. What each of these asserts is
 * that everything needed to resume the job is inside the returned JSON — nothing is held in the model.
 */
class VideoVendorsTest {

    private fun client(server: TestServer): HttpClient = HttpClient(server.engine())

    // --- fal ------------------------------------------------------------------------------------

    @Test
    fun `fal submits to the queue and hands back both URLs`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"request_id":"r1","response_url":"https://queue.fal.run/fal-ai/veo3/requests/r1"}""",
            ),
        )
        val provider = FalProvider(client = client(server), apiKey = "k")

        val started = provider.videoModel("fal-ai/veo3").doStart(
            VideoCallOptions(
                prompt = "a cat",
                aspectRatio = "16:9",
                durationInSeconds = 5.0,
                seed = 7,
                image = VideoFile.Url("https://cdn/frame.png"),
            ),
        )

        server.request(0).assertHeader("Authorization", "Key k")
        assertEquals("queue.fal.run/fal-ai/veo3", server.request(0).url.substringAfter("://"))
        server.request(0).assertBodyJson {
            assertEquals("a cat", it["prompt"].string())
            assertEquals("https://cdn/frame.png", it["image_url"].string())
            assertEquals("16:9", it["aspect_ratio"].string())
            // `5.0` is not a duration fal accepts; `5s` is.
            assertEquals("5s", it["duration"].string())
            assertEquals("7", it["seed"].string())
        }
        server.request(0).assertBodyKeys("prompt", "image_url", "aspect_ratio", "duration", "seed")

        // Both URLs: the one to poll, and the one that was submitted to. The second is the origin the
        // API key belongs to, and without it a poll resumed in another process has nothing to compare a
        // vendor-named host against.
        val operation = started!!.operation.jsonObject
        assertEquals(
            "https://queue.fal.run/fal-ai/veo3/requests/r1",
            operation["responseUrl"]?.jsonPrimitive?.content,
        )
        assertEquals("https://queue.fal.run/fal-ai/veo3", operation["submitUrl"]?.jsonPrimitive?.content)
    }

    @Test
    fun `fal reports a still-rendering clip as pending, not as a failure`() = runTest {
        // fal answers a poll before the clip is ready with an HTTP ERROR whose body says so. Letting that
        // escape turns every poll taken too early into a failed generation.
        val server = TestServer(TestServer.error(400, """{"detail":"Request is still in progress"}"""))
        val provider = FalProvider(client = client(server), apiKey = "k")

        val status = provider.videoModel("fal-ai/veo3").doStatus(
            parseJsonObject(
                """{"responseUrl":"https://queue.fal.run/fal-ai/veo3/requests/r1",
                   "submitUrl":"https://queue.fal.run/fal-ai/veo3"}""",
            ),
        )

        assertIs<VideoStatusResult.Pending>(status)
    }

    @Test
    fun `fal completes with the clip URL and its safety flags`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"video":{"url":"https://cdn/clip.mp4","content_type":"video/mp4","duration":5},
                    "seed":7,"has_nsfw_concepts":[false]}""",
            ),
        )
        val provider = FalProvider(client = client(server), apiKey = "k")

        val status = provider.videoModel("fal-ai/veo3").doStatus(
            parseJsonObject(
                """{"responseUrl":"https://queue.fal.run/fal-ai/veo3/requests/r1",
                   "submitUrl":"https://queue.fal.run/fal-ai/veo3"}""",
            ),
        )

        val completed = assertIs<VideoStatusResult.Completed>(status)
        // A clip is tens of megabytes: it stays a URL rather than being pulled into memory, which is why
        // `VideoData.Url` is the first arm of the union.
        assertEquals(VideoData.Url("https://cdn/clip.mp4", "video/mp4"), completed.videos.single())
        assertEquals(
            "false",
            (completed.providerMetadata?.get("fal")?.get("has_nsfw_concepts") as? JsonArray)
                ?.first()?.jsonPrimitive?.content,
        )
    }

    // --- replicate ------------------------------------------------------------------------------

    @Test
    fun `replicate video starts a prediction and polls its own get URL`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"id":"p1","urls":{"get":"https://api.replicate.com/v1/predictions/p1"}}""",
            ),
            TestServer.json("""{"id":"p1","status":"succeeded","output":"https://cdn/clip.mp4"}"""),
        )
        val provider = ReplicateProvider(client = client(server), apiToken = "t")
        val model = provider.videoModel("owner/name")

        val started = model.doStart(
            VideoCallOptions(prompt = "a cat", resolution = "1280x720", durationInSeconds = 5.0, fps = 24),
            webhookUrl = "https://example.test/hook",
        )!!

        server.request(0).assertBodyJson { body ->
            val input = body["input"]!!.jsonObject
            assertEquals("a cat", input["prompt"].string())
            // Replicate spells a video's pixel dimensions `size`, not `resolution`.
            assertEquals("1280x720", input["size"].string())
            assertEquals("5.0", input["duration"].string())
            assertEquals("24", input["fps"].string())
            // Without the filter Replicate calls the webhook on every intermediate log line.
            assertEquals("[\"completed\"]", body["webhook_events_filter"].toString())
        }

        val status = model.doStatus(started.operation)
        assertEquals("api.replicate.com/v1/predictions/p1", server.request(1).url.substringAfter("://"))
        val completed = assertIs<VideoStatusResult.Completed>(status)
        assertEquals(VideoData.Url("https://cdn/clip.mp4", "video/mp4"), completed.videos.single())
    }

    @Test
    fun `a refused replicate clip is a status, not a thrown transport error`() = runTest {
        val server = TestServer(
            TestServer.json("""{"id":"p1","status":"failed","error":"NSFW content detected"}"""),
        )
        val provider = ReplicateProvider(client = client(server), apiToken = "t")

        val status = provider.videoModel("owner/name").doStatus(
            parseJsonObject(
                """{"getUrl":"https://api.replicate.com/v1/predictions/p1"}""",
            ),
        )

        // A refused job is an answer. Throwing here would make a caller's retry policy re-run a prompt
        // that will be refused every time.
        val failed = assertIs<VideoStatusResult.Failed>(status)
        assertEquals("Video generation failed: NSFW content detected", failed.error)
    }

    // --- kling ----------------------------------------------------------------------------------

    @Test
    fun `kling routes by the model id suffix and rewrites the model name`() = runTest {
        val server = TestServer(TestServer.json("""{"data":{"task_id":"t1"}}"""))
        val provider = KlingProvider(client = client(server), apiKey = "k")

        val started = provider.videoModel("kling-v2.1-master-t2v")
            .doStart(VideoCallOptions(prompt = "a cat", aspectRatio = "16:9", durationInSeconds = 5.0))!!

        // The suffix names the ENDPOINT and never reaches the body; the version is dotted in the id and
        // hyphenated on the wire. Each is a "model not found" that reads as an unavailable model.
        assertEquals(
            "api-singapore.klingai.com/v1/videos/text2video",
            server.request(0).url.substringAfter("://"),
        )
        server.request(0).assertBodyJson {
            assertEquals("kling-v2-1-master", it["model_name"].string())
            assertEquals("a cat", it["prompt"].string())
            assertEquals("16:9", it["aspect_ratio"].string())
            // Whole seconds as a string: `5.0` is rejected where `5` is accepted.
            assertEquals("5", it["duration"].string())
        }
        server.request(0).assertBodyKeys("model_name", "prompt", "aspect_ratio", "duration")

        // The endpoint travels in the handle because a status query goes back to the endpoint the job was
        // submitted to; a handle holding only the task id cannot be polled once the mode is forgotten.
        assertEquals("/v1/videos/text2video", started.operation.jsonObject["endpointPath"].string())
    }

    @Test
    fun `kling image-to-video refuses an aspect ratio the input image already decides`() = runTest {
        val server = TestServer(TestServer.json("""{"data":{"task_id":"t1"}}"""))
        val provider = KlingProvider(client = client(server), apiKey = "k")

        val started = provider.videoModel("kling-v1-i2v").doStart(
            VideoCallOptions(
                prompt = "a cat",
                aspectRatio = "16:9",
                image = VideoFile.Url("https://cdn/frame.png"),
            ),
        )!!

        started.warnings.assertUnsupported(
            "aspectRatio",
            "KlingAI image-to-video does not support aspectRatio. " +
                "The output dimensions are determined by the input image.",
        )
        server.request(0).assertBodyKeys("model_name", "prompt", "image")
    }

    @Test
    fun `kling polls the endpoint the handle names and reads its own past tense`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"data":{"task_status":"succeed","task_result":{"videos":[
                   {"id":"v1","url":"https://cdn/clip.mp4","duration":"5"}]}}}""",
            ),
        )
        val provider = KlingProvider(client = client(server), apiKey = "k")

        val status = provider.videoModel("kling-v1-t2v").doStatus(
            parseJsonObject(
                """{"taskId":"t1","endpointPath":"/v1/videos/text2video"}""",
            ),
        )

        assertEquals(
            "api-singapore.klingai.com/v1/videos/text2video/t1",
            server.request(0).url.substringAfter("://"),
        )
        // `succeed`, not `succeeded`. Matching the English past tense polls a finished job forever.
        val completed = assertIs<VideoStatusResult.Completed>(status)
        assertEquals(VideoData.Url("https://cdn/clip.mp4", "video/mp4"), completed.videos.single())
    }
}
