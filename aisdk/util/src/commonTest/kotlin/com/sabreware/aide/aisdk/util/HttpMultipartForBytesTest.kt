package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.APICallError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The multipart-in / bytes-out verb, pinned at the wire.
 *
 * The request half is decoded with [MultipartResponse] — the same parser the callers of this verb use
 * on the answer — because the property under test is that a part goes out under the NAME, FILENAME and
 * CONTENT TYPE the vendor keys its decoder off. A file part named `image` where the endpoint reads
 * `input` is a 4xx at best, and at Prodia a job that ran from the prompt alone at worst.
 */
class HttpMultipartForBytesTest {

    private val url = "https://api.example.com/v2/job?price=true"

    private val jobJson = """{"type":"inference.nano-banana.img2img.v2","config":{"prompt":"hi"}}"""

    private val parts = listOf(
        MultipartPart.File("job", "job.json", jobJson.encodeToByteArray(), "application/json"),
        MultipartPart.File("input", "input.png", "PNG-BYTES".encodeToByteArray(), "image/png"),
    )

    private fun http(
        retryPolicy: RetryPolicy = RetryPolicy.None,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): ProviderHttp = ProviderHttp(
        client = HttpClient(MockEngine { request -> handler(request) }),
        retryPolicy = retryPolicy,
        now = { 1_700_000_000_000 },
    )

    /** The request body split back into parts, off the boundary the request declared. */
    private suspend fun HttpRequestData.parts(): List<MultipartResponsePart> {
        val boundary = MultipartResponse.boundaryOf(body.contentType?.toString())
        assertNotNull(boundary, "request content type should name a boundary: ${body.contentType}")
        return MultipartResponse.parse(body.toByteArray(), boundary)
    }

    @Test
    fun `every part goes out under its name, filename and content type, in order`() = runTest {
        var sent: List<MultipartResponsePart> = emptyList()
        var contentType: String? = null
        val http = http { request ->
            contentType = request.body.contentType?.toString()
            sent = request.parts()
            respond(
                content = "MULTIPART-ANSWER".encodeToByteArray(),
                headers = headersOf("Content-Type", listOf("multipart/form-data; boundary=bnd")),
            )
        }

        val result = http.postMultipartForBytes(url, parts, headers = mapOf("Authorization" to "Bearer k"))

        assertTrue(contentType.orEmpty().startsWith("multipart/form-data"), "content type: $contentType")
        assertEquals(listOf("job", "input"), sent.map { it.name })
        // The disposition carries BOTH the field name and the filename on one line — the shape a
        // vendor's decoder reads — rather than a second header the decoder never looks at.
        assertContains(sent[0].headers.getValue("content-disposition"), "filename=\"job.json\"")
        assertContains(sent[1].headers.getValue("content-disposition"), "filename=\"input.png\"")
        assertEquals("application/json", sent[0].contentType)
        assertEquals("image/png", sent[1].contentType)
        assertEquals(jobJson, sent[0].body.decodeToString())
        assertContentEquals("PNG-BYTES".encodeToByteArray(), sent[1].body)
        // The answer comes back as raw bytes with the header the multipart decoder needs.
        assertContentEquals("MULTIPART-ANSWER".encodeToByteArray(), result.value)
        assertEquals("multipart/form-data; boundary=bnd", result.headers["content-type"])
        assertEquals(HttpStatusCode.OK.value, result.statusCode)
        assertEquals(1_700_000_000_000, result.timestamp)
        // A multipart body has no JSON to quote back; the field is honestly null, as its sibling's is.
        assertNull(result.requestBody)
    }

    @Test
    fun `a part with no content type sends none rather than a guessed octet-stream`() = runTest {
        var sent: List<MultipartResponsePart> = emptyList()
        val http = http { request ->
            sent = request.parts()
            respond(content = ByteArray(0))
        }

        http.postMultipartForBytes(
            url,
            listOf(MultipartPart.File("files[]", "SKILL.md", "# skill".encodeToByteArray())),
        )

        assertEquals("files[]", sent.single().name)
        assertNull(sent.single().contentType)
    }

    @Test
    fun `a non-2xx answer is the vendor's message, not a parse failure on its bytes`() = runTest {
        val http = http {
            respondError(HttpStatusCode.BadRequest, """{"detail":"Missing input image"}""")
        }

        val error = assertFailsWith<APICallError> { http.postMultipartForBytes(url, parts) }

        assertEquals(HttpStatusCode.BadRequest.value, error.statusCode)
        assertContains(error.message.orEmpty(), "Missing input image")
        assertEquals(url, error.url)
    }

    @Test
    fun `a transport failure is retried like every other verb`() = runTest {
        var calls = 0
        val http = http(retryPolicy = RetryPolicy(maxRetries = 1)) {
            calls++
            if (calls == 1) throw IllegalStateException("connection reset")
            respond(content = "ok".encodeToByteArray())
        }

        val result = http.postMultipartForBytes(url, parts)

        assertEquals(2, calls)
        assertContentEquals("ok".encodeToByteArray(), result.value)
    }
}
