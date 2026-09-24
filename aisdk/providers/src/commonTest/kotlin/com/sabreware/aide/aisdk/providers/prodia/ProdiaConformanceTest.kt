package com.sabreware.aide.aisdk.providers.prodia

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Prodia's v2 job API, against the reference's own `prodia-image-model.test.ts`.
 *
 * The interesting half is the RESPONSE: one multipart body carrying the job JSON and the image bytes,
 * which is the shape whose parser this provider waited on. The request half pins the `{type, config}`
 * envelope and the precedence rule between `size` and the vendor's own width/height.
 */
class ProdiaConformanceTest {

    private val prompt = "A cute baby sea otter"
    private val modelId = "inference.flux-fast.schnell.txt2img.v2"
    private val imageBytes = "png-bytes-here".encodeToByteArray()

    private fun provider(server: TestServer) = ProdiaProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-key",
    )

    private fun multipartResponse(
        job: String = """{"id":"job-1","state":{"current":"succeeded"},"config":{"seed":7},""" +
            """"metrics":{"elapsed":1.25,"ips":8.5},"price":{"product":"flux","dollars":0.002},""" +
            """"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:05Z"}""",
    ): TestServer = TestServer(
        TestServer.bytes(
            buildString {
                append("--bnd\r\n")
                append("Content-Disposition: form-data; name=\"job\"\r\n")
                append("Content-Type: application/json\r\n\r\n")
                append(job)
                append("\r\n--bnd\r\n")
                append("Content-Disposition: form-data; name=\"output\"\r\n")
                append("Content-Type: image/png\r\n\r\n")
            }.encodeToByteArray() + imageBytes + "\r\n--bnd--\r\n".encodeToByteArray(),
            "multipart/form-data; boundary=bnd",
        ),
    )

    @Test
    fun `the job envelope is type plus config, posted with the multipart Accept`() = runTest {
        val server = multipartResponse()

        provider(server).imageModel(modelId).doGenerate(
            ImageCallOptions(
                prompt = prompt,
                n = 1,
                seed = 12345,
                providerOptions = mapOf(
                    PRODIA_PROVIDER_ID to buildJsonObject { put("steps", 4) },
                ),
            ),
        )

        val call = server.request()
        assertEquals("https://inference.prodia.com/v2/job?price=true", call.url.substringBefore('#'))
        assertEquals("Bearer test-key", call.header("Authorization"))
        assertEquals("multipart/form-data; image/png", call.header("Accept"))
        // The reference's own whole-body pin.
        call.assertBodyEquals(
            """{"type":"$modelId","config":{"prompt":"$prompt","seed":12345,"steps":4}}""",
        )
    }

    @Test
    fun `size splits into numeric width and height inside config`() = runTest {
        val server = multipartResponse()

        provider(server).imageModel(modelId).doGenerate(
            ImageCallOptions(prompt = prompt, n = 1, size = "1024x768"),
        )

        server.request().assertBodyEquals(
            """{"type":"$modelId","config":{"prompt":"$prompt","width":1024,"height":768}}""",
        )
    }

    @Test
    fun `vendor width and height beat the pair derived from size`() = runTest {
        val server = multipartResponse()

        provider(server).imageModel(modelId).doGenerate(
            ImageCallOptions(
                prompt = prompt,
                n = 1,
                size = "1024x768",
                providerOptions = mapOf(
                    PRODIA_PROVIDER_ID to buildJsonObject {
                        put("width", 512)
                        put("height", 512)
                    },
                ),
            ),
        )

        server.request().assertBodyEquals(
            """{"type":"$modelId","config":{"prompt":"$prompt","width":512,"height":512}}""",
        )
    }

    @Test
    fun `the multipart answer splits into image bytes and job metadata`() = runTest {
        val server = multipartResponse()

        val result = provider(server).imageModel(modelId)
            .doGenerate(ImageCallOptions(prompt = prompt, n = 1))

        assertContentEquals(imageBytes, (result.images.single() as BinaryData.Bytes).value)
        val meta = result.providerMetadata?.get(PRODIA_PROVIDER_ID)
            ?.jsonObject?.get("images")?.jsonArray?.single()?.jsonObject
        assertEquals("job-1", meta?.get("jobId")?.jsonPrimitive?.content)
        assertEquals(7, meta?.get("seed")?.jsonPrimitive?.content?.toInt())
        assertEquals(1.25, meta?.get("elapsed")?.jsonPrimitive?.content?.toDouble())
        assertEquals(8.5, meta?.get("iterationsPerSecond")?.jsonPrimitive?.content?.toDouble())
        assertEquals(0.002, meta?.get("dollars")?.jsonPrimitive?.content?.toDouble())
        assertEquals("job-1", result.response?.id)
    }

    @Test
    fun `a response without a job part is invalid data, not an index crash`() = runTest {
        val body = buildString {
            append("--bnd\r\n")
            append("Content-Disposition: form-data; name=\"output\"\r\n")
            append("Content-Type: image/png\r\n\r\nimg\r\n--bnd--\r\n")
        }.encodeToByteArray()
        val server = TestServer(TestServer.bytes(body, "multipart/form-data; boundary=bnd"))

        assertFailsWith<InvalidResponseDataError> {
            provider(server).imageModel(modelId).doGenerate(ImageCallOptions(prompt = prompt, n = 1))
        }
    }

    @Test
    fun `a non-multipart content type is named in the failure`() = runTest {
        val server = TestServer(TestServer.bytes("{}".encodeToByteArray(), "application/json"))

        val error = assertFailsWith<InvalidResponseDataError> {
            provider(server).imageModel(modelId).doGenerate(ImageCallOptions(prompt = prompt, n = 1))
        }
        assertTrue(error.message.orEmpty().contains("application/json"))
    }

    @Test
    fun `an unparseable size warns and sends no dimensions`() = runTest {
        val server = multipartResponse()

        val result = provider(server).imageModel(modelId).doGenerate(
            ImageCallOptions(prompt = prompt, n = 1, size = "huge"),
        )

        result.warnings.assertUnsupported("size")
        assertTrue("width" !in server.request().bodyJson()["config"]!!.jsonObject.keys)
    }

    @Test
    fun `the string-detail error shape is read`() = runTest {
        val server = TestServer(TestServer.error(422, """{"detail":"invalid job type"}"""))

        val error = assertFailsWith<APICallError> {
            provider(server).imageModel(modelId).doGenerate(ImageCallOptions(prompt = prompt, n = 1))
        }
        assertTrue(error.message.orEmpty().contains("invalid job type"))
    }
}
