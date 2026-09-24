package com.sabreware.aide.aisdk.providers.quiverai

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * QuiverAI SVG generation, against `quiverai-image-model.test.ts` and its recorded fixture.
 *
 * The trap under test: the "images" are SVG DOCUMENTS. The bytes are UTF-8 markup, the per-image
 * `mime_type` is the only thing that says so, and the pixel-era options all warn.
 */
class QuiverAiConformanceTest {

    /** The reference's own `generateSvgResponseFixture`, kept verbatim. */
    private val generateFixture =
        """{"id":"svg-gen-1","created":1713374400,"data":[{"svg":"<svg viewBox=\"0 0 10 10\"><rect width=\"10\" height=\"10\"/></svg>","mime_type":"image/svg+xml"}],"usage":{"total_tokens":21,"input_tokens":12,"output_tokens":9}}"""

    private fun provider(server: TestServer) = QuiverAiProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-key",
    )

    @Test
    fun `generate posts to svgs generations with the sampling knobs snake-cased`() = runTest {
        val server = TestServer(TestServer.json(generateFixture))

        provider(server).imageModel("arrow-1.1").doGenerate(
            ImageCallOptions(
                prompt = "a red square",
                n = 1,
                providerOptions = mapOf(
                    QUIVERAI_PROVIDER_ID to buildJsonObject {
                        put("instructions", "flat design")
                        put("temperature", 0.7)
                        put("topP", 0.9)
                        put("maxOutputTokens", 4096)
                    },
                ),
            ),
        )

        val call = server.request()
        assertEquals("https://api.quiver.ai/v1/svgs/generations", call.url)
        assertEquals("Bearer test-key", call.header("Authorization"))
        call.assertBodyEquals(
            """{"model":"arrow-1.1","n":1,"prompt":"a red square","temperature":0.7,"top_p":0.9,"max_output_tokens":4096,"stream":false,"instructions":"flat design"}""",
        )
    }

    @Test
    fun `the SVG document comes back as its own bytes with the mime type in metadata`() = runTest {
        val server = TestServer(TestServer.json(generateFixture))

        val result = provider(server).imageModel("arrow-1.1")
            .doGenerate(ImageCallOptions(prompt = "a red square", n = 1))

        val expected = "<svg viewBox=\"0 0 10 10\"><rect width=\"10\" height=\"10\"/></svg>"
        assertEquals(listOf<BinaryData>(BinaryData.Bytes(expected.encodeToByteArray())), result.images)
        val imageMeta = result.providerMetadata
            ?.get(QUIVERAI_PROVIDER_ID)?.jsonObject?.get("images")?.jsonArray?.first()?.jsonObject
        assertEquals("image/svg+xml", imageMeta?.get("mimeType")?.jsonPrimitive?.content)
        assertEquals(12, result.usage?.inputTokens)
        assertEquals(9, result.usage?.outputTokens)
        assertEquals(21, result.usage?.totalTokens)
        assertEquals("svg-gen-1", result.response?.id)
    }

    @Test
    fun `vectorize posts the single input image to svgs vectorizations`() = runTest {
        val server = TestServer(TestServer.json(generateFixture))

        provider(server).imageModel("arrow-1.1").doGenerate(
            ImageCallOptions(
                n = 1,
                prompt = null,
                files = listOf(ImageFile.Url("https://example.com/in.png")),
                providerOptions = mapOf(
                    QUIVERAI_PROVIDER_ID to buildJsonObject {
                        put("operation", "vectorize")
                        put("autoCrop", true)
                        put("targetSize", 512)
                    },
                ),
            ),
        )

        val call = server.request()
        assertEquals("https://api.quiver.ai/v1/svgs/vectorizations", call.url)
        call.assertBodyEquals(
            """{"model":"arrow-1.1","n":1,"image":{"url":"https://example.com/in.png"},"stream":false,"auto_crop":true,"target_size":512}""",
        )
    }

    @Test
    fun `the per-model reference ceiling is enforced before the request`() = runTest {
        val server = TestServer(TestServer.json(generateFixture))
        val file = ImageFile.Data(BinaryData.Base64("aW1n"), mediaType = "image/png")

        assertFailsWith<InvalidArgumentError> {
            provider(server).imageModel("arrow-1.1").doGenerate(
                ImageCallOptions(prompt = "x", n = 1, files = List(5) { file }),
            )
        }
        // arrow-1.1-max documents 16; five references are fine there.
        provider(server).imageModel("arrow-1.1-max").doGenerate(
            ImageCallOptions(prompt = "x", n = 1, files = List(5) { file }),
        )
        // The over-limit call above never reached the wire; the max-model call did.
        assertEquals(1, server.callCount)
    }

    @Test
    fun `vectorize without exactly one image is an argument error, not a vendor 400`() = runTest {
        val server = TestServer(TestServer.json(generateFixture))
        val vectorize = mapOf(
            QUIVERAI_PROVIDER_ID to buildJsonObject { put("operation", "vectorize") },
        )

        assertFailsWith<InvalidArgumentError> {
            provider(server).imageModel("arrow-1.1")
                .doGenerate(ImageCallOptions(n = 1, providerOptions = vectorize))
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `the pixel-era options all warn`() = runTest {
        val server = TestServer(TestServer.json(generateFixture))

        val result = provider(server).imageModel("arrow-1.1").doGenerate(
            ImageCallOptions(
                prompt = "x",
                n = 1,
                size = "1024x1024",
                aspectRatio = "1:1",
                seed = 7,
                mask = ImageFile.Data(BinaryData.Base64("bQ=="), mediaType = "image/png"),
            ),
        )

        result.warnings.assertUnsupported("size")
        result.warnings.assertUnsupported("aspectRatio")
        result.warnings.assertUnsupported("seed")
        result.warnings.assertUnsupported("mask")
    }
}
