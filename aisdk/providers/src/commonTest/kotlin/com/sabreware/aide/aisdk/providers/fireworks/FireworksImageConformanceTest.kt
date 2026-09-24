package com.sabreware.aide.aisdk.providers.fireworks

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.util.PollPolicy
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Fireworks images, against the reference's own `fireworks-image-model.test.ts`.
 *
 * The vendor fact under test is the three-URL split: the same options go to a different endpoint —
 * and come back in a different shape — depending only on the model id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FireworksImageConformanceTest {

    private val prompt = "A cute baby sea otter"
    private val imageBytes = "binary-image-data".encodeToByteArray()

    private fun TestScope.provider(server: TestServer) = FireworksProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-key",
        baseUrl = "https://api.example.com",
        pollPolicy = PollPolicy.Fast,
        elapsedMillis = { testScheduler.currentTime },
    )

    @Test
    fun `a workflows model posts to text_to_image and passes options through loose`() = runTest {
        val server = TestServer(TestServer.bytes(imageBytes, "image/png"))

        val result = provider(server)
            .imageModel("accounts/fireworks/models/flux-1-dev-fp8")
            .doGenerate(
                ImageCallOptions(
                    prompt = prompt,
                    n = 1,
                    aspectRatio = "16:9",
                    seed = 42,
                    providerOptions = mapOf(
                        FIREWORKS_PROVIDER_ID to buildJsonObject { put("additional_param", "value") },
                    ),
                ),
            )

        val call = server.request()
        assertEquals(
            "https://api.example.com/workflows/accounts/fireworks/models/flux-1-dev-fp8/text_to_image",
            call.url,
        )
        // The reference's own whole-body pin: an open schema, so the unknown option RIDES.
        call.assertBodyEquals(
            """{"prompt":"$prompt","aspect_ratio":"16:9","seed":42,"samples":1,"additional_param":"value"}""",
        )
        assertEquals(listOf(BinaryData.Bytes(imageBytes)), result.images)
    }

    @Test
    fun `a legacy model posts to image_generation and takes a size`() = runTest {
        val server = TestServer(TestServer.bytes(imageBytes, "image/png"))

        val result = provider(server)
            .imageModel("accounts/fireworks/models/playground-v2-5-1024px-aesthetic")
            .doGenerate(ImageCallOptions(prompt = prompt, n = 1, size = "1024x768"))

        val call = server.request()
        assertEquals(
            "https://api.example.com/image_generation/accounts/fireworks/models/playground-v2-5-1024px-aesthetic",
            call.url,
        )
        // The split halves go as STRINGS — the reference sends them un-parsed and the endpoint takes
        // them; sending numbers is a wire change with no recording behind it.
        call.assertBodyEquals("""{"prompt":"$prompt","samples":1,"width":"1024","height":"768"}""")
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `size warns on a model without it, aspectRatio warns on a model with it`() = runTest {
        val server = TestServer(TestServer.bytes(imageBytes, "image/png"))

        val fluxResult = provider(server)
            .imageModel("accounts/fireworks/models/flux-1-dev-fp8")
            .doGenerate(ImageCallOptions(prompt = prompt, n = 1, size = "1024x768"))
        fluxResult.warnings.assertUnsupported("size")

        val legacyResult = provider(server)
            .imageModel("accounts/fireworks/models/SSD-1B")
            .doGenerate(ImageCallOptions(prompt = prompt, n = 1, aspectRatio = "16:9"))
        legacyResult.warnings.assertUnsupported("aspectRatio")
    }

    @Test
    fun `a kontext model submits, polls get_result, and downloads the sample`() = runTest {
        val server = TestServer(
            TestServer.json("""{"request_id":"req-1"}"""),
            TestServer.json("""{"id":"req-1","status":"Pending","result":null}"""),
            TestServer.json(
                """{"id":"req-1","status":"Ready","result":{"sample":"https://api.example.com/results/img.png"}}""",
            ),
            TestServer.bytes(imageBytes, "image/png"),
        )

        val result = provider(server)
            .imageModel("accounts/fireworks/models/flux-kontext-pro")
            .doGenerate(
                ImageCallOptions(
                    prompt = prompt,
                    n = 1,
                    files = listOf(
                        ImageFile.Data(BinaryData.Base64("aW1n"), mediaType = "image/png"),
                    ),
                ),
            )

        assertEquals("https://api.example.com/workflows/accounts/fireworks/models/flux-kontext-pro", server.request(0).url)
        // The edit input travels as a data: URI — the same FLUX family behind BFL REJECTS the prefix;
        // here it is required.
        assertEquals(
            "data:image/png;base64,aW1n",
            server.request(0).bodyJson()["input_image"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content },
        )
        assertEquals(
            "https://api.example.com/workflows/accounts/fireworks/models/flux-kontext-pro/get_result",
            server.request(1).url,
        )
        server.request(1).assertBodyEquals("""{"id":"req-1"}""")
        assertEquals(4, server.callCount)
        assertEquals(listOf(BinaryData.Bytes(imageBytes)), result.images)
        // Same origin as the base: the key travels on the download.
        assertEquals("Bearer test-key", server.request(3).header("Authorization"))
    }

    @Test
    fun `a kontext delivery URL on a foreign host is fetched without the key`() = runTest {
        val server = TestServer(
            TestServer.json("""{"request_id":"req-1"}"""),
            TestServer.json(
                """{"id":"req-1","status":"Ready","result":{"sample":"https://cdn.elsewhere.example/img.png"}}""",
            ),
            TestServer.bytes(imageBytes, "image/png"),
        )

        provider(server)
            .imageModel("accounts/fireworks/models/flux-kontext-max")
            .doGenerate(ImageCallOptions(prompt = prompt, n = 1))

        assertEquals("https://cdn.elsewhere.example/img.png", server.request(2).url)
        assertEquals(null, server.request(2).header("Authorization"))
    }

    @Test
    fun `a failed kontext job names its status instead of hanging`() = runTest {
        val server = TestServer(
            TestServer.json("""{"request_id":"req-1"}"""),
            TestServer.json("""{"id":"req-1","status":"Error","result":null}"""),
        )

        val error = kotlin.runCatching {
            provider(server)
                .imageModel("accounts/fireworks/models/flux-kontext-pro")
                .doGenerate(ImageCallOptions(prompt = prompt, n = 1))
        }.exceptionOrNull()

        assertIs<com.sabreware.aide.aisdk.util.JobFailedError>(error)
        assertTrue(error.message.orEmpty().contains("Error"))
    }

    @Test
    fun `mask and extra files warn rather than vanish`() = runTest {
        val server = TestServer(TestServer.bytes(imageBytes, "image/png"))
        val file = ImageFile.Data(BinaryData.Base64("aW1n"), mediaType = "image/png")

        val result = provider(server)
            .imageModel("accounts/fireworks/models/flux-1-dev-fp8")
            .doGenerate(ImageCallOptions(prompt = prompt, n = 1, files = listOf(file, file), mask = file))

        result.warnings.assertUnsupported("mask")
        assertTrue(result.warnings.any { it is com.sabreware.aide.aisdk.Warning.Other })
        // Only the FIRST file rides; the body must not grow an input_image_2.
        assertTrue("input_image_2" !in server.request().bodyJson().keys)
    }
}
