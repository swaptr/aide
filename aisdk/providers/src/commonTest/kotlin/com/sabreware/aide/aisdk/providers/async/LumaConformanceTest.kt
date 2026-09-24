package com.sabreware.aide.aisdk.providers.async

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.providers.luma.LumaProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.util.JobFailedError
import com.sabreware.aide.aisdk.util.PollPolicy
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Luma, against the reference's own `luma-image-model.test.ts`.
 *
 * Luma is the vendor that made [ImageFile] a union: it accepts a reference image only as a publicly
 * reachable URL and refuses inline data outright. It is also the vendor with FOUR different keys an
 * input image can arrive under, and sending one under the wrong key produces a plausible picture that
 * ignores the input entirely — the failure that is hardest to notice, because nothing errors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LumaConformanceTest {

    private val prompt = "A cute baby sea otter"
    private val modelId = "test-model"
    private val baseUrl = "https://api.example.com/dream-machine/v1"
    private val imageBytes = "test-binary-content".encodeToByteArray()

    private fun TestScope.provider(server: TestServer) = LumaProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-key",
        baseUrl = baseUrl,
        pollPolicy = PollPolicy.Fast,
        elapsedMillis = { testScheduler.currentTime },
    )

    private fun server(vararg generations: String): TestServer = TestServer(
        TestServer.json(LumaFixtures.QUEUED),
        *(generations.takeIf { it.isNotEmpty() } ?: arrayOf(LumaFixtures.COMPLETED))
            .map { TestServer.json(it) }.toTypedArray(),
        TestServer.bytes(imageBytes, "image/png"),
    )

    private fun options(
        prompt: String? = this.prompt,
        size: String? = null,
        aspectRatio: String? = null,
        seed: Int? = null,
        files: List<ImageFile>? = null,
        mask: ImageFile? = null,
        luma: JsonObject? = null,
        headers: Map<String, String>? = null,
    ) = ImageCallOptions(
        prompt = prompt,
        n = 1,
        size = size,
        aspectRatio = aspectRatio,
        seed = seed,
        files = files,
        mask = mask,
        providerOptions = luma?.let { mapOf("luma" to it) },
        headers = headers,
    )

    // --- the request ------------------------------------------------------------------------------

    @Test
    fun `the model rides in the body, beside the ratio and anything else the caller passed`() =
        runTest {
            val server = server()
            provider(server).imageModel(modelId).doGenerate(
                options(
                    aspectRatio = "16:9",
                    luma = buildJsonObject { put("additional_param", "value") },
                ),
            )

            server.request().assertBodyEquals(
                """
                {
                  "prompt": "$prompt",
                  "aspect_ratio": "16:9",
                  "model": "$modelId",
                  "additional_param": "value"
                }
                """,
            )
        }

    /** These steer our poll loop; sent to Luma they are parameters its generation schema rejects. */
    @Test
    fun `the poll knobs never reach the generation request`() = runTest {
        val server = server()
        provider(server).imageModel(modelId).doGenerate(
            options(
                aspectRatio = "16:9",
                luma = buildJsonObject {
                    put("pollIntervalMillis", 1000)
                    put("maxPollAttempts", 5)
                    put("additional_param", "value")
                },
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "prompt": "$prompt",
              "aspect_ratio": "16:9",
              "model": "$modelId",
              "additional_param": "value"
            }
            """,
        )
    }

    @Test
    fun `the three calls go out in order`() = runTest {
        val server = server()
        provider(server).imageModel(modelId).doGenerate(options(aspectRatio = "16:9"))

        assertEquals("POST", server.request(0).method)
        assertEquals("$baseUrl/generations/image", server.request(0).url)
        assertEquals("GET", server.request(1).method)
        assertEquals("$baseUrl/generations/test-generation-id", server.request(1).url)
        assertEquals("GET", server.request(2).method)
        assertEquals("https://api.example.com/image.png", server.request(2).url)
    }

    @Test
    fun `the call's own headers ride alongside the bearer token`() = runTest {
        val server = server()
        provider(server).imageModel(modelId).doGenerate(
            options(headers = mapOf("Custom-Request-Header" to "request-header-value")),
        )

        server.request().assertHeader("Authorization", "Bearer test-key")
        server.request().assertHeader("Custom-Request-Header", "request-header-value")
    }

    @Test
    fun `seed and size are things Luma does not have, and both say so`() = runTest {
        val server = server()
        val result = provider(server).imageModel(modelId)
            .doGenerate(options(size = "1024x1024", seed = 123))

        result.warnings.assertUnsupported("seed", "This model does not support the `seed` option.")
        result.warnings.assertUnsupported(
            "size",
            "This model does not support the `size` option. Use `aspectRatio` instead.",
        )
        assertEquals(2, result.warnings.size)
    }

    @Test
    fun `nothing warns when nothing was refused`() = runTest {
        val server = server()
        provider(server).imageModel(modelId).doGenerate(options(aspectRatio = "16:9"))
            .warnings.assertNoWarnings()
    }

    // --- reference types --------------------------------------------------------------------------

    private val inputUrl = "https://example.com/input.jpg"

    @Test
    fun `a source with no reference type is a composition guide, weighted at Luma's default`() =
        runTest {
            val server = server()
            provider(server).imageModel(modelId).doGenerate(
                options(prompt = "A warrior with sunglasses", files = listOf(ImageFile.Url(inputUrl))),
            )

            server.request().assertBodyEquals(
                """
                {
                  "prompt": "A warrior with sunglasses",
                  "model": "$modelId",
                  "image": [{ "url": "$inputUrl", "weight": 0.85 }]
                }
                """,
            )
        }

    /** Each reference type has its OWN default weight; they are not one number reused. */
    @Test
    fun `style and modify_image carry their own default weights, not the composition one`() =
        runTest {
            val style = server()
            provider(style).imageModel(modelId).doGenerate(
                options(
                    prompt = "A dog in this style",
                    files = listOf(ImageFile.Url("https://example.com/style.jpg")),
                    luma = buildJsonObject { put("referenceType", "style") },
                ),
            )
            style.request().assertBodyEquals(
                """
                {
                  "prompt": "A dog in this style",
                  "model": "$modelId",
                  "style": [{ "url": "https://example.com/style.jpg", "weight": 0.8 }]
                }
                """,
            )

            val modify = server()
            provider(modify).imageModel(modelId).doGenerate(
                options(
                    prompt = "Transform flowers to sunflowers",
                    files = listOf(ImageFile.Url(inputUrl)),
                    luma = buildJsonObject { put("referenceType", "modify_image") },
                ),
            )
            // `modify_image` is a single object, not a list: it transforms one source rather than
            // blending several.
            modify.request().assertBodyEquals(
                """
                {
                  "prompt": "Transform flowers to sunflowers",
                  "model": "$modelId",
                  "modify_image": { "url": "$inputUrl", "weight": 1.0 }
                }
                """,
            )
        }

    /**
     * `character` groups shots by identity rather than listing them: several photographs of ONE person
     * belong under one identity, and two people are two identities. A flat list means Luma treats every
     * photograph as a different subject.
     */
    @Test
    fun `character groups every unnamed source under one identity`() = runTest {
        val server = server()
        provider(server).imageModel(modelId).doGenerate(
            options(
                prompt = "A warrior",
                files = listOf(
                    ImageFile.Url("https://example.com/person1.jpg"),
                    ImageFile.Url("https://example.com/person2.jpg"),
                ),
                luma = buildJsonObject { put("referenceType", "character") },
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "prompt": "A warrior",
              "model": "$modelId",
              "character": {
                "identity0": {
                  "images": [
                    "https://example.com/person1.jpg",
                    "https://example.com/person2.jpg"
                  ]
                }
              }
            }
            """,
        )
    }

    @Test
    fun `named identities split the same sources into separate subjects`() = runTest {
        val server = server()
        provider(server).imageModel(modelId).doGenerate(
            options(
                prompt = "Two people talking",
                files = listOf(
                    ImageFile.Url("https://example.com/person1.jpg"),
                    ImageFile.Url("https://example.com/person2.jpg"),
                ),
                luma = buildJsonObject {
                    put("referenceType", "character")
                    put(
                        "images",
                        buildJsonArray {
                            add(buildJsonObject { put("id", "identity0") })
                            add(buildJsonObject { put("id", "identity1") })
                        },
                    )
                },
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "prompt": "Two people talking",
              "model": "$modelId",
              "character": {
                "identity0": { "images": ["https://example.com/person1.jpg"] },
                "identity1": { "images": ["https://example.com/person2.jpg"] }
              }
            }
            """,
        )
    }

    @Test
    fun `a per-image weight overrides the default for the image it sits against`() = runTest {
        val server = server()
        provider(server).imageModel(modelId).doGenerate(
            options(
                prompt = "Styled image",
                files = listOf(ImageFile.Url(inputUrl)),
                luma = buildJsonObject {
                    put("images", buildJsonArray { add(buildJsonObject { put("weight", 0.5) }) })
                },
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "prompt": "Styled image",
              "model": "$modelId",
              "image": [{ "url": "$inputUrl", "weight": 0.5 }]
            }
            """,
        )
    }

    @Test
    fun `several composition sources each get the default weight`() = runTest {
        val server = server()
        provider(server).imageModel(modelId).doGenerate(
            options(
                prompt = "Combine these concepts",
                files = listOf(
                    ImageFile.Url("https://example.com/input1.jpg"),
                    ImageFile.Url("https://example.com/input2.jpg"),
                ),
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "prompt": "Combine these concepts",
              "model": "$modelId",
              "image": [
                { "url": "https://example.com/input1.jpg", "weight": 0.85 },
                { "url": "https://example.com/input2.jpg", "weight": 0.85 }
              ]
            }
            """,
        )
    }

    // --- refusals ---------------------------------------------------------------------------------

    /**
     * Every refusal here throws rather than warns. A caller who asked to edit *this* image and silently
     * got a fresh generation of the prompt has been handed the wrong picture, and a warning on a result
     * nobody reads is not a correction.
     */
    @Test
    fun `Luma has no mask editing, so a mask is refused rather than dropped`() = runTest {
        val server = server()
        val error = assertFailsWith<UnsupportedFunctionalityError> {
            provider(server).imageModel(modelId).doGenerate(
                options(
                    prompt = "Replace sky with sunset",
                    files = listOf(ImageFile.Url(inputUrl)),
                    mask = ImageFile.Url("https://example.com/mask.png"),
                ),
            )
        }
        assertTrue("does not support mask-based image editing" in error.message!!)
        assertEquals(0, server.callCount)
    }

    @Test
    fun `inline bytes are refused rather than generating the wrong picture`() = runTest {
        val server = server()
        val error = assertFailsWith<UnsupportedFunctionalityError> {
            provider(server).imageModel(modelId).doGenerate(
                options(
                    prompt = "Edit this image",
                    files = listOf(
                        ImageFile.Data(
                            BinaryData.Bytes(byteArrayOf(137.toByte(), 80, 78, 71)),
                            "image/png",
                        ),
                    ),
                ),
            )
        }
        assertTrue("only supports URL-based images" in error.message!!)
    }

    @Test
    fun `a fifth composition source is over Luma's limit and is refused`() = runTest {
        val server = server()
        val error = assertFailsWith<IllegalArgumentException> {
            provider(server).imageModel(modelId).doGenerate(
                options(
                    prompt = "Too many images",
                    files = List(5) { ImageFile.Url("https://example.com/${it + 1}.jpg") },
                ),
            )
        }
        assertTrue("supports up to 4 reference images" in error.message!!)
    }

    @Test
    fun `modify_image transforms one source, so a second is refused`() = runTest {
        val server = server()
        val error = assertFailsWith<IllegalArgumentException> {
            provider(server).imageModel(modelId).doGenerate(
                options(
                    prompt = "Edit multiple images",
                    files = listOf(
                        ImageFile.Url("https://example.com/input1.jpg"),
                        ImageFile.Url("https://example.com/input2.jpg"),
                    ),
                    luma = buildJsonObject { put("referenceType", "modify_image") },
                ),
            )
        }
        assertTrue("only supports a single input image" in error.message!!)
    }

    // --- polling and errors -------------------------------------------------------------------------

    /**
     * `dreaming` is Luma's word for "processing". A client matching only a standard vocabulary treats it
     * as terminal and reports a failure for a job that is running perfectly well.
     */
    @Test
    fun `queued and dreaming are both still-working`() = runTest {
        val server = server(
            LumaFixtures.QUEUED,
            LumaFixtures.QUEUED.replace("\"queued\"", "\"dreaming\""),
            LumaFixtures.COMPLETED,
        )
        val result = provider(server).imageModel(modelId).doGenerate(options())

        assertEquals(1, result.images.size)
        assertEquals(5, server.callCount)
    }

    /**
     * Luma echoes the whole request back on a completed generation, references and all. A parser that
     * insists on a shape it recognises rejects the answer it was waiting for; nothing in this response
     * except `assets.image` is ours to understand.
     */
    @Test
    fun `a completed generation echoing every reference kind still yields its image`() = runTest {
        val server = server(LumaFixtures.COMPLETED_ECHOING_EVERY_REFERENCE)
        val result = provider(server).imageModel(modelId).doGenerate(options())

        assertEquals(1, result.images.size)
        assertTrue(imageBytes contentEquals (result.images.single() as BinaryData.Bytes).value)
    }

    @Test
    fun `a failed generation carries Luma's own reason`() = runTest {
        val server = server(LumaFixtures.FAILED)
        val error = assertFailsWith<JobFailedError> {
            provider(server).imageModel(modelId).doGenerate(options())
        }
        assertEquals("Generation failed", error.message)
    }

    @Test
    fun `a rejected submit is thrown with the status and URL that produced it`() = runTest {
        val server = TestServer(TestServer.json("\"Bad Request\"", HttpStatusCode.BadRequest))
        val error = assertFailsWith<APICallError> {
            provider(server).imageModel(modelId).doGenerate(options())
        }

        assertEquals(400, error.statusCode)
        assertEquals("$baseUrl/generations/image", error.url)
        // The body we sent is carried too, which is what makes a vendor support ticket answerable.
        assertTrue(error.requestBodyValues!!.contains(prompt))
    }

    @Test
    fun `the response names the model, and Luma serves one image per call`() = runTest {
        val server = server()
        val result = provider(server).imageModel(modelId).doGenerate(options())

        assertEquals(modelId, result.response.modelId)
        assertEquals(1, provider(server).imageModel(modelId).maxImagesPerCall())
    }
}
