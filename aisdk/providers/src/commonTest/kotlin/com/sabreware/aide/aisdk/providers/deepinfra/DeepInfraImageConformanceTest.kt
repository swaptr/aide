package com.sabreware.aide.aisdk.providers.deepinfra

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.providers.testing.TestServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * DeepInfra images, against the reference's own `deepinfra-image-model.test.ts`.
 *
 * Two dialects in one model: generation posts JSON to `/inference/{model}` and answers `images[]` as
 * `data:` URIs; editing posts multipart to the OpenAI-compatible `/openai/images/edits` and answers
 * `data[].b64_json`. The compat binding this replaces pointed every call at a third endpoint that does
 * not exist.
 */
class DeepInfraImageConformanceTest {

    private val prompt = "A cute baby sea otter"

    private fun provider(server: TestServer) = DeepInfraProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-key",
    )

    private fun generationResponse() =
        TestServer.json("""{"images":["data:image/png;base64,dGVzdC1pbWFnZQ=="]}""")

    @Test
    fun `generation posts the model in the path and num_images in the body`() = runTest {
        val server = TestServer(generationResponse())

        provider(server).imageModel("stabilityai/sdxl-turbo").doGenerate(
            ImageCallOptions(
                prompt = prompt,
                n = 1,
                aspectRatio = "16:9",
                seed = 42,
                providerOptions = mapOf(
                    DEEPINFRA_PROVIDER_ID to buildJsonObject { put("additional_param", "value") },
                ),
            ),
        )

        val call = server.request()
        assertEquals("https://api.deepinfra.com/v1/inference/stabilityai/sdxl-turbo", call.url)
        assertEquals("Bearer test-key", call.header("Authorization"))
        // The reference's whole-body pin, unknown option riding through.
        call.assertBodyEquals(
            """{"prompt":"$prompt","num_images":1,"aspect_ratio":"16:9","seed":42,"additional_param":"value"}""",
        )
    }

    @Test
    fun `size splits into string width and height`() = runTest {
        val server = TestServer(generationResponse())

        provider(server).imageModel("m").doGenerate(
            ImageCallOptions(prompt = prompt, n = 1, size = "1024x768", seed = 42),
        )

        // Strings, matching the reference's recorded wire — not tidied into numbers.
        server.request().assertBodyEquals(
            """{"prompt":"$prompt","num_images":1,"width":"1024","height":"768","seed":42}""",
        )
    }

    @Test
    fun `the data-URI prefix is stripped and the payload stays base64`() = runTest {
        val server = TestServer(generationResponse())

        val result = provider(server).imageModel("m").doGenerate(ImageCallOptions(prompt = prompt, n = 1))

        assertEquals(listOf<BinaryData>(BinaryData.Base64("dGVzdC1pbWFnZQ==")), result.images)
    }

    @Test
    fun `editing goes multipart to the openai edits endpoint`() = runTest {
        val server = TestServer(TestServer.json("""{"data":[{"b64_json":"ZWRpdGVk"}]}"""))

        val result = provider(server).imageModel("m").doGenerate(
            ImageCallOptions(
                prompt = prompt,
                n = 1,
                size = "512x512",
                files = listOf(ImageFile.Data(BinaryData.Base64("aW1n"), mediaType = "image/png")),
                mask = ImageFile.Data(BinaryData.Base64("bWFzaw=="), mediaType = "image/png"),
            ),
        )

        val call = server.request()
        assertEquals("https://api.deepinfra.com/v1/openai/images/edits", call.url)
        assertEquals("m", call.multipart["model"])
        assertEquals(prompt, call.multipart["prompt"])
        assertEquals("1", call.multipart["n"])
        assertEquals("512x512", call.multipart["size"])
        assertTrue("image" in call.multipartFiles)
        assertTrue("mask" in call.multipartFiles)
        assertEquals(listOf<BinaryData>(BinaryData.Base64("ZWRpdGVk")), result.images)
    }

    @Test
    fun `the inference error envelope is read, not stringified`() = runTest {
        val server = TestServer(
            TestServer.error(400, """{"detail":{"error":"Invalid prompt"}}"""),
        )

        val error = assertFailsWith<APICallError> {
            provider(server).imageModel("m").doGenerate(ImageCallOptions(prompt = prompt, n = 1))
        }

        assertTrue(error.message.orEmpty().contains("Invalid prompt"))
    }
}
