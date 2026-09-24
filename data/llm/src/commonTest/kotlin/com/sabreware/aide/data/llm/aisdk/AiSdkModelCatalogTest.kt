package com.sabreware.aide.data.llm.aisdk

import com.sabreware.aide.core.domain.provider.ConnectionTestResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The listing that used to be two clients — one hand-rolled Ktor call per vendor — is one call over the
 * SDK's transport. What differs per vendor is headers and one optional field, so that is what is pinned:
 * the request each vendor receives, and the row each vendor's shape produces.
 */
class AiSdkModelCatalogTest {

    private class Server(private val status: HttpStatusCode, private val body: String) {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(
            MockEngine { request ->
                requests += request
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
    }

    @Test
    fun `an OpenAI-shaped listing yields ids with no display name, under a bearer token`() = runTest {
        val server = Server(HttpStatusCode.OK, """{"object":"list","data":[{"id":"gpt-5"},{"id":"o3"}]}""")

        val models = AiSdkModelCatalog(server.client)
            .listModels("https://api.openai.com/v1/", AiSdkModelCatalog.bearerHeaders("sk-1"))

        assertEquals(listOf(ListedModel("gpt-5"), ListedModel("o3")), models)
        val request = server.requests.single()
        // The trailing slash is trimmed rather than producing `/v1//models`.
        assertEquals("https://api.openai.com/v1/models", request.url.toString())
        assertEquals("Bearer sk-1", request.headers[HttpHeaders.Authorization])
        assertNull(request.headers["x-api-key"])
    }

    @Test
    fun `an Anthropic-shaped listing keeps the display name, under x-api-key and the pinned version`() = runTest {
        val server = Server(
            HttpStatusCode.OK,
            """{"data":[{"id":"claude-opus-4-5","display_name":"Claude Opus 4.5","type":"model"}],"has_more":false}""",
        )

        val models = AiSdkModelCatalog(server.client)
            .listModels("https://api.anthropic.com/v1", AiSdkModelCatalog.anthropicHeaders("sk-ant"))

        assertEquals(listOf(ListedModel("claude-opus-4-5", "Claude Opus 4.5")), models)
        val request = server.requests.single()
        assertEquals("sk-ant", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `a keyless server gets no authorization header at all`() = runTest {
        val server = Server(HttpStatusCode.OK, """{"data":[{"id":"llama3"}]}""")

        AiSdkModelCatalog(server.client).listModels("http://localhost:11434/v1", AiSdkModelCatalog.bearerHeaders(null))

        assertNull(server.requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `the connection test reports the count, and a refusal reports the vendor's own sentence`() = runTest {
        val ok = Server(HttpStatusCode.OK, """{"data":[{"id":"a"},{"id":"b"},{"id":"c"}]}""")
        assertEquals(
            ConnectionTestResult.Ok(3),
            AiSdkModelCatalog(ok.client).testConnection("https://api.openai.com/v1", emptyMap()),
        )

        val refused = Server(
            HttpStatusCode.Unauthorized,
            """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""",
        )
        val result = AiSdkModelCatalog(refused.client).testConnection("https://api.anthropic.com/v1", emptyMap())
        assertTrue(result is ConnectionTestResult.Failed)
        assertTrue(
            "invalid x-api-key" in result.message,
            "the transport reads the vendor's error envelope; got: ${result.message}",
        )
    }

    @Test
    fun `a row without an id is skipped rather than failing the listing`() = runTest {
        val server = Server(HttpStatusCode.OK, """{"data":[{"object":"model"},{"id":"kept"}]}""")

        val models = AiSdkModelCatalog(server.client).listModels("https://x/v1", emptyMap())

        assertEquals(listOf(ListedModel("kept")), models)
    }
}
