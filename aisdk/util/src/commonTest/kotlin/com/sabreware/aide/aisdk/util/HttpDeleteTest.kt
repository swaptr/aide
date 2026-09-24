package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.APICallError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** [ProviderHttp.delete], the reference's `deleteFromApi`: the verb, the headers, and what "nothing" means. */
class HttpDeleteTest {

    private fun http(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ProviderHttp =
        ProviderHttp(HttpClient(MockEngine { request -> handler(request) }), retryPolicy = RetryPolicy.None)

    @Test
    fun `sends DELETE with the caller's headers and parses the JSON answer`() = runTest {
        var seen: HttpRequestData? = null
        val http = http { request ->
            seen = request
            respond("""{"id":"file-1","deleted":true}""", HttpStatusCode.OK)
        }

        val result = http.delete("https://api.example.com/files/file-1", headers = mapOf("Authorization" to "Bearer k"))

        assertEquals(HttpMethod.Delete, seen?.method)
        assertEquals("Bearer k", seen?.headers?.get("Authorization"))
        assertEquals(buildJsonObject { put("id", "file-1"); put("deleted", true) }, result.value)
        assertEquals(HttpStatusCode.OK.value, result.statusCode)
    }

    @Test
    fun `a successful answer with no body is JsonNull, not an empty-body error`() = runTest {
        val http = http { respond("", HttpStatusCode.NoContent) }

        val result = http.delete("https://api.example.com/files/file-1")

        assertEquals(JsonNull, result.value)
        assertEquals(HttpStatusCode.NoContent.value, result.statusCode)
    }

    @Test
    fun `a failed answer is the vendor's error`() = runTest {
        val http = http { respond("""{"error":{"message":"no such file"}}""", HttpStatusCode.NotFound) }

        val error = assertFailsWith<APICallError> { http.delete("https://api.example.com/files/missing") }

        assertEquals(HttpStatusCode.NotFound.value, error.statusCode)
    }
}
