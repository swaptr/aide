package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.APICallError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [ProviderHttp.postMultipartStream], the reference's `postMultipartStreamToApi`: a file part backed by
 * a flow goes out in the caller's part order, spelled the way every other file part is, and a source
 * that fails takes the request down with it rather than leaving a writer parked.
 */
class HttpMultipartStreamTest {

    private val url = "https://api.example.com/v1/files"

    private fun http(
        retryPolicy: RetryPolicy = RetryPolicy.None,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): ProviderHttp = ProviderHttp(
        client = HttpClient(MockEngine { request -> handler(request) }),
        retryPolicy = retryPolicy,
    )

    /** The request body split back into parts, off the boundary the request declared. */
    private suspend fun HttpRequestData.parts(): List<MultipartResponsePart> {
        val boundary = MultipartResponse.boundaryOf(body.contentType?.toString())
        assertNotNull(boundary, "request content type should name a boundary: ${body.contentType}")
        return MultipartResponse.parse(body.toByteArray(), boundary)
    }

    @Test
    fun `a streamed file part goes out in order, whole, under its name, filename and content type`() = runTest {
        var sent: List<MultipartResponsePart> = emptyList()
        val http = http { request ->
            sent = request.parts()
            respond("""{"id":"file-1"}""", HttpStatusCode.OK)
        }
        val parts = listOf(
            MultipartPart.Field("purpose", "batch"),
            MultipartPart.Field("expires_after[seconds]", "3600"),
            MultipartPart.Stream(
                field = "file",
                fileName = "input.jsonl",
                content = flowOf("{\"a\":1}\n".encodeToByteArray(), "{\"b\":2}\n".encodeToByteArray()),
                byteSize = 16,
                contentType = "application/jsonl",
            ),
        )

        val result = http.postMultipartStream(url, parts)

        // Field parts precede the file exactly as listed — xAI reads the expiry only if it arrives first.
        assertEquals(listOf("purpose", "expires_after[seconds]", "file"), sent.map { it.name })
        assertEquals("3600", sent[1].body.decodeToString())
        assertContains(sent[2].headers.getValue("content-disposition"), "filename=\"input.jsonl\"")
        assertEquals("application/jsonl", sent[2].contentType)
        assertContentEquals("{\"a\":1}\n{\"b\":2}\n".encodeToByteArray(), sent[2].body)
        assertEquals(buildJsonObject { put("id", "file-1") }, result.value)
    }

    @Test
    fun `a filename cannot smuggle a line break or break the quoted string`() = runTest {
        var sent: List<MultipartResponsePart> = emptyList()
        val http = http { request ->
            sent = request.parts()
            respond("{}", HttpStatusCode.OK)
        }

        http.postMultipartStream(
            url,
            listOf(MultipartPart.Stream("file", "a\r\nX-Injected: 1\"b\\c", flowOf("x".encodeToByteArray()))),
        )

        assertContains(sent.single().headers.getValue("content-disposition"), "filename=\"aX-Injected: 1\\\"b\\\\c\"")
    }

    @Test
    fun `a failure before the body starts is retried, one after it has started is not`() = runTest {
        var attempts = 0
        val http = http(retryPolicy = RetryPolicy(maxRetries = 1)) { request ->
            attempts++
            if (attempts == 1) {
                respond("", HttpStatusCode.ServiceUnavailable)
            } else {
                request.body.toByteArray()
                respond("{}", HttpStatusCode.OK)
            }
        }

        http.postMultipartStream(url, listOf(MultipartPart.Stream("file", "a.bin", flowOf("x".encodeToByteArray()))))
        assertEquals(2, attempts)

        // The body was read — the stream has been collected — so a retry would re-collect a source
        // that may be one-shot; the failure is the caller's to retry.
        var reads = 0
        val readThenFail = http(retryPolicy = RetryPolicy(maxRetries = 1)) { request ->
            reads++
            request.body.toByteArray()
            respond("", HttpStatusCode.ServiceUnavailable)
        }

        assertFailsWith<APICallError> {
            readThenFail.postMultipartStream(url, listOf(MultipartPart.Stream("file", "a.bin", flowOf("x".encodeToByteArray()))))
        }
        assertEquals(1, reads)
    }

    @Test
    fun `a source that fails fails the upload with its own error and does not hang`() = runTest {
        val http = http { request ->
            request.body.toByteArray()
            respond("{}", HttpStatusCode.OK)
        }
        val broken = flow<ByteArray> {
            emit("first".encodeToByteArray())
            throw IllegalStateException("disk went away")
        }

        val error = assertFailsWith<IllegalStateException> {
            http.postMultipartStream(url, listOf(MultipartPart.Stream("file", "a.bin", broken)))
        }

        assertEquals("disk went away", error.message)
    }
}
