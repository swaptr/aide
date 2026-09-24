package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoFile
import com.sabreware.aide.aisdk.VideoFrameImage
import com.sabreware.aide.aisdk.VideoFrameType
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Veo over `predictLongRunning`, ported from `google-video-model.test.ts`.
 *
 * The whole-body assertions mirror the reference's `toStrictEqual` — the assertion that sees an EXTRA
 * parameter as well as a missing one.
 */
class GoogleVideoModelTest {

    private val prompt = "A futuristic city with flying cars"

    private fun model(server: TestServer, apiKey: String = "test-api-key") = GoogleVideoModel(
        modelId = "veo-3.1-generate-preview",
        http = server.http(),
        headers = { mapOf("x-goog-api-key" to apiKey) },
    )

    private fun startServer(operationName: String = "operations/test-operation-id") =
        TestServer(TestServer.json("""{"name":"$operationName","done":false}"""))

    private fun statusServer(body: String) = TestServer(TestServer.json(body))

    private fun options(build: VideoCallOptions.() -> VideoCallOptions = { this }) =
        VideoCallOptions(prompt = prompt).build()

    private fun googleOptions(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        mapOf(GOOGLE_PROVIDER_ID to buildJsonObject(build))

    private fun doneBody(vararg uris: String): String {
        val samples = uris.joinToString(",") { """{"video":{"uri":"$it"}}""" }
        return """{"name":"operations/op","done":true,""" +
            """"response":{"generateVideoResponse":{"generatedSamples":[$samples]}}}"""
    }

    // --- doStart -------------------------------------------------------------------------------------

    @Test
    fun `the submit goes to predictLongRunning with instances and parameters, and nothing else`() =
        runTest {
            val server = startServer("operations/start-test-op")

            val result = model(server).doStart(options())

            val call = server.request()
            assertEquals("v1beta/models/veo-3.1-generate-preview:predictLongRunning", call.path)
            assertEquals("test-api-key", call.header("x-goog-api-key"))
            call.assertBodyEquals(
                """{"instances":[{"prompt":"$prompt"}],"parameters":{"sampleCount":1}}""",
            )
            // The handle is exactly what doStatus needs, and it survives serialization.
            assertEquals(
                "operations/start-test-op",
                result.operation.jsonObject["operationName"].string(),
            )
        }

    @Test
    fun `seed and aspect ratio ride in parameters`() = runTest {
        val server = startServer()

        model(server).doStart(options { copy(seed = 42, aspectRatio = "16:9") })

        server.request().assertBodyEquals(
            """{"instances":[{"prompt":"$prompt"}],""" +
                """"parameters":{"sampleCount":1,"aspectRatio":"16:9","seed":42}}""",
        )
    }

    @Test
    fun `a named resolution is translated and an unknown one is forwarded as spelled`() = runTest {
        val server = startServer()
        val m = model(server)

        m.doStart(options { copy(resolution = "1920x1080") })
        m.doStart(options { copy(resolution = "640x480") })

        assertEquals("1080p", server.request(0).bodyJson().obj("parameters")?.get("resolution").string())
        assertEquals("640x480", server.request(1).bodyJson().obj("parameters")?.get("resolution").string())
    }

    @Test
    fun `duration goes out as whole durationSeconds and n as sampleCount`() = runTest {
        val server = startServer()

        model(server).doStart(options { copy(durationInSeconds = 8.0, n = 3) })

        server.request().assertBodyEquals(
            """{"instances":[{"prompt":"$prompt"}],""" +
                """"parameters":{"sampleCount":3,"durationSeconds":8}}""",
        )
    }

    @Test
    fun `a submit that answers with no operation name is a loud failure`() = runTest {
        val server = TestServer(TestServer.json("""{"done":false}"""))

        assertFailsWith<NoContentGeneratedError> { model(server).doStart(options()) }
    }

    @Test
    fun `an input image is the Vertex payload shape, not Gemini inlineData`() = runTest {
        val server = startServer()

        model(server).doStart(
            options {
                copy(
                    image = VideoFile.Data(
                        data = BinaryData.Base64("base64-image-data"),
                        mediaType = "image/png",
                    ),
                )
            },
        )

        // bytesBase64Encoded/mimeType — the predictLongRunning surface; inlineData 400s here.
        assertEquals(
            buildJsonObject {
                put("bytesBase64Encoded", "base64-image-data")
                put("mimeType", "image/png")
            },
            server.request().bodyJson().arr("instances")?.first()?.jsonObject?.get("image"),
        )
    }

    @Test
    fun `an ordinary URL image has no field to ride in and warns instead of mangling`() = runTest {
        val server = startServer()

        val result = model(server).doStart(
            options { copy(image = VideoFile.Url("https://example.com/image.png")) },
        )

        result.warnings.assertUnsupported("URL-based image input")
        assertEquals(null, server.request().bodyJson().arr("instances")?.first()?.jsonObject?.get("image"))
    }

    @Test
    fun `a gs URI rides as gcsUri`() = runTest {
        val server = startServer()

        model(server).doStart(options { copy(image = VideoFile.Url("gs://bucket/frame.png")) })

        assertEquals(
            buildJsonObject {
                put("gcsUri", "gs://bucket/frame.png")
                put("mimeType", "image/png")
            },
            server.request().bodyJson().arr("instances")?.first()?.jsonObject?.get("image"),
        )
    }

    @Test
    fun `frame images pin the first and last frame`() = runTest {
        val server = startServer()

        model(server).doStart(
            options {
                copy(
                    frameImages = listOf(
                        VideoFrameImage(
                            VideoFile.Data(BinaryData.Base64("first"), "image/png"),
                            VideoFrameType.FirstFrame,
                        ),
                        VideoFrameImage(
                            VideoFile.Data(BinaryData.Base64("last"), "image/png"),
                            VideoFrameType.LastFrame,
                        ),
                    ),
                )
            },
        )

        val instance = server.request().bodyJson().arr("instances")?.first()?.jsonObject
        assertEquals("first", instance?.obj("image")?.get("bytesBase64Encoded").string())
        assertEquals("last", instance?.obj("lastFrame")?.get("bytesBase64Encoded").string())
    }

    @Test
    fun `personGeneration and negativePrompt come from the google namespace`() = runTest {
        val server = startServer()

        model(server).doStart(
            options {
                copy(
                    providerOptions = googleOptions {
                        put("personGeneration", "allow_adult")
                        put("negativePrompt", "blurry, low quality")
                    },
                )
            },
        )

        server.request().assertBodyEquals(
            """{"instances":[{"prompt":"$prompt"}],""" +
                """"parameters":{"sampleCount":1,"personGeneration":"allow_adult",""" +
                """"negativePrompt":"blurry, low quality"}}""",
        )
    }

    @Test
    fun `an unclaimed google option passes through to parameters and poll knobs do not`() = runTest {
        val server = startServer()

        model(server).doStart(
            options {
                copy(
                    providerOptions = googleOptions {
                        put("enhancePrompt", true)
                        put("pollIntervalMs", 500)
                    },
                )
            },
        )

        server.request().assertBodyEquals(
            """{"instances":[{"prompt":"$prompt"}],""" +
                """"parameters":{"sampleCount":1,"enhancePrompt":true}}""",
        )
    }

    @Test
    fun `vendor referenceImages become asset references in both spellings`() = runTest {
        val server = startServer()

        model(server).doStart(
            options {
                copy(
                    providerOptions = googleOptions {
                        put(
                            "referenceImages",
                            kotlinx.serialization.json.buildJsonArray {
                                add(buildJsonObject { put("bytesBase64Encoded", "reference-image-data") })
                                add(buildJsonObject { put("gcsUri", "gs://bucket/reference.png") })
                            },
                        )
                    },
                )
            },
        )

        val references = server.request().bodyJson()
            .arr("instances")?.first()?.jsonObject?.arr("referenceImages")
        assertEquals(2, references?.size)
        assertEquals(
            buildJsonObject {
                put(
                    "image",
                    buildJsonObject {
                        put("bytesBase64Encoded", "reference-image-data")
                        put("mimeType", "image/png")
                    },
                )
                put("referenceType", "asset")
            },
            references?.get(0),
        )
        assertEquals(
            "gs://bucket/reference.png",
            references?.get(1)?.jsonObject?.obj("image")?.get("gcsUri").string(),
        )
    }

    @Test
    fun `caller references win over the vendor option and frame images suppress both`() = runTest {
        val server = startServer()
        val m = model(server)
        val vendorReferences = googleOptions {
            put(
                "referenceImages",
                kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject { put("gcsUri", "gs://bucket/vendor.png") })
                },
            )
        }

        m.doStart(
            options {
                copy(
                    references = listOf(VideoFile.Data(BinaryData.Base64("caller-ref"), "image/png")),
                    providerOptions = vendorReferences,
                )
            },
        )
        m.doStart(
            options {
                copy(
                    frameImages = listOf(
                        VideoFrameImage(
                            VideoFile.Data(BinaryData.Base64("first"), "image/png"),
                            VideoFrameType.FirstFrame,
                        ),
                    ),
                    references = listOf(VideoFile.Data(BinaryData.Base64("caller-ref"), "image/png")),
                    providerOptions = vendorReferences,
                )
            },
        )

        val first = server.request(0).bodyJson().arr("instances")?.first()?.jsonObject
        assertEquals(
            "caller-ref",
            first?.arr("referenceImages")?.first()?.jsonObject?.obj("image")
                ?.get("bytesBase64Encoded").string(),
        )
        val second = server.request(1).bodyJson().arr("instances")?.first()?.jsonObject
        assertEquals(null, second?.get("referenceImages"))
    }

    @Test
    fun `fps and generateAudio have no wire field and warn`() = runTest {
        val server = startServer()

        val result = model(server).doStart(options { copy(fps = 30, generateAudio = true) })

        result.warnings.assertUnsupported("fps")
        result.warnings.assertUnsupported("generateAudio")
    }

    // --- doStatus ------------------------------------------------------------------------------------

    @Test
    fun `a pending operation polls the operation name under our own base`() = runTest {
        val server = statusServer("""{"name":"operations/pending-op","done":false}""")

        val result = model(server).doStatus(
            operation = buildJsonObject { put("operationName", "operations/pending-op") },
            headers = mapOf("x-custom" to "value"),
        )

        assertIs<VideoStatusResult.Pending>(result)
        val call = server.request()
        assertEquals("v1beta/operations/pending-op", call.path)
        assertEquals("test-api-key", call.header("x-goog-api-key"))
        assertEquals("value", call.header("x-custom"))
    }

    @Test
    fun `a finished operation returns the clip with the key appended on our own origin only`() = runTest {
        val server = statusServer(
            doneBody(
                "https://generativelanguage.googleapis.com/files/video-456.mp4",
                "https://cdn.elsewhere.example/video-789.mp4",
            ),
        )

        val result = model(server).doStatus(
            operation = buildJsonObject { put("operationName", "operations/status-test-op") },
        )

        assertIs<VideoStatusResult.Completed>(result)
        assertEquals(
            VideoData.Url(
                url = "https://generativelanguage.googleapis.com/files/video-456.mp4?key=test-api-key",
                mediaType = "video/mp4",
            ),
            result.videos[0],
        )
        // A foreign host gets no credential: the key belongs to the origin it was issued for.
        assertEquals(
            VideoData.Url(
                url = "https://cdn.elsewhere.example/video-789.mp4",
                mediaType = "video/mp4",
            ),
            result.videos[1],
        )
        // The metadata carries the UN-keyed URIs.
        val metadataUris = result.providerMetadata
            ?.get(GOOGLE_PROVIDER_ID)?.arr("videos")
            ?.map { it.jsonObject["uri"].string() }
        assertEquals(
            listOf(
                "https://generativelanguage.googleapis.com/files/video-456.mp4",
                "https://cdn.elsewhere.example/video-789.mp4",
            ),
            metadataUris,
        )
    }

    @Test
    fun `an operation error is a Failed answer carrying the vendor's message, not an exception`() =
        runTest {
            val server = statusServer(
                """{"name":"operations/error-op","done":true,""" +
                    """"error":{"code":400,"message":"Content policy violation",""" +
                    """"status":"FAILED_PRECONDITION"}}""",
            )

            val result = model(server).doStatus(
                operation = buildJsonObject { put("operationName", "operations/error-op") },
            )

            assertIs<VideoStatusResult.Failed>(result)
            assertTrue("Content policy violation" in result.error)
        }

    @Test
    fun `done with no videos is a loud failure rather than an empty success`() = runTest {
        val server = statusServer(
            """{"name":"operations/empty-op","done":true,""" +
                """"response":{"generateVideoResponse":{"generatedSamples":[]}}}""",
        )

        assertFailsWith<NoContentGeneratedError> {
            model(server).doStatus(
                operation = buildJsonObject { put("operationName", "operations/empty-op") },
            )
        }
    }

    @Test
    fun `a handle without an operationName is rejected before any request goes out`() = runTest {
        val server = statusServer("""{}""")

        assertFailsWith<NoContentGeneratedError> {
            model(server).doStatus(operation = buildJsonObject { put("wrong", "shape") })
        }
        assertEquals(0, server.callCount)
    }
}
