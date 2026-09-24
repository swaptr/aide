package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.DownloadError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * [ProviderHttp.getByteStream] is the unframed streaming half of [ProviderHttp.getBytes] — the
 * reference's `createBinaryStreamResponseHandler` — under the same redirect and origin guard as
 * [ProviderHttp.getLines]. These pin that the bytes arrive whole, that the response metadata a download
 * reports its media type from is handed over before the first chunk, and that the guard is shared.
 */
class HttpGetByteStreamTest {

    private fun http(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ProviderHttp =
        ProviderHttp(HttpClient(MockEngine { request -> handler(request) }))

    @Test
    fun `streams the body and reports the response before the first chunk`() = runTest {
        val http = http {
            respond("PDF-BYTES".encodeToByteArray(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/pdf"))
        }
        var opened: HttpResult<Unit>? = null

        val chunks = http.getByteStream("https://api.example.com/files/f1/content", onResponse = { opened = it }).toList()

        assertEquals("PDF-BYTES", chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }.decodeToString())
        assertEquals("application/pdf", opened?.headers?.get("content-type"))
        assertEquals(HttpStatusCode.OK.value, opened?.statusCode)
    }

    @Test
    fun `follows a same-origin redirect with credentials and drops them across origins`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val http = http { request ->
            seen += request
            when (request.url.host) {
                "api.example.com" -> if (request.url.encodedPath == "/first") {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/second"))
                } else {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.example.net/blob"))
                }
                else -> respond("z".encodeToByteArray(), HttpStatusCode.OK)
            }
        }

        val chunks = http.getByteStream(
            url = "https://api.example.com/first",
            headers = mapOf("Authorization" to "Bearer k"),
            trustedOrigin = "https://api.example.com",
        ).toList()

        assertEquals("z", chunks.single().decodeToString())
        assertEquals(3, seen.size)
        assertEquals("Bearer k", seen[1].headers["Authorization"])
        assertNull(seen[2].headers["Authorization"])
    }

    @Test
    fun `a body past the cap is refused as it streams, not after it is held`() = runTest {
        val http = http { respond(ByteArray(64), HttpStatusCode.OK) }

        assertFailsWith<DownloadError> {
            http.getByteStream("https://api.example.com/files/f1/content", maxBytes = 16).toList()
        }
    }
}
