package com.sabreware.aide.data.search.providers

import com.sabreware.aide.core.domain.search.FetchOutcome
import com.sabreware.aide.core.domain.search.WebSearchCredentialsRepository
import com.sabreware.aide.core.domain.search.WebSearchProviderId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * Locks the Ollama Cloud `/api/web_fetch` provider (Ktor POST, ContentNegotiation `body<T>()`): key-gated
 * availability (dedicated [WebSearchCredentialsRepository], independent of any chat provider),
 * `{title,content,links}` → [FetchOutcome] mapping, and HTTP/scheme error buckets.
 */
class OllamaWebFetcherTest {

    private fun creds(apiKey: String?) = object : WebSearchCredentialsRepository {
        override fun apiKeyFlow(provider: WebSearchProviderId): Flow<String?> = flowOf(apiKey)
        override val configured = kotlinx.coroutines.flow.MutableStateFlow<Set<WebSearchProviderId>?>(null)
        override suspend fun setApiKey(provider: WebSearchProviderId, value: String) {}
        override suspend fun clear(provider: WebSearchProviderId) {}
    }

    private fun fetcher(apiKey: String?, status: HttpStatusCode, body: String): OllamaWebFetcher {
        val engine = MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        val http = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return OllamaWebFetcher(http, creds(apiKey))
    }

    @Test
    fun isAvailable_keyOnly() = runTest {
        assertTrue(fetcher("key", HttpStatusCode.OK, "{}").isAvailable())
        assertFalse(fetcher(null, HttpStatusCode.OK, "{}").isAvailable())
    }

    @Test
    fun fetch_ok_returnsContentText() = runTest {
        val body = """{"title":"Example","content":"The page body text.","links":["https://a","https://b"]}"""
        val out = fetcher("key", HttpStatusCode.OK, body).fetch("https://x.test/a", 2500)
        assertEquals("The page body text.", (out as FetchOutcome.Text).text)
    }

    @Test
    fun fetch_404_mapsHttp4xx() = runTest {
        val out = fetcher("key", HttpStatusCode.NotFound, "nope").fetch("https://x.test/a", 2500)
        assertEquals("HTTP_4XX", (out as FetchOutcome.Error).code)
    }

    @Test
    fun fetch_nonHttpScheme_rejected() = runTest {
        val out = fetcher("key", HttpStatusCode.OK, "{}").fetch("ftp://x.test", 2500)
        assertEquals("INVALID_URL", (out as FetchOutcome.Error).code)
    }

    @Test
    fun fetch_blankContent_isEmpty() = runTest {
        val out = fetcher("key", HttpStatusCode.OK, """{"title":"x","content":"   ","links":[]}""")
            .fetch("https://x.test", 2500)
        assertTrue(out is FetchOutcome.Empty)
    }
}
