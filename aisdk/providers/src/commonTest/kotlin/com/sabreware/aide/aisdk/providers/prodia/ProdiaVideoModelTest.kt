package com.sabreware.aide.aisdk.providers.prodia

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.DownloadError
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoFile
import com.sabreware.aide.aisdk.VideoFrameImage
import com.sabreware.aide.aisdk.VideoFrameType
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Prodia video, against the reference's `prodia-video-model.test.ts`.
 *
 * One endpoint, two request shapes: text-to-video is a JSON body, image-to-video is multipart with
 * the starting frame as a part named `input`. The tests pin which shape goes out for which call, what
 * each carries, and the one multipart decode both share on the way back.
 */
@OptIn(ExperimentalEncodingApi::class)
class ProdiaVideoModelTest {

    private val prompt = "A cat walking on a beach"
    private val txt2vid = "inference.wan2-2.lightning.txt2vid.v0"
    private val img2vid = "inference.wan2-2.lightning.img2vid.v0"
    private val jobUrl = "https://inference.prodia.com/v2/job?price=true"

    private fun model(server: TestServer, modelId: String = txt2vid) =
        ProdiaProvider(client = HttpClient(server.engine()), apiKey = "test-key").videoModel(modelId)

    private fun answer(contentType: String = "video/mp4", fileName: String = "output.mp4") = TestServer.bytes(
        ProdiaFixtures.videoResponse(contentType = contentType, fileName = fileName),
        ProdiaFixtures.CONTENT_TYPE,
    )

    private fun frame(bytes: ByteArray, mediaType: String = "image/png") =
        VideoFile.Data(BinaryData.Bytes(bytes), mediaType)

    // --- text-to-video: JSON ------------------------------------------------------------------------

    @Test
    fun `a text-to-video job is plain JSON, naming mp4 in Accept`() = runTest {
        val server = TestServer(answer())

        model(server).doGenerate(VideoCallOptions(prompt = prompt))

        val call = server.request()
        assertEquals("POST", call.method)
        assertEquals(jobUrl, call.url)
        assertEquals("application/json", call.header("Content-Type"))
        assertEquals("multipart/form-data; video/mp4", call.header("Accept"))
        assertEquals("Bearer test-key", call.header("Authorization"))
        call.assertBodyEquals("""{"type":"$txt2vid","config":{"prompt":"$prompt"}}""")
    }

    @Test
    fun `seed joins the config`() = runTest {
        val server = TestServer(answer())

        model(server).doGenerate(VideoCallOptions(prompt = prompt, seed = 42))

        server.request().assertBodyEquals("""{"type":"$txt2vid","config":{"prompt":"$prompt","seed":42}}""")
    }

    @Test
    fun `the vendor resolution name joins the config`() = runTest {
        val server = TestServer(answer())

        model(server).doGenerate(
            VideoCallOptions(
                prompt = prompt,
                providerOptions = mapOf(PRODIA_PROVIDER_ID to buildJsonObject { put("resolution", "720p") }),
            ),
        )

        server.request().assertBodyEquals("""{"type":"$txt2vid","config":{"prompt":"$prompt","resolution":"720p"}}""")
    }

    @Test
    fun `a pixel resolution has nowhere to go and says so`() = runTest {
        val server = TestServer(answer())

        val result = model(server).doGenerate(VideoCallOptions(prompt = prompt, resolution = "1280x720", fps = 24))!!

        // Prodia's config takes a resolution NAME; sending pixels would be silently ignored.
        result.warnings.assertUnsupported("resolution")
        result.warnings.assertUnsupported("fps")
    }

    @Test
    fun `a caller cannot override the Accept the multipart decode depends on`() = runTest {
        val server = TestServer(answer())

        model(server).doGenerate(VideoCallOptions(prompt = prompt, headers = mapOf("Accept" to "application/json")))

        // Honouring it would return a JSON body the multipart parser then fails on — a broken response
        // rather than a rejected header, which is why the reference spreads this last too.
        assertEquals("multipart/form-data; video/mp4", server.request().header("Accept"))
    }

    // --- image-to-video: multipart ------------------------------------------------------------------

    @Test
    fun `a starting frame turns the job into multipart, with the picture as the input part`() = runTest {
        val server = TestServer(answer())
        val png = byteArrayOf(1, 2, 3, 4)

        model(server, img2vid).doGenerate(VideoCallOptions(prompt = prompt, image = frame(png)))

        val call = server.request()
        assertEquals(jobUrl, call.url)
        assertTrue(call.header("Content-Type").orEmpty().startsWith("multipart/form-data"))
        assertEquals("multipart/form-data; video/mp4", call.header("Accept"))
        assertEquals(listOf("job", "input"), call.sentParts().map { it.name })
        call.assertMultipartFile("job", fileName = "job.json", contentType = "application/json")
        call.assertMultipartFile("input", fileName = "input.png", contentType = "image/png")
        // The envelope is the same one the JSON shape sends — the picture is the only difference.
        assertEquals(parseJsonObject("""{"type":"$img2vid","config":{"prompt":"$prompt"}}"""), call.jobPart())
        assertContentEquals(png, call.inputPart()!!.body)
    }

    @Test
    fun `a base64 starting frame is decoded before upload`() = runTest {
        val server = TestServer(answer())

        model(server, img2vid).doGenerate(
            VideoCallOptions(
                prompt = prompt,
                image = VideoFile.Data(BinaryData.Base64(Base64.encode("PNG!".encodeToByteArray())), "image/jpeg"),
            ),
        )

        val call = server.request()
        call.assertMultipartFile("input", fileName = "input.jpg", contentType = "image/jpeg")
        assertContentEquals("PNG!".encodeToByteArray(), call.inputPart()!!.body)
    }

    @Test
    fun `a linked frame is downloaded without the key, typed by the server, and uploaded`() = runTest {
        val server = TestServer(
            TestServer.bytes(ProdiaFixtures.CDN_INPUT.encodeToByteArray(), "image/png"),
            answer(),
        )

        model(server, img2vid).doGenerate(
            VideoCallOptions(prompt = prompt, image = VideoFile.Url("https://cdn.example.com/input.png")),
        )

        // The image is downloaded, then the job is POSTed as multipart form-data.
        assertEquals(2, server.callCount)
        val download = server.request(0)
        assertEquals("https://cdn.example.com/input.png", download.url)
        assertNull(download.header("Authorization"))
        val job = server.request(1)
        assertEquals(jobUrl, job.url)
        assertTrue(job.header("Content-Type").orEmpty().startsWith("multipart/form-data"))
        job.assertMultipartFile("input", fileName = "input.png", contentType = "image/png")
        assertContentEquals(ProdiaFixtures.CDN_INPUT.encodeToByteArray(), job.inputPart()!!.body)
    }

    @Test
    fun `a linked frame on a private address is refused before anything is fetched`() = runTest {
        val server = TestServer(answer())

        assertFailsWith<DownloadError> {
            model(server, img2vid).doGenerate(
                VideoCallOptions(prompt = prompt, image = VideoFile.Url("http://169.254.169.254/latest/meta-data/")),
            )
        }
        // The internal URL must never be requested, and no job is submitted.
        assertEquals(0, server.callCount)
    }

    @Test
    fun `role-tagged frames have nowhere to go and say so`() = runTest {
        val server = TestServer(answer())

        val result = model(server, img2vid).doGenerate(
            VideoCallOptions(
                prompt = prompt,
                frameImages = listOf(VideoFrameImage(frame(byteArrayOf(1)), VideoFrameType.FirstFrame)),
            ),
        )!!

        // The reference ignores these outright and runs text-to-video; the job goes out the same way
        // here, with the drop named rather than silent.
        result.warnings.assertUnsupported("frameImages")
        assertEquals("application/json", server.request().header("Content-Type"))
    }

    // --- The answer ----------------------------------------------------------------------------------

    @Test
    fun `the clip comes back as bytes, with the job metrics under videos`() = runTest {
        val server = TestServer(answer())

        val result = model(server).doGenerate(VideoCallOptions(prompt = prompt))!!

        val video = result.videos.single() as VideoData.Bytes
        assertContentEquals(ProdiaFixtures.VIDEO_CONTENT.encodeToByteArray(), video.data)
        assertEquals("video/mp4", video.mediaType)
        assertEquals(
            parseJsonObject(
                """{"jobId":"job-vid-123","seed":99,"elapsed":5,"iterationsPerSecond":3.2,""" +
                    """"createdAt":"2025-01-01T00:00:00Z","updatedAt":"2025-01-01T00:00:10Z","dollars":0.05}""",
            ),
            result.providerMetadata!![PRODIA_PROVIDER_ID]!!.jsonObject["videos"]!!.jsonArray.single(),
        )
        assertEquals(txt2vid, result.response.modelId)
        assertEquals("job-vid-123", result.response.id)
    }

    @Test
    fun `an output part typed webm keeps its type`() = runTest {
        val server = TestServer(answer(contentType = "video/webm", fileName = "output.webm"))

        val result = model(server).doGenerate(VideoCallOptions(prompt = prompt))!!

        assertEquals("video/webm", result.videos.single().mediaType)
    }

    @Test
    fun `an API error carries the vendor's detail`() = runTest {
        val server = TestServer(TestServer.error(400, ProdiaFixtures.VIDEO_ERROR))

        val error = assertFailsWith<APICallError> { model(server).doGenerate(VideoCallOptions(prompt = prompt)) }

        assertEquals(400, error.statusCode)
        assertEquals(jobUrl, error.url)
        assertTrue(error.message.orEmpty().contains("Prompt cannot be empty"), error.message)
    }
}
