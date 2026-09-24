package com.sabreware.aide.aisdk.providers.async

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.RetryError
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.fal.FalProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * fal's image model, against the reference's own `fal-image-model.test.ts`.
 *
 * The reason this file exists rather than another handful of cases in [AsyncVendorsTest]: the reference
 * asserts the WHOLE request body on every one of these, and a whole-body assertion is the only kind that
 * can see a field we send but should not, or one we drop. Every case below is the reference's, with the
 * bodies it recorded kept verbatim in [FalFixtures].
 */
class FalImageConformanceTest {

    private val prompt = "A cute baby sea otter"
    private val modelId = "fal-ai/qwen-image"
    private val baseUrl = "https://api.example.com"

    /** The picture bytes the fal response URL resolves to; every generate is a POST then a GET. */
    private val imageBytes = "test-binary-content".encodeToByteArray()

    private fun server(body: String = FalFixtures.ONE_IMAGE, imageCount: Int = 1): TestServer =
        TestServer(
            TestServer.json(body),
            *Array(imageCount) { TestServer.bytes(imageBytes, "image/png") },
        )

    private fun TestServer.model(apiKey: String = "test-key") =
        FalProvider(HttpClient(engine()), apiKey = apiKey, baseUrl = baseUrl).imageModel(modelId)

    private fun options(
        prompt: String? = this.prompt,
        n: Int = 1,
        size: String? = null,
        aspectRatio: String? = null,
        seed: Int? = null,
        files: List<ImageFile>? = null,
        mask: ImageFile? = null,
        fal: JsonObject? = null,
        headers: Map<String, String>? = null,
    ) = ImageCallOptions(
        prompt = prompt,
        n = n,
        size = size,
        aspectRatio = aspectRatio,
        seed = seed,
        files = files,
        mask = mask,
        providerOptions = fal?.let { mapOf("fal" to it) },
        headers = headers,
    )

    // --- request shape --------------------------------------------------------------------------

    @Test
    fun `size becomes a width-height object and every other parameter rides beside it`() = runTest {
        val server = server()
        server.model().doGenerate(
            options(
                size = "1024x1024",
                seed = 123,
                fal = buildJsonObject { put("additional_param", "value") },
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "prompt": "A cute baby sea otter",
              "seed": 123,
              "image_size": { "width": 1024, "height": 1024 },
              "num_images": 1,
              "additional_param": "value"
            }
            """,
        )
    }

    @Test
    fun `camelCase provider options are renamed to fal's snake_case, and nothing else is touched`() =
        runTest {
            val server = server()
            val result = server.model().doGenerate(
                options(
                    fal = buildJsonObject {
                        put("imageUrl", "https://example.com/image.png")
                        put("guidanceScale", 7.5)
                        put("numInferenceSteps", 50)
                        put("enableSafetyChecker", false)
                    },
                ),
            )

            server.request().assertBodyEquals(
                """
                {
                  "prompt": "A cute baby sea otter",
                  "num_images": 1,
                  "image_url": "https://example.com/image.png",
                  "guidance_scale": 7.5,
                  "num_inference_steps": 50,
                  "enable_safety_checker": false
                }
                """,
            )
            result.warnings.assertNoWarnings()
        }

    /**
     * The snake_case spellings still reach the wire unchanged — they are what fal wants — but a caller
     * using them is on a deprecated path and the reference says so. Without the warning the option keeps
     * working right up until the vendor drops it, with nothing anywhere having said it would.
     */
    @Test
    fun `a deprecated snake_case provider option still sends, and names its camelCase replacement`() =
        runTest {
            val server = server()
            val result = server.model().doGenerate(
                options(
                    fal = buildJsonObject {
                        put("image_url", "https://example.com/image.png")
                        put("guidance_scale", 7.5)
                        put("num_inference_steps", 50)
                    },
                ),
            )

            server.request().assertBodyEquals(
                """
                {
                  "prompt": "A cute baby sea otter",
                  "num_images": 1,
                  "image_url": "https://example.com/image.png",
                  "guidance_scale": 7.5,
                  "num_inference_steps": 50
                }
                """,
            )

            val deprecated = result.warnings.filterIsInstance<Warning.Deprecated>()
            assertEquals(
                listOf("guidance_scale", "image_url", "num_inference_steps"),
                deprecated.map { it.setting }.sorted(),
            )
            assertEquals(
                "fal provider options are camelCase; use 'imageUrl' instead.",
                deprecated.first { it.setting == "image_url" }.message,
            )
        }

    @Test
    fun `an aspect ratio becomes one of fal's preset names, never a WxH string`() = runTest {
        val server = server()
        server.model().doGenerate(options(aspectRatio = "16:9"))

        server.request().assertBodyEquals(
            """
            {
              "prompt": "A cute baby sea otter",
              "image_size": "landscape_16_9",
              "num_images": 1
            }
            """,
        )
    }

    /**
     * fal names a portrait preset after its landscape ratio and then says which way up it is, so `9:16`
     * is `portrait_16_9`. "Correcting" it to `portrait_9_16` is a 422 on every portrait generation.
     */
    @Test
    fun `the portrait presets keep fal's backwards spelling`() = runTest {
        val server = server()
        server.model().doGenerate(options(aspectRatio = "9:16"))
        assertEquals(
            "portrait_16_9",
            server.request().bodyJson()["image_size"]?.toString()?.trim('"'),
        )
    }

    @Test
    fun `a ratio fal has no preset for becomes explicit pixels`() = runTest {
        val server = server()
        server.model().doGenerate(options(aspectRatio = "21:9"))

        server.request().assertBodyEquals(
            """
            {
              "prompt": "A cute baby sea otter",
              "image_size": { "width": 2560, "height": 1080 },
              "num_images": 1
            }
            """,
        )
    }

    @Test
    fun `the call's own headers ride alongside the auth header fal requires`() = runTest {
        val server = server()
        server.model().doGenerate(
            options(headers = mapOf("Custom-Request-Header" to "request-header-value")),
        )

        val call = server.request()
        // `Key`, not `Bearer` and not a bare key: the other two are a 401 that reads as a bad credential.
        call.assertHeader("Authorization", "Key test-key")
        call.assertHeader("Custom-Request-Header", "request-header-value")
    }

    // --- responses ------------------------------------------------------------------------------

    @Test
    fun `the model id and the response headers come back on the result`() = runTest {
        val server = server()
        val result = server.model().doGenerate(options())

        assertEquals(modelId, result.response.modelId)
        assertTrue(result.response.headers!!.isNotEmpty())
        // Populated rather than pinned: this test builds the provider itself, so the transport uses its
        // own clock. That it is non-null at all is the point — the field is advertised on every result
        // and, until the transport was given a real default clock, was null on every one of them.
        assertNotNull(result.response.timestamp)
    }

    @Test
    fun `a lora response keeps the debug latents and folds the safety flag into each image`() =
        runTest {
            val server = server(FalFixtures.LORA)
            val result = server.model().doGenerate(options())

            assertEquals(
                parseJsonObject(
                    """
                    {
                      "images": [
                        {
                          "width": 1024,
                          "height": 1024,
                          "contentType": "image/png",
                          "fileName": "<image file_name>",
                          "fileData": "<image file_data>",
                          "fileSize": 123,
                          "nsfw": true
                        }
                      ],
                      "seed": 123,
                      "debug_latents": {
                        "url": "<debug_latents url>",
                        "content_type": "<debug_latents content_type>",
                        "file_name": "<debug_latents file_name>",
                        "file_data": "<debug_latents file_data>",
                        "file_size": 123
                      },
                      "debug_per_pass_latents": {
                        "url": "<debug_per_pass_latents url>",
                        "content_type": "<debug_per_pass_latents content_type>",
                        "file_name": "<debug_per_pass_latents file_name>",
                        "file_data": "<debug_per_pass_latents file_data>",
                        "file_size": 456
                      }
                    }
                    """,
                ),
                result.providerMetadata!!["fal"],
            )
        }

    /**
     * The prompt fal echoes back is the one we sent, not a revised one, so carrying it as metadata would
     * be a field that looks informative and never is. The reference drops it; so must we.
     */
    @Test
    fun `the echoed prompt is not carried as metadata`() = runTest {
        val server = server(FalFixtures.LORA)
        val result = server.model().doGenerate(options())
        assertTrue("prompt" !in result.providerMetadata!!["fal"]!!)
    }

    @Test
    fun `the older nsfw_content_detected spelling lands under the same per-image key`() = runTest {
        val server = server(FalFixtures.LCM)
        val result = server.model().doGenerate(options())

        assertEquals(
            parseJsonObject(
                """
                {
                  "images": [{ "width": 1024, "height": 1024, "nsfw": false }],
                  "seed": 123,
                  "num_inference_steps": 456
                }
                """,
            ),
            result.providerMetadata!!["fal"],
        )
    }

    @Test
    fun `a model that answers with a single image object is read, not dropped`() = runTest {
        val server = server(FalFixtures.SINGLE_IMAGE_KEY)
        val result = server.model().doGenerate(options())

        assertEquals(1, result.images.size)
        assertIs<BinaryData.Bytes>(result.images.first())
    }

    @Test
    fun `two images are two downloads and two results`() = runTest {
        val server = server(FalFixtures.TWO_IMAGES, imageCount = 2)
        val result = server.model().doGenerate(options(n = 2))

        assertEquals(2, result.images.size)
        assertEquals(3, server.callCount)
        result.images.forEach { assertTrue(imageBytes contentEquals (it as BinaryData.Bytes).value) }
    }

    /**
     * fal fills these with JSON null rather than omitting them. A parser that treats null as absent
     * silently reports a file name of "unknown" for an image the vendor explicitly said has none.
     */
    @Test
    fun `null file name and file size survive as null rather than disappearing`() = runTest {
        val server = server(FalFixtures.NULL_FILE_FIELDS)
        val result = server.model().doGenerate(options())

        assertEquals(
            parseJsonObject(
                """
                {
                  "images": [
                    {
                      "width": 944,
                      "height": 1104,
                      "contentType": "image/png",
                      "fileName": null,
                      "fileSize": null,
                      "nsfw": false
                    }
                  ],
                  "timings": { "inference": 5.875932216644287 },
                  "seed": 328395684
                }
                """,
            ),
            result.providerMetadata!!["fal"],
        )
    }

    @Test
    fun `an empty timings object is carried rather than treated as a parse failure`() = runTest {
        val server = server(FalFixtures.EMPTY_TIMINGS)
        val result = server.model().doGenerate(options())

        val fal = result.providerMetadata!!["fal"]!!
        assertEquals(parseJsonObject("{}"), fal["timings"])
        assertEquals("235205040", fal["seed"].toString())
    }

    @Test
    fun `null dimensions are carried, and an unknown top-level key rides along`() = runTest {
        val server = server(FalFixtures.NULL_DIMENSIONS)
        val result = server.model().doGenerate(options())

        assertEquals(
            parseJsonObject(
                """
                {
                  "images": [
                    {
                      "width": null,
                      "height": null,
                      "contentType": "image/png",
                      "fileName": "output.png",
                      "fileSize": 663399
                    }
                  ],
                  "description": "here is an image with null width and height"
                }
                """,
            ),
            result.providerMetadata!!["fal"],
        )
    }

    // --- errors ---------------------------------------------------------------------------------

    /**
     * FastAPI answers a rejected parameter with a list of `{loc, msg}` pairs, and the useful half is
     * which field it rejected. Flattening that to "HTTP 400" reads as a server fault rather than as the
     * one field the caller has to change.
     */
    @Test
    fun `a validation error names the field fal rejected`() = runTest {
        val server = TestServer(TestServer.json(FalFixtures.VALIDATION_ERROR, io.ktor.http.HttpStatusCode.BadRequest))
        val error = assertFailsWith<APICallError> { server.model().doGenerate(options()) }

        // The message carries our own `HTTP {code} from {url}` prefix, which the reference does not; the
        // part that matters is that the FastAPI envelope was unpacked down to the offending field.
        assertTrue(error.message!!.endsWith("prompt: Invalid prompt"), error.message!!)
        assertEquals(400, error.statusCode)
        assertEquals("$baseUrl/$modelId", error.url)
    }

    /**
     * fal's quota refusal, from the reference's own `fal-error.test.ts`: a `{error: {message, code}}`
     * envelope whose message is itself a JSON document, newlines and all. It must come out intact —
     * an excerpt of the raw body instead means the envelope was never unwrapped, and the upstream
     * status the caller has to act on is buried in it.
     *
     * A 429 is retryable, so what escapes is the `RetryError` the port's own ladder produces once the
     * attempts run out, carrying the last failure as its cause.
     */
    @Test
    fun `a quota refusal keeps fal's nested message verbatim, newlines included`() = runTest {
        val nested = "{\n  \"error\": {\n    \"code\": 429,\n    " +
            "\"message\": \"Resource has been exhausted (e.g. check quota).\",\n    " +
            "\"status\": \"RESOURCE_EXHAUSTED\"\n  }\n}\n"
        val body = buildJsonObject {
            putJsonObject("error") {
                put("message", nested)
                put("code", 429)
            }
        }
        val server = TestServer(
            TestServer.json(body.toString(), io.ktor.http.HttpStatusCode.TooManyRequests),
        )
        val retry = assertFailsWith<RetryError> { server.model().doGenerate(options()) }
        val error = assertIs<APICallError>(retry.errors.last())

        assertEquals(429, error.statusCode)
        assertTrue(error.message!!.endsWith(nested), error.message!!)
    }

    @Test
    fun `a success carrying no image at all is a generation failure, not an empty list`() = runTest {
        val server = TestServer(TestServer.json("{}"))
        assertFailsWith<NoContentGeneratedError> { server.model().doGenerate(options()) }
    }

    // --- editing --------------------------------------------------------------------------------

    private val png = byteArrayOf(137.toByte(), 80, 78, 71)

    @Test
    fun `an edit source becomes a data URI, because a bare base64 string reads as a prompt`() =
        runTest {
            val server = server()
            server.model().doGenerate(
                options(
                    prompt = "Turn the cat into a dog",
                    files = listOf(ImageFile.Data(BinaryData.Bytes(png), "image/png")),
                ),
            )

            server.request().assertBodyEquals(
                """
                {
                  "image_url": "data:image/png;base64,iVBORw==",
                  "num_images": 1,
                  "prompt": "Turn the cat into a dog"
                }
                """,
            )
        }

    @Test
    fun `a mask goes under mask_url beside the source it applies to`() = runTest {
        val server = server()
        server.model().doGenerate(
            options(
                prompt = "Add a flamingo to the pool",
                files = listOf(ImageFile.Data(BinaryData.Bytes(png), "image/png")),
                mask = ImageFile.Data(
                    BinaryData.Bytes(byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 0)),
                    "image/png",
                ),
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "image_url": "data:image/png;base64,iVBORw==",
              "mask_url": "data:image/png;base64,////AA==",
              "num_images": 1,
              "prompt": "Add a flamingo to the pool"
            }
            """,
        )
    }

    @Test
    fun `a URL source is passed through untouched`() = runTest {
        val server = server()
        server.model().doGenerate(
            options(
                prompt = "Edit this image",
                files = listOf(ImageFile.Url("https://example.com/input.png")),
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "image_url": "https://example.com/input.png",
              "num_images": 1,
              "prompt": "Edit this image"
            }
            """,
        )
    }

    @Test
    fun `base64 that is already encoded is wrapped, not re-encoded`() = runTest {
        val server = server()
        server.model().doGenerate(
            options(
                prompt = "Edit this image",
                files = listOf(
                    ImageFile.Data(
                        BinaryData.Base64("iVBORw0KGgoAAAANSUhEUgAAAAE="),
                        "image/png",
                    ),
                ),
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "image_url": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAE=",
              "num_images": 1,
              "prompt": "Edit this image"
            }
            """,
        )
    }

    @Test
    fun `a second source with no multi-image flag is dropped, and the caller is told`() = runTest {
        val server = server()
        val result = server.model().doGenerate(
            options(
                prompt = "Edit images",
                files = List(2) { ImageFile.Data(BinaryData.Bytes(png), "image/png") },
            ),
        )

        assertEquals(1, result.warnings.size)
        val warning = assertIs<Warning.Other>(result.warnings.single())
        assertTrue("useMultipleImages is not enabled" in warning.message)
    }

    @Test
    fun `useMultipleImages sends an image_urls array and no image_url at all`() = runTest {
        val server = server()
        val result = server.model().doGenerate(
            options(
                prompt = "Edit these images",
                files = List(2) { ImageFile.Data(BinaryData.Bytes(png), "image/png") },
                fal = buildJsonObject { put("useMultipleImages", true) },
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "image_urls": [
                "data:image/png;base64,iVBORw==",
                "data:image/png;base64,iVBORw=="
              ],
              "num_images": 1,
              "prompt": "Edit these images"
            }
            """,
        )
        server.request().assertBodyMissing("image_url")
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `a single source under useMultipleImages is still an array of one`() = runTest {
        val server = server()
        server.model().doGenerate(
            options(
                prompt = "Edit this image",
                files = listOf(ImageFile.Data(BinaryData.Bytes(png), "image/png")),
                fal = buildJsonObject { put("useMultipleImages", true) },
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "image_urls": ["data:image/png;base64,iVBORw=="],
              "num_images": 1,
              "prompt": "Edit this image"
            }
            """,
        )
    }

    /** The routing flag decides a field name; sending it as one is a parameter fal has never heard of. */
    @Test
    fun `useMultipleImages is never itself sent`() = runTest {
        val server = server()
        server.model().doGenerate(
            options(fal = buildJsonObject { put("useMultipleImages", true) }),
        )
        server.request().assertBodyMissing("useMultipleImages")
    }

    @Test
    fun `an edit can be addressed entirely through provider options`() = runTest {
        val server = server()
        server.model().doGenerate(
            options(
                prompt = "Inpaint this",
                fal = buildJsonObject {
                    put("imageUrl", "https://example.com/image.png")
                    put("maskUrl", "https://example.com/mask.png")
                },
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "image_url": "https://example.com/image.png",
              "mask_url": "https://example.com/mask.png",
              "num_images": 1,
              "prompt": "Inpaint this"
            }
            """,
        )
    }

    @Test
    fun `fal serves one image per call`() = runTest {
        assertEquals(1, server().model().maxImagesPerCall())
    }
}
