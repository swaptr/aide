package com.sabreware.aide.aisdk.providers.prodia

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.DownloadError
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Prodia's image-to-image job driven as a language model, against the reference's
 * `prodia-language-model.test.ts`.
 *
 * The request is the half that matters here: EVERY call is multipart, the envelope rides as a part
 * named `job` and the picture as a part named `input`, and the endpoint reads both by name. A part
 * sent under the wrong name is not a 400 — it is a job run from the prompt alone, which returns a
 * plausible picture of the wrong thing. So each test reads the parts back off the wire rather than
 * trusting the object that built them.
 */
class ProdiaLanguageModelTest {

    private val modelId = "inference.nano-banana.img2img.v2"
    private val prompt = "Describe this image"
    private val jobUrl = "https://inference.prodia.com/v2/job?price=true"

    private fun model(server: TestServer) =
        ProdiaProvider(client = HttpClient(server.engine()), apiKey = "test-key").languageModel(modelId)

    private fun answer(text: String? = ProdiaFixtures.LANGUAGE_TEXT, image: String? = ProdiaFixtures.LANGUAGE_IMAGE) =
        TestServer.bytes(ProdiaFixtures.languageResponse(text = text, image = image), ProdiaFixtures.CONTENT_TYPE)

    private fun user(vararg parts: UserPart) = listOf(ModelMessage.User(parts.toList()))

    private fun image(data: FileData, mediaType: String = "image/png") = UserPart.File(data, mediaType)

    // --- The request ---------------------------------------------------------------------------------

    @Test
    fun `every request is multipart, with the envelope as a job part named job dot json`() = runTest {
        val server = TestServer(answer())

        model(server).doGenerate(CallOptions(prompt = user(UserPart.Text("make it blue"))))

        val call = server.request()
        assertEquals("POST", call.method)
        assertEquals(jobUrl, call.url)
        assertEquals("Bearer test-key", call.header("Authorization"))
        assertEquals("multipart/form-data", call.header("Accept"))
        assertTrue(call.header("Content-Type").orEmpty().startsWith("multipart/form-data"))
        call.assertMultipartFile("job", fileName = "job.json", contentType = "application/json")
        // The reference's own pin: `include_messages` is always on.
        assertEquals(
            parseJsonObject("""{"type":"$modelId","config":{"prompt":"make it blue","include_messages":true}}"""),
            call.jobPart(),
        )
        // A text-only turn has no picture, and sends no part for one.
        assertNull(call.inputPart())
    }

    @Test
    fun `a system turn leads the prompt and earlier user turns are dropped`() = runTest {
        val server = TestServer(answer())

        model(server).doGenerate(
            CallOptions(
                prompt = listOf(
                    ModelMessage.System("be terse"),
                    ModelMessage.User(listOf(UserPart.Text("first"))),
                    ModelMessage.User(listOf(UserPart.Text("second"))),
                ),
            ),
        )

        // A job takes ONE instruction; pasting the whole conversation in would render the transcript.
        assertEquals("be terse\nsecond", server.request().jobPart()["config"]!!.jsonObject["prompt"].string())
    }

    @Test
    fun `aspectRatio from provider options lands in config as aspect_ratio`() = runTest {
        val server = TestServer(answer())

        model(server).doGenerate(
            CallOptions(
                prompt = user(UserPart.Text(prompt)),
                providerOptions = mapOf(PRODIA_PROVIDER_ID to buildJsonObject { put("aspectRatio", "16:9") }),
            ),
        )

        assertEquals("16:9", server.request().jobPart()["config"]!!.jsonObject["aspect_ratio"].string())
    }

    @Test
    fun `an attached image goes out as the input part, after the job, typed and named by its media type`() =
        runTest {
            val server = TestServer(answer())
            val png = byteArrayOf(1, 2, 3)

            model(server).doGenerate(
                CallOptions(prompt = user(image(FileData.Bytes(png)), UserPart.Text(prompt))),
            )

            val call = server.request()
            assertEquals(listOf("job", "input"), call.sentParts().map { it.name })
            call.assertMultipartFile("input", fileName = "input.png", contentType = "image/png")
            assertContentEquals(png, call.inputPart()!!.body)
            assertEquals(prompt, call.jobPart()["config"]!!.jsonObject["prompt"].string())
        }

    @Test
    fun `jpeg and webp take the reference's extensions and anything else goes out bare`() = runTest {
        val cases = listOf("image/jpeg" to "input.jpg", "image/webp" to "input.webp", "image/gif" to "input")
        for ((mediaType, fileName) in cases) {
            val server = TestServer(answer())

            model(server).doGenerate(
                CallOptions(prompt = user(image(FileData.Bytes(byteArrayOf(1)), mediaType), UserPart.Text(prompt))),
            )

            server.request().assertMultipartFile("input", fileName = fileName, contentType = mediaType)
        }
    }

    @Test
    fun `a bare image media type is sniffed to the full type from the bytes`() = runTest {
        val server = TestServer(answer())
        val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

        model(server).doGenerate(
            CallOptions(prompt = user(image(FileData.Bytes(pngSignature), "image"), UserPart.Text(prompt))),
        )

        server.request().assertMultipartFile("input", fileName = "input.png", contentType = "image/png")
    }

    @Test
    fun `undetectable bytes under a bare image type keep the png default`() = runTest {
        val server = TestServer(answer())

        model(server).doGenerate(
            CallOptions(prompt = user(image(FileData.Bytes(byteArrayOf(0, 1, 2)), "image"), UserPart.Text(prompt))),
        )

        server.request().assertMultipartFile("input", fileName = "input.png", contentType = "image/png")
    }

    @Test
    fun `a vendor-held reference is refused before any request`() = runTest {
        val server = TestServer(answer())

        val reference = image(FileData.Reference(mapOf("prodia" to "file_1")))
        assertFailsWith<UnsupportedFunctionalityError> {
            model(server).doGenerate(CallOptions(prompt = user(reference, UserPart.Text(prompt))))
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `an inline text document is refused before any request`() = runTest {
        val server = TestServer(answer())

        assertFailsWith<UnsupportedFunctionalityError> {
            model(server).doGenerate(
                CallOptions(prompt = user(image(FileData.Text("<svg/>"), "image/svg+xml"), UserPart.Text(prompt))),
            )
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `a linked image is downloaded without the key and uploaded as bytes`() = runTest {
        val server = TestServer(
            TestServer.bytes(ProdiaFixtures.CDN_INPUT.encodeToByteArray(), "image/png"),
            answer(),
        )

        model(server).doGenerate(
            CallOptions(
                prompt = user(image(FileData.Url("https://cdn.example.com/input.png")), UserPart.Text(prompt)),
            ),
        )

        assertEquals(2, server.callCount)
        val download = server.request(0)
        assertEquals("https://cdn.example.com/input.png", download.url)
        // The key belongs to the API host; a CDN named in a prompt does not get it.
        assertNull(download.header("Authorization"))
        val job = server.request(1)
        assertEquals(jobUrl, job.url)
        job.assertMultipartFile("input", fileName = "input.png", contentType = "image/png")
        assertContentEquals(ProdiaFixtures.CDN_INPUT.encodeToByteArray(), job.inputPart()!!.body)
    }

    @Test
    fun `a linked image on a private address is refused before anything is fetched`() = runTest {
        val server = TestServer(answer())

        // The reference fetches this URL with a bare `fetch`; here it goes through the same guard every
        // other download does, so a prompt cannot point the app at a metadata service.
        assertFailsWith<DownloadError> {
            model(server).doGenerate(
                CallOptions(
                    prompt = user(
                        image(FileData.Url("http://169.254.169.254/latest/meta-data/")),
                        UserPart.Text(prompt),
                    ),
                ),
            )
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `a second image in the same turn is not sent, and the warning says so`() = runTest {
        val server = TestServer(answer())
        val first = "first".encodeToByteArray()

        val result = model(server).doGenerate(
            CallOptions(
                prompt = user(
                    image(FileData.Bytes(first)),
                    image(FileData.Bytes("second".encodeToByteArray())),
                    UserPart.Text(prompt),
                ),
            ),
        )

        // The job takes one `input`. The reference keeps the first silently; this port says so.
        result.warnings.assertUnsupported("multiple image inputs")
        val call = server.request()
        assertEquals(1, call.sentParts().count { it.name == "input" })
        assertContentEquals(first, call.inputPart()!!.body)
    }

    // --- The answer ----------------------------------------------------------------------------------

    @Test
    fun `text and image output parts become text, then a file`() = runTest {
        val server = TestServer(answer())

        val result = model(server).doGenerate(CallOptions(prompt = user(UserPart.Text(prompt))))

        assertEquals(2, result.content.size)
        assertEquals(Content.Text(ProdiaFixtures.LANGUAGE_TEXT), result.content[0])
        val file = assertIs<Content.File>(result.content[1])
        assertEquals("image/png", file.mediaType)
        assertContentEquals(ProdiaFixtures.LANGUAGE_IMAGE.encodeToByteArray(), (file.data as FileData.Bytes).bytes)
        assertEquals(FinishReason.Unified.Stop, result.finishReason.unified)
        assertNull(result.finishReason.raw)
    }

    @Test
    fun `a text-only answer is a single text part`() = runTest {
        val server = TestServer(answer(text = "Just a text response", image = null))

        val result = model(server).doGenerate(CallOptions(prompt = user(UserPart.Text(prompt))))

        assertEquals(listOf<Content>(Content.Text("Just a text response")), result.content)
    }

    @Test
    fun `the job metrics ride providerMetadata flat, as the reference publishes them`() = runTest {
        val server = TestServer(answer())

        val result = model(server).doGenerate(CallOptions(prompt = user(UserPart.Text(prompt))))

        // Flat here, where the IMAGE model nests the same block under `images` — the reference's own
        // asymmetry, and the kind of thing a consumer reads straight off the wrong path.
        assertEquals(
            parseJsonObject(
                """{"jobId":"job-lang-123","seed":7,"elapsed":1.5,"iterationsPerSecond":20,""" +
                    """"createdAt":"2025-01-01T00:00:00Z","updatedAt":"2025-01-01T00:00:03Z","dollars":0.01}""",
            ),
            result.providerMetadata!![PRODIA_PROVIDER_ID],
        )
        assertEquals(modelId, result.response?.metadata?.modelId)
        assertEquals("job-lang-123", result.response?.metadata?.id)
        // The envelope is quoted back even though the transport holds no string form of a multipart body.
        assertEquals(
            parseJsonObject("""{"type":"$modelId","config":{"prompt":"$prompt","include_messages":true}}"""),
            parseJsonObject(result.request!!.body!!),
        )
    }

    @Test
    fun `every LLM knob this endpoint lacks is named rather than dropped`() = runTest {
        val server = TestServer(answer())

        val result = model(server).doGenerate(
            CallOptions(
                prompt = user(UserPart.Text(prompt)),
                temperature = 0.5,
                topP = 0.9,
                topK = 40,
                seed = 42,
                maxOutputTokens = 1000,
                stopSequences = listOf("stop"),
                presencePenalty = 0.1,
                frequencyPenalty = 0.2,
                tools = listOf(Tool.Function(name = "test", inputSchema = buildJsonObject {})),
                toolChoice = ToolChoice.Auto,
                responseFormat = ResponseFormat.Json(),
                reasoning = ReasoningEffort.Medium,
            ),
        )

        for (
            feature in listOf(
                "temperature", "topP", "topK", "seed", "maxOutputTokens", "stopSequences",
                "presencePenalty", "frequencyPenalty", "tools", "toolChoice", "responseFormat", "reasoning",
            )
        ) {
            result.warnings.assertUnsupported(feature)
        }
    }

    @Test
    fun `the stream replays the answer as parts, metadata first`() = runTest {
        val server = TestServer(answer(text = "Stream test response", image = "stream-image-bytes"))

        val parts = model(server).doStream(CallOptions(prompt = user(UserPart.Text(prompt)))).stream.toList()

        assertIs<StreamPart.StreamStart>(parts[0])
        val metadata = assertIs<StreamPart.ResponseMetadataPart>(parts[1])
        assertEquals(modelId, metadata.metadata.modelId)
        assertIs<StreamPart.TextStart>(parts[2])
        assertEquals("Stream test response", assertIs<StreamPart.TextDelta>(parts[3]).delta)
        assertIs<StreamPart.TextEnd>(parts[4])
        assertEquals("image/png", assertIs<StreamPart.FilePart>(parts[5]).file.mediaType)
        assertEquals(FinishReason.Unified.Stop, assertIs<StreamPart.Finish>(parts[6]).finishReason.unified)
        assertEquals(7, parts.size)
    }

    @Test
    fun `an API error carries the vendor's detail`() = runTest {
        val server = TestServer(TestServer.error(400, ProdiaFixtures.LANGUAGE_ERROR))

        val error = assertFailsWith<APICallError> {
            model(server).doGenerate(CallOptions(prompt = user(UserPart.Text(prompt))))
        }

        assertEquals(400, error.statusCode)
        assertTrue(error.message.orEmpty().contains("Missing input image"), error.message)
    }
}
