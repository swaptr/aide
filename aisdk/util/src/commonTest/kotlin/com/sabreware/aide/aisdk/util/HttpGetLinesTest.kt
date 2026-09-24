package com.sabreware.aide.aisdk.util

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
import kotlin.test.assertNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * [ProviderHttp.getLines] is the streaming half of [ProviderHttp.getBytes]: same redirect and origin
 * guard, lines instead of a body. These pin the framing and that the guard really is shared.
 */
class HttpGetLinesTest {

    private fun http(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ProviderHttp =
        ProviderHttp(HttpClient(MockEngine { request -> handler(request) }))

    @Test
    fun splitsLinesTolerantOfCrlfAndBlankLines() = runTest {
        val http = http { respond("{\"a\":1}\r\n\n{\"b\":2}\n   \n{\"c\":3}", HttpStatusCode.OK) }

        val lines = http.getLines("https://api.example.com/files/f1/content").toList()

        assertEquals(listOf("{\"a\":1}", "{\"b\":2}", "{\"c\":3}"), lines)
    }

    @Test
    fun followsSameOriginRedirectAndKeepsCredentials() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val http = http { request ->
            seen += request
            if (request.url.encodedPath == "/first") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/second"))
            } else {
                respond("x\ny", HttpStatusCode.OK)
            }
        }

        val lines = http.getLines(
            url = "https://api.example.com/first",
            headers = mapOf("Authorization" to "Bearer k"),
            trustedOrigin = "https://api.example.com",
        ).toList()

        assertEquals(listOf("x", "y"), lines)
        assertEquals(2, seen.size)
        assertEquals("Bearer k", seen[1].headers["Authorization"])
    }

    @Test
    fun dropsCredentialsOnCrossOriginRedirect() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val http = http { request ->
            seen += request
            if (request.url.host == "api.example.com") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.example.net/blob"))
            } else {
                respond("z", HttpStatusCode.OK)
            }
        }

        val lines = http.getLines(
            url = "https://api.example.com/file",
            headers = mapOf("Authorization" to "Bearer k"),
            trustedOrigin = "https://api.example.com",
        ).toList()

        assertEquals(listOf("z"), lines)
        assertNull(seen[1].headers["Authorization"])
    }
}
