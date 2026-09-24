package com.sabreware.aide.aisdk.providers.async

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.providers.blackforestlabs.BlackForestLabsProvider
import com.sabreware.aide.aisdk.providers.fal.FalProvider
import com.sabreware.aide.aisdk.providers.luma.LumaProvider
import com.sabreware.aide.aisdk.providers.replicate.ReplicateProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.JobFailedError
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Image EDITING, which every one of these vendors serves and none of them used to reach.
 *
 * `ImageCallOptions.files` and `.mask` were declared and read by nobody, so an edit ran as a fresh
 * text-to-image generation of the prompt: no warning, no error, the wrong picture. That failure is
 * invisible to any assertion that only checks a call succeeded, so each test here reads the field the
 * input image landed in — which is also the thing that differs on every vendor.
 */
class ImageEditingTest {

    private fun client(server: TestServer): HttpClient = HttpClient(server.engine())

    private val png = ImageFile.Data(BinaryData.Base64("QUJD"), mediaType = "image/png")

    @Test
    fun `fal inlines an edit source as a data URI`() = runTest {
        val server = TestServer(
            TestServer.json("""{"images":[{"url":"https://cdn/i.png"}]}"""),
            TestServer.bytes("X".encodeToByteArray(), "image/png"),
        )
        val provider = FalProvider(client = client(server), apiKey = "k")

        provider.imageModel("fal-ai/flux/dev/image-to-image").doGenerate(
            ImageCallOptions(prompt = "make it blue", files = listOf(png), mask = png),
        )

        // A bare base64 string is what a caller reaches for, and fal accepts it as a PROMPT — so the
        // edit silently becomes a generation of a base64 blob.
        server.request(0).assertBodyJson {
            assertEquals("data:image/png;base64,QUJD", it["image_url"].string())
            assertEquals("data:image/png;base64,QUJD", it["mask_url"].string())
        }
        server.request(0).assertBodyKeys("prompt", "num_images", "image_url", "mask_url")
    }

    @Test
    fun `fal only sends an image list when the model is told it takes one`() = runTest {
        val server = TestServer(
            TestServer.json("""{"images":[{"url":"https://cdn/i.png"}]}"""),
            TestServer.bytes("X".encodeToByteArray(), "image/png"),
        )
        val provider = FalProvider(client = client(server), apiKey = "k")

        val result = provider.imageModel("fal-ai/flux-2/edit").doGenerate(
            ImageCallOptions(
                prompt = "combine",
                files = listOf(png, ImageFile.Url("https://cdn/two.png")),
                providerOptions = mapOf(
                    "fal" to buildJsonObject { put("useMultipleImages", "true") },
                ),
            ),
        )

        // `image_urls` is not a superset of `image_url`: a model that takes the singular rejects the
        // plural outright, so which key is used cannot be inferred from the number of images.
        server.request(0).assertBodyJson {
            assertEquals(
                listOf("data:image/png;base64,QUJD", "https://cdn/two.png"),
                it["image_urls"]?.let { urls -> (urls as kotlinx.serialization.json.JsonArray) }
                    ?.map { url -> url.jsonPrimitive.content },
            )
        }
        server.request(0).assertBodyMissing("image_url")
        // The routing flag steers the request; sending it on is an unexpected-parameter rejection.
        server.request(0).assertBodyMissing("useMultipleImages")
        assertTrue(result.warnings.isEmpty(), "unexpected warnings: ${result.warnings}")
    }

    @Test
    fun `replicate numbers flux-2 inputs and keeps everything else on a single key`() = runTest {
        val server = TestServer(
            TestServer.json("""{"id":"p1","status":"succeeded","output":"https://cdn/a.png"}"""),
            TestServer.bytes("A".encodeToByteArray(), "image/png"),
        )
        val provider = ReplicateProvider(client = client(server), apiToken = "t")

        provider.imageModel("black-forest-labs/flux-2-dev").doGenerate(
            ImageCallOptions(prompt = "x", files = listOf(png, ImageFile.Url("https://cdn/b.png"))),
        )

        // Flux-2 spells the second input `input_image_2`, not a second element of an array. The first is
        // `input_image` with no number, and getting that wrong drops the image the edit was about.
        server.request(0).assertBodyJson { body ->
            val input = body["input"]!!.let { it as kotlinx.serialization.json.JsonObject }
            assertEquals("data:image/png;base64,QUJD", input["input_image"].string())
            assertEquals("https://cdn/b.png", input["input_image_2"].string())
        }
    }

    @Test
    fun `bfl sends bare base64 under numbered input_image keys`() = runTest {
        val server = TestServer(
            TestServer.json("""{"id":"req1","polling_url":"https://api.us1.bfl.ai/v1/get_result"}"""),
            TestServer.json("""{"status":"Ready","result":{"sample":"https://cdn/out.png","seed":7}}"""),
            TestServer.bytes("IMG".encodeToByteArray(), "image/png"),
        )
        val provider = BlackForestLabsProvider(
            client = client(server),
            apiKey = "k",
            elapsedMillis = { 0L },
        )

        val result = provider.imageModel("flux-pro-1.1").doGenerate(
            ImageCallOptions(prompt = "edit it", files = listOf(png, png)),
        )

        server.request(0).assertHeader("x-key", "k")
        server.request(0).assertBodyJson {
            // BARE base64: the `data:` prefix is a 422 that names the image field, so it reads as "this
            // picture is unusable" rather than "this envelope is".
            assertEquals("QUJD", it["input_image"].string())
            assertEquals("QUJD", it["input_image_2"].string())
        }
        // BFL routes a poll by query parameter; without `?id=` the request answers about no job at all.
        assertEquals("req1", server.request(1).query["id"])
        // The poll URL is on a different cluster host than the submit, and the key must still ride along
        // or every job 401s.
        assertEquals("k", server.request(1).header("x-key"))
        assertEquals(
            "7",
            result.providerMetadata?.get("blackForestLabs")
                ?.get("images")?.let { it as kotlinx.serialization.json.JsonArray }
                ?.first()?.let { it as kotlinx.serialization.json.JsonObject }
                ?.get("seed")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `bfl keeps a refused prompt distinct from a refused result`() = runTest {
        val moderated = TestServer(
            TestServer.json("""{"id":"req1","polling_url":"https://api.bfl.ai/v1/get_result"}"""),
            TestServer.json("""{"status":"Request Moderated"}"""),
        )
        val provider = BlackForestLabsProvider(
            client = client(moderated),
            apiKey = "k",
            elapsedMillis = { 0L },
        )

        val error = assertFailsWith<JobFailedError> {
            provider.imageModel("flux-pro-1.1").doGenerate(ImageCallOptions(prompt = "x"))
        }
        // A rejected PROMPT will be rejected on every retry; a rejected RESULT may well pass a re-roll.
        // Collapsing the two is what makes a retry policy unwritable.
        assertEquals("The prompt was rejected by moderation", error.message)
    }

    @Test
    fun `bfl puts the vendor's own failure detail into the message`() = runTest {
        val failing = TestServer(
            TestServer.json("""{"id":"req1","polling_url":"https://api.bfl.ai/v1/get_result"}"""),
            TestServer.json("""{"status":"Error","details":"prompt exceeds the token limit"}"""),
        )
        val provider = BlackForestLabsProvider(
            client = client(failing),
            apiKey = "k",
            elapsedMillis = { 0L },
        )

        val error = assertFailsWith<JobFailedError> {
            provider.imageModel("flux-pro-1.1").doGenerate(ImageCallOptions(prompt = "x"))
        }
        assertEquals(
            "Black Forest Labs generation failed. prompt exceeds the token limit",
            error.message,
        )
    }

    @Test
    fun `luma refuses inline bytes rather than generating the wrong picture`() = runTest {
        val server = TestServer(TestServer.json("""{"id":"g1"}"""))
        val provider = LumaProvider(client = client(server), apiKey = "k", elapsedMillis = { 0L })

        // Luma takes a reference image only as a publicly reachable URL. Warning and generating anyway
        // hands back a picture that ignores the input entirely — the failure hardest to notice.
        assertFailsWith<UnsupportedFunctionalityError> {
            provider.imageModel("photon-1").doGenerate(
                ImageCallOptions(prompt = "x", files = listOf(png)),
            )
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `luma weights each reference URL under the key its reference type names`() = runTest {
        val server = TestServer(
            TestServer.json("""{"id":"g1"}"""),
            TestServer.json("""{"state":"completed","assets":{"image":"https://cdn/out.png"}}"""),
            TestServer.bytes("IMG".encodeToByteArray(), "image/png"),
        )
        val provider = LumaProvider(client = client(server), apiKey = "k", elapsedMillis = { 0L })

        provider.imageModel("photon-1").doGenerate(
            ImageCallOptions(
                prompt = "in this style",
                files = listOf(ImageFile.Url("https://cdn/ref.png")),
                providerOptions = mapOf(
                    "luma" to buildJsonObject { put("referenceType", "style") },
                ),
            ),
        )

        // There is no one "input image" key: `image` guides composition, `style` transfers a look. Under
        // the wrong key the reference is accepted and ignored.
        server.request(0).assertBodyJson {
            assertEquals(
                """[{"url":"https://cdn/ref.png","weight":0.8}]""",
                it["style"].toString(),
            )
        }
        server.request(0).assertBodyKeys("prompt", "model", "style")
    }
}
