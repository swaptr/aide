package com.sabreware.aide.aisdk.providers.bedrock

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.NoImageGeneratedError
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.AwsCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Nova Canvas over `InvokeModel`, ported from the reference's `amazon-bedrock-image-model.test.ts`.
 *
 * Every request-body assertion is the reference's inline snapshot compared WHOLE, because the
 * task-type inference and the per-task parameter blocks are exactly the shape a per-key read is blind
 * to: a `maskPrompt` filed under the wrong task key is a 400, and no single-key assertion sees it.
 */
class BedrockImageModelTest {

    private val prompt = "A cute baby sea otter"

    private fun model(server: TestServer, modelId: String = NOVA_CANVAS) = BedrockImageModel(
        modelId = modelId,
        http = server.http().withErrorStructure(BedrockErrors),
        credentials = { AwsCredentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY") },
        region = "us-east-1",
        now = { 1_705_314_645_000L },
    )

    private fun twoImages() = TestServer(TestServer.json(BedrockModalityFixtures.IMAGES_TWO))

    private fun edited() = TestServer(TestServer.json(BedrockModalityFixtures.IMAGE_EDITED))

    /** Options under the reference's legacy `bedrock` key, which most of its tests use. */
    private fun bedrock(build: JsonObjectBuilder.() -> Unit) = mapOf("bedrock" to buildJsonObject(build))

    /** The reference's PNG magic bytes, `[137, 80, 78, 71]`, which base64 as `iVBORw==`. */
    private val png = ImageFile.Data(BinaryData.Bytes(byteArrayOf(137.toByte(), 80, 78, 71)), "image/png")

    /** `[255, 255, 255, 0]`, which base64 as `////AA==`. */
    private val mask = ImageFile.Data(BinaryData.Bytes(byteArrayOf(-1, -1, -1, 0)), "image/png")

    @Test
    fun `passes the model and the settings, under the legacy bedrock key`() = runTest {
        val server = twoImages()

        model(server).doGenerate(
            ImageCallOptions(
                prompt = prompt,
                n = 1,
                size = "1024x1024",
                seed = 1234,
                providerOptions = bedrock {
                    put("negativeText", "bad")
                    put("quality", "premium")
                    put("cfgScale", 1.2)
                },
            ),
        )

        assertEquals(
            "https://bedrock-runtime.us-east-1.amazonaws.com/model/amazon.nova-canvas-v1%3A0/invoke",
            server.request().url,
        )
        server.request().assertBodyEquals(
            """
            {
              "imageGenerationConfig": {
                "cfgScale": 1.2,
                "height": 1024,
                "numberOfImages": 1,
                "quality": "premium",
                "seed": 1234,
                "width": 1024
              },
              "taskType": "TEXT_IMAGE",
              "textToImageParams": {
                "negativeText": "bad",
                "text": "A cute baby sea otter"
              }
            }
            """,
        )
    }

    @Test
    fun `reads options under our own provider id, style included`() = runTest {
        val server = twoImages()

        model(server).doGenerate(
            ImageCallOptions(
                prompt = prompt,
                size = "1024x1024",
                seed = 1234,
                providerOptions = mapOf(
                    BEDROCK_PROVIDER_ID to buildJsonObject {
                        put("negativeText", "bad")
                        put("quality", "premium")
                        put("cfgScale", 1.2)
                        put("style", "PHOTOREALISM")
                    },
                ),
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "imageGenerationConfig": {
                "cfgScale": 1.2,
                "height": 1024,
                "numberOfImages": 1,
                "quality": "premium",
                "seed": 1234,
                "width": 1024
              },
              "taskType": "TEXT_IMAGE",
              "textToImageParams": {
                "negativeText": "bad",
                "style": "PHOTOREALISM",
                "text": "A cute baby sea otter"
              }
            }
            """,
        )
    }

    @Test
    fun `our provider id wins over the legacy bedrock key, wholesale`() = runTest {
        val server = twoImages()

        model(server).doGenerate(
            ImageCallOptions(
                prompt = prompt,
                providerOptions = mapOf(
                    "bedrock" to buildJsonObject {
                        put("negativeText", "legacy")
                        put("quality", "standard")
                        put("cfgScale", 1)
                        put("style", "DESIGN_SKETCH")
                    },
                    BEDROCK_PROVIDER_ID to buildJsonObject {
                        put("negativeText", "preferred")
                        put("quality", "premium")
                        put("cfgScale", 2)
                        put("style", "PHOTOREALISM")
                    },
                ),
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "imageGenerationConfig": {
                "cfgScale": 2,
                "numberOfImages": 1,
                "quality": "premium"
              },
              "taskType": "TEXT_IMAGE",
              "textToImageParams": {
                "negativeText": "preferred",
                "style": "PHOTOREALISM",
                "text": "A cute baby sea otter"
              }
            }
            """,
        )
    }

    @Test
    fun `caller headers are merged and signed along with the request`() = runTest {
        val server = twoImages()

        model(server).doGenerate(
            ImageCallOptions(
                prompt = prompt,
                headers = mapOf("options-header" to "options-value"),
            ),
        )

        val request = server.request()
        request.assertHeader("options-header", "options-value")
        request.assertHeader("content-type", "application/json")
        request.assertHeader("x-amz-date", "20240115T103045Z")
        val auth = request.header("Authorization")!!
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 "), auth)
        assertTrue(auth.contains("/20240115/us-east-1/bedrock/aws4_request"), auth)
        // The caller's header is in the signed set: added after signing it would be a 403.
        assertTrue(auth.contains("SignedHeaders=content-type;host;options-header;x-amz-date"), auth)
    }

    @Test
    fun `nova canvas takes five per call, its cross-region profile too, and anything else one`() = runTest {
        assertEquals(5, model(twoImages()).maxImagesPerCall())
        assertEquals(5, model(twoImages(), modelId = "us.amazon.nova-canvas-v1:0").maxImagesPerCall())
        assertEquals(1, model(twoImages(), modelId = "unknown-model").maxImagesPerCall())
    }

    @Test
    fun `warns on aspectRatio, which this model has no field for`() = runTest {
        val result = model(twoImages()).doGenerate(
            ImageCallOptions(prompt = prompt, size = "1024x1024", aspectRatio = "1:1"),
        )

        assertEquals(1, result.warnings.size)
        result.warnings.assertUnsupported(
            "aspectRatio",
            "This model does not support aspect ratio. Use `size` instead.",
        )
    }

    @Test
    fun `extracts the generated images as base64`() = runTest {
        val result = model(twoImages()).doGenerate(ImageCallOptions(prompt = prompt))

        assertEquals(
            listOf(BinaryData.Base64("base64-image-1"), BinaryData.Base64("base64-image-2")),
            result.images,
        )
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `response carries the model id, the pinned timestamp and the headers`() = runTest {
        val result = model(twoImages()).doGenerate(ImageCallOptions(prompt = prompt, size = "1024x1024"))

        assertEquals(NOVA_CANVAS, result.response.modelId)
        assertEquals(TestServer.FIXED_NOW, result.response.timestamp)
        assertEquals("application/json", result.response.headers?.get("content-type"))
        assertTrue(result.request?.body!!.contains("\"taskType\":\"TEXT_IMAGE\""), result.request?.body)
    }

    @Test
    fun `style is absent from the wire when not provided`() = runTest {
        val server = twoImages()

        model(server).doGenerate(
            ImageCallOptions(
                prompt = prompt,
                size = "1024x1024",
                seed = 1234,
                providerOptions = bedrock { put("quality", "standard") },
            ),
        )

        server.request().assertBodyJson { body ->
            assertNull(body.obj("textToImageParams")?.get("style"))
            assertEquals("standard", body.obj("imageGenerationConfig")?.get("quality").string())
        }
    }

    @Test
    fun `a seed of zero is sent, where the reference's truthiness check drops it`() = runTest {
        val server = twoImages()

        model(server).doGenerate(ImageCallOptions(prompt = prompt, seed = 0))

        server.request().assertBodyJson { body ->
            assertEquals("0", body.obj("imageGenerationConfig")?.get("seed").string())
        }
    }

    @Test
    fun `a moderated request fails with the vendor's reasons`() = runTest {
        val server = TestServer(TestServer.json(BedrockModalityFixtures.IMAGE_MODERATED))

        val error = assertFailsWith<NoImageGeneratedError> {
            model(server).doGenerate(ImageCallOptions(prompt = "Generate something that triggers moderation"))
        }

        assertEquals("Amazon Bedrock request was moderated: Derivative Works Filter", error.message)
        assertEquals(NOVA_CANVAS, error.responses.single().modelId)
    }

    @Test
    fun `an empty image list fails rather than returning nothing`() = runTest {
        val server = TestServer(TestServer.json(BedrockModalityFixtures.IMAGES_EMPTY))

        val error = assertFailsWith<NoImageGeneratedError> {
            model(server).doGenerate(ImageCallOptions(prompt = "Generate an image"))
        }

        assertTrue(error.message!!.startsWith("Amazon Bedrock returned no images"), error.message)
    }

    @Test
    fun `a rejected request surfaces Bedrock's own message`() = runTest {
        val server = TestServer(
            TestServer.error(400, """{"message":"Malformed input request","type":"ValidationException"}"""),
        )

        val error = assertFailsWith<APICallError> {
            model(server).doGenerate(ImageCallOptions(prompt = prompt))
        }

        assertEquals(400, error.statusCode)
        assertTrue(error.message!!.contains("Malformed input request"), error.message)
    }

    @Test
    fun `a mask with no input image warns instead of vanishing`() = runTest {
        val result = model(twoImages()).doGenerate(ImageCallOptions(prompt = prompt, mask = mask))

        result.warnings.assertUnsupported("mask")
    }

    @Test
    fun `a malformed size warns and goes out without dimensions`() = runTest {
        val server = twoImages()

        val result = model(server).doGenerate(ImageCallOptions(prompt = prompt, size = "large"))

        assertTrue(result.warnings.isNotEmpty(), result.warnings.toString())
        server.request().assertBodyJson { body ->
            assertNull(body.obj("imageGenerationConfig")?.get("width"))
        }
    }

    // --- Image editing ---------------------------------------------------------------------------

    @Test
    fun `inpainting with a maskPrompt`() = runTest {
        val server = edited()

        model(server).doGenerate(
            ImageCallOptions(
                prompt = "a cute corgi dog",
                files = listOf(png),
                seed = 42,
                providerOptions = bedrock {
                    put("maskPrompt", "cat")
                    put("quality", "standard")
                    // The reference writes `7.0`; JSON has no such distinction and its snapshot says 7.
                    put("cfgScale", 7)
                },
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "imageGenerationConfig": {
                "cfgScale": 7,
                "numberOfImages": 1,
                "quality": "standard",
                "seed": 42
              },
              "inPaintingParams": {
                "image": "iVBORw==",
                "maskPrompt": "cat",
                "text": "a cute corgi dog"
              },
              "taskType": "INPAINTING"
            }
            """,
        )
    }

    @Test
    fun `inpainting with a mask image`() = runTest {
        val server = edited()

        model(server).doGenerate(
            ImageCallOptions(
                prompt = "A sunlit indoor lounge area with a pool containing a flamingo",
                files = listOf(png),
                mask = mask,
                providerOptions = bedrock { put("quality", "standard") },
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "imageGenerationConfig": {
                "numberOfImages": 1,
                "quality": "standard"
              },
              "inPaintingParams": {
                "image": "iVBORw==",
                "maskImage": "////AA==",
                "text": "A sunlit indoor lounge area with a pool containing a flamingo"
              },
              "taskType": "INPAINTING"
            }
            """,
        )
    }

    @Test
    fun `base64 file data passes through untouched`() = runTest {
        val server = edited()
        val base64Image = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"

        model(server).doGenerate(
            ImageCallOptions(
                prompt = "Edit this image",
                files = listOf(ImageFile.Data(BinaryData.Base64(base64Image), "image/png")),
                providerOptions = bedrock { put("maskPrompt", "background") },
            ),
        )

        server.request().assertBodyJson { body ->
            assertEquals("INPAINTING", body["taskType"].string())
            assertEquals(base64Image, body.obj("inPaintingParams")?.get("image").string())
        }
    }

    @Test
    fun `negativeText rides in the inpainting params`() = runTest {
        val server = edited()

        model(server).doGenerate(
            ImageCallOptions(
                prompt = "a beautiful garden",
                files = listOf(png),
                providerOptions = bedrock {
                    put("maskPrompt", "sky")
                    put("negativeText", "clouds, rain")
                },
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "imageGenerationConfig": {
                "numberOfImages": 1
              },
              "inPaintingParams": {
                "image": "iVBORw==",
                "maskPrompt": "sky",
                "negativeText": "clouds, rain",
                "text": "a beautiful garden"
              },
              "taskType": "INPAINTING"
            }
            """,
        )
    }

    @Test
    fun `extracts the edited image`() = runTest {
        val result = model(edited()).doGenerate(
            ImageCallOptions(
                prompt = "Edit this image",
                files = listOf(png),
                providerOptions = bedrock { put("maskPrompt", "object") },
            ),
        )

        assertEquals(listOf(BinaryData.Base64("edited-image-base64")), result.images)
    }

    @Test
    fun `a URL input is refused, not fetched`() = runTest {
        val server = edited()

        val error = assertFailsWith<UnsupportedFunctionalityError> {
            model(server).doGenerate(
                ImageCallOptions(
                    prompt = "Edit this image",
                    files = listOf(ImageFile.Url("https://example.com/image.png")),
                ),
            )
        }

        assertTrue(
            error.message!!.startsWith("URL-based images are not supported for Amazon Bedrock image editing."),
            error.message,
        )
        assertEquals(0, server.callCount)
    }

    @Test
    fun `outpainting with a mask image and a mode`() = runTest {
        val server = edited()

        model(server).doGenerate(
            ImageCallOptions(
                prompt = "Extend the background with a beautiful sunset",
                files = listOf(png),
                mask = mask,
                providerOptions = bedrock {
                    put("taskType", "OUTPAINTING")
                    put("outPaintingMode", "DEFAULT")
                    put("negativeText", "bad quality")
                },
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "imageGenerationConfig": {
                "numberOfImages": 1
              },
              "outPaintingParams": {
                "image": "iVBORw==",
                "maskImage": "////AA==",
                "negativeText": "bad quality",
                "outPaintingMode": "DEFAULT",
                "text": "Extend the background with a beautiful sunset"
              },
              "taskType": "OUTPAINTING"
            }
            """,
        )
    }

    @Test
    fun `outpainting with a maskPrompt`() = runTest {
        val server = edited()

        model(server).doGenerate(
            ImageCallOptions(
                prompt = "Replace the background with mountains",
                files = listOf(png),
                providerOptions = bedrock {
                    put("taskType", "OUTPAINTING")
                    put("maskPrompt", "background")
                },
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "imageGenerationConfig": {
                "numberOfImages": 1
              },
              "outPaintingParams": {
                "image": "iVBORw==",
                "maskPrompt": "background",
                "text": "Replace the background with mountains"
              },
              "taskType": "OUTPAINTING"
            }
            """,
        )
    }

    @Test
    fun `background removal sends the image and nothing else`() = runTest {
        val server = edited()

        model(server).doGenerate(
            ImageCallOptions(
                prompt = null,
                files = listOf(png),
                providerOptions = bedrock { put("taskType", "BACKGROUND_REMOVAL") },
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "backgroundRemovalParams": {
                "image": "iVBORw=="
              },
              "taskType": "BACKGROUND_REMOVAL"
            }
            """,
        )
    }

    @Test
    fun `image variation from one image, with size and count`() = runTest {
        val server = edited()

        model(server).doGenerate(
            ImageCallOptions(
                prompt = "Create a variation in anime style",
                files = listOf(png),
                n = 3,
                size = "512x512",
                providerOptions = bedrock {
                    put("taskType", "IMAGE_VARIATION")
                    put("similarityStrength", 0.7)
                    put("negativeText", "bad quality, low resolution")
                },
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "imageGenerationConfig": {
                "height": 512,
                "numberOfImages": 3,
                "width": 512
              },
              "imageVariationParams": {
                "images": [
                  "iVBORw=="
                ],
                "negativeText": "bad quality, low resolution",
                "similarityStrength": 0.7,
                "text": "Create a variation in anime style"
              },
              "taskType": "IMAGE_VARIATION"
            }
            """,
        )
    }

    @Test
    fun `image variation from several images`() = runTest {
        val server = edited()
        val jpeg = ImageFile.Data(BinaryData.Bytes(byteArrayOf(-1, -40, -1, -32)), "image/jpeg")

        model(server).doGenerate(
            ImageCallOptions(
                prompt = "Combine these images into one cohesive scene",
                files = listOf(png, jpeg),
                providerOptions = bedrock { put("taskType", "IMAGE_VARIATION") },
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "imageGenerationConfig": {
                "numberOfImages": 1
              },
              "imageVariationParams": {
                "images": [
                  "iVBORw==",
                  "/9j/4A=="
                ],
                "text": "Combine these images into one cohesive scene"
              },
              "taskType": "IMAGE_VARIATION"
            }
            """,
        )
    }

    @Test
    fun `files alone infer IMAGE_VARIATION`() = runTest {
        val server = edited()

        model(server).doGenerate(ImageCallOptions(prompt = "Create variations", files = listOf(png)))

        server.request().assertBodyJson { body -> assertEquals("IMAGE_VARIATION", body["taskType"].string()) }
    }

    @Test
    fun `files with a mask infer INPAINTING`() = runTest {
        val server = edited()

        model(server).doGenerate(ImageCallOptions(prompt = "Edit masked area", files = listOf(png), mask = mask))

        server.request().assertBodyJson { body -> assertEquals("INPAINTING", body["taskType"].string()) }
    }

    @Test
    fun `files with a maskPrompt infer INPAINTING`() = runTest {
        val server = edited()

        model(server).doGenerate(
            ImageCallOptions(
                prompt = "Edit the cat",
                files = listOf(png),
                providerOptions = bedrock { put("maskPrompt", "cat") },
            ),
        )

        server.request().assertBodyJson { body -> assertEquals("INPAINTING", body["taskType"].string()) }
    }

    @Test
    fun `an unknown task type is refused before anything is sent`() = runTest {
        val server = edited()

        val error = assertFailsWith<InvalidArgumentError> {
            model(server).doGenerate(
                ImageCallOptions(
                    prompt = prompt,
                    files = listOf(png),
                    providerOptions = bedrock { put("taskType", "VIRTUAL_TRY_ON") },
                ),
            )
        }

        assertEquals("taskType", error.argument)
        assertEquals(0, server.callCount)
    }

    @Test
    fun `the provider serves images and reranking alongside chat and embeddings`() {
        val provider = BedrockProvider(
            client = HttpClient(MockEngine { respond(content = ByteArray(0)) }),
            credentials = { AwsCredentials("a", "b") },
            region = "us-east-1",
            now = { 0L },
        )

        assertTrue(provider.imageModel(NOVA_CANVAS) is BedrockImageModel)
        assertTrue(provider.rerankingModel("cohere.rerank-v3-5:0") is BedrockRerankingModel)
        assertEquals(BEDROCK_PROVIDER_ID, provider.imageModel(NOVA_CANVAS).provider)
    }

    private companion object {
        const val NOVA_CANVAS = "amazon.nova-canvas-v1:0"
    }
}
