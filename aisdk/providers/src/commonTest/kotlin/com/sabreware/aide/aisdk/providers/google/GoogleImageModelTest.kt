package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.int
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Gemini image generation, ported from `google-image-model.test.ts`.
 *
 * The load-bearing fact is that there is no image endpoint: the call goes to the language model with
 * `responseModalities: ["IMAGE"]`, so these tests pin the translation — the forced modality, the
 * `imageConfig` merge, the tool the `googleSearch` option becomes — rather than a request shape of its
 * own.
 */
@OptIn(ExperimentalEncodingApi::class)
class GoogleImageModelTest {

    private val imageBytes = byteArrayOf(1, 2, 3, 4)

    private fun sse(vararg objects: String) =
        TestServer.sse(*objects.map { "data: $it\n\n" }.toTypedArray())

    private fun imageResponse() = sse(
        """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png",""" +
            """"data":"${Base64.encode(imageBytes)}"}}]},"finishReason":"STOP"}],""" +
            """"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":100,""" +
            """"totalTokenCount":110}}""",
    )

    private fun model(server: TestServer, modelId: String = "gemini-2.5-flash-image") =
        GoogleImageModel(
            modelId = modelId,
            http = server.http(),
            headers = { mapOf("x-goog-api-key" to "test-api-key") },
        )

    @Test
    fun `a non-Gemini model id fails before any request — its endpoint no longer exists`() = runTest {
        val server = TestServer(imageResponse())

        assertFailsWith<UnsupportedFunctionalityError> {
            model(server, modelId = "legacy-image-model")
                .doGenerate(ImageCallOptions(prompt = "A beautiful sunset"))
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `the call is a language-model call with the modality forced to IMAGE`() = runTest {
        val server = TestServer(imageResponse())

        val result = model(server).doGenerate(
            ImageCallOptions(
                prompt = "A beautiful sunset",
                aspectRatio = "21:9",
                seed = 12345,
                providerOptions = mapOf(
                    GOOGLE_PROVIDER_ID to buildJsonObject {
                        putJsonObject("imageConfig") { put("imageSize", "4K") }
                    },
                ),
            ),
        )

        assertEquals(listOf(BinaryData.Bytes(imageBytes)), result.images)
        val call = server.request()
        assertTrue(call.path.contains("gemini-2.5-flash-image"), "path was ${call.path}")
        val config = call.bodyJson().obj("generationConfig")!!
        assertEquals("IMAGE", config.arr("responseModalities")?.single().string())
        // The caller's imageConfig merges UNDER the neutral aspectRatio, which wins on conflict.
        assertEquals("21:9", config.obj("imageConfig")?.get("aspectRatio").string())
        assertEquals("4K", config.obj("imageConfig")?.get("imageSize").string())
        assertEquals(12345, config["seed"].int())
        assertEquals(
            "A beautiful sunset",
            call.bodyJson().arr("contents")!!.first().jsonObject["parts"]!!
                .jsonArray.first().jsonObject["text"].string(),
        )
    }

    @Test
    fun `googleSearch becomes the provider tool, not a generationConfig field`() = runTest {
        val server = TestServer(imageResponse())

        model(server).doGenerate(
            ImageCallOptions(
                prompt = "A beautiful sunset",
                providerOptions = mapOf(
                    GOOGLE_PROVIDER_ID to buildJsonObject {
                        putJsonObject("googleSearch") {
                            putJsonObject("searchTypes") { putJsonObject("imageSearch") {} }
                        }
                    },
                ),
            ),
        )

        val body = server.request().bodyJson()
        val tool = body.arr("tools")?.single()?.jsonObject
        kotlin.test.assertNotNull(
            tool?.obj("googleSearch", "searchTypes", "imageSearch"),
            "expected the googleSearch option to become the google_search tool, got $body",
        )
        // Consumed into the tool — a `googleSearch` leaking into generationConfig would 400.
        assertEquals(null, body.obj("generationConfig")?.get("googleSearch"))
    }

    @Test
    fun `size has no wire field and warns instead of being silently mapped`() = runTest {
        val server = TestServer(imageResponse())

        val result = model(server).doGenerate(
            ImageCallOptions(prompt = "A beautiful sunset", size = "1024x1024"),
        )

        result.warnings.assertUnsupported(
            feature = "size",
            details = "This model does not support the `size` option. Use `aspectRatio` instead.",
        )
    }

    @Test
    fun `an input image rides the prompt as inline bytes, after the text`() = runTest {
        val server = TestServer(imageResponse())

        model(server).doGenerate(
            ImageCallOptions(
                prompt = "Add a hat to this cat",
                files = listOf(
                    ImageFile.Data(
                        data = BinaryData.Base64(Base64.encode(imageBytes)),
                        mediaType = "image/png",
                    ),
                ),
            ),
        )

        val parts = server.request().bodyJson().arr("contents")!!
            .first().jsonObject["parts"]!!.jsonArray
        assertEquals("Add a hat to this cat", parts[0].jsonObject["text"].string())
        assertEquals("image/png", parts[1].jsonObject.obj("inlineData")?.get("mimeType").string())
        assertEquals(
            Base64.encode(imageBytes),
            parts[1].jsonObject.obj("inlineData")?.get("data").string(),
        )
    }

    @Test
    fun `URL input, several images and masks are refused loudly`() = runTest {
        val server = TestServer(imageResponse())

        assertFailsWith<UnsupportedFunctionalityError> {
            model(server).doGenerate(
                ImageCallOptions(
                    prompt = "Add a hat to this cat",
                    files = listOf(ImageFile.Url(url = "https://example.com/cat.png")),
                ),
            )
        }
        assertFailsWith<UnsupportedFunctionalityError> {
            model(server).doGenerate(ImageCallOptions(prompt = "A beautiful sunset", n = 2))
        }
        assertFailsWith<UnsupportedFunctionalityError> {
            model(server).doGenerate(
                ImageCallOptions(
                    prompt = "Edit this image",
                    mask = ImageFile.Data(data = BinaryData.Base64("mask"), mediaType = "image/png"),
                ),
            )
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `usage prefers the vendor's own total over the derived sum`() = runTest {
        val server = TestServer(imageResponse())

        val result = model(server).doGenerate(ImageCallOptions(prompt = "A beautiful sunset"))

        assertEquals(10, result.usage?.inputTokens)
        assertEquals(100, result.usage?.outputTokens)
        assertEquals(110, result.usage?.totalTokens)
        assertEquals(null, result.isRetryable, "an ordinary result is left unclassified")
        // One empty metadata entry per image, the reference's own per-image shape.
        assertEquals(
            1,
            result.providerMetadata?.get(GOOGLE_PROVIDER_ID)?.arr("images")?.size,
        )
    }

    @Test
    fun `a blocked prompt returns no images rather than a parse failure`() = runTest {
        val server = TestServer(
            sse(
                """{"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"},""" +
                    """"usageMetadata":{"promptTokenCount":9,"totalTokenCount":9}}""",
            ),
        )

        val result = model(server).doGenerate(ImageCallOptions(prompt = "A blocked image prompt"))

        assertEquals(emptyList(), result.images)
        assertEquals(9, result.usage?.inputTokens)
        // The same prompt is blocked the same way: terminal for the runtime's empty-result retry.
        assertEquals(false, result.isRetryable)
    }

    @Test
    fun `a blocked prompt is terminal for the runtime's empty-result retry`() {
        // "should classify prompt blocks as terminal": the same prompt is blocked the same way, so an
        // empty result from a content filter is not worth another attempt; anything else is unclassified.
        assertEquals(false, googleImageRetryability(googleFinishReason("PROHIBITED_CONTENT", hasToolCalls = false)))
        assertEquals(null, googleImageRetryability(googleFinishReason("STOP", hasToolCalls = false)))
    }

    @Test
    fun `one image per call is the truthful ceiling`() = runTest {
        // The reference says 10 and then rejects n>1, which fails any multi-image run; a ceiling of one
        // lets a runtime fan nine images out as nine calls that all succeed.
        assertEquals(1, model(TestServer(imageResponse())).maxImagesPerCall())
    }
}
