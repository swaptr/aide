package com.sabreware.aide.data.search

import com.sabreware.aide.core.domain.search.FetchOutcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

/**
 * Phase-1 Ktor migration lock for the DuckDuckGo client (no unit tests existed under raw OkHttp). Drives
 * the real `search()` / `fetchOutcome()` paths through a `MockEngine` (JVM, no socket): Ktor GET +
 * `bodyAsText()` + JSoup parse + HTTP-status → error-bucket mapping. `expectSuccess = false` mirrors the
 * production finite client so non-2xx is handled, not thrown.
 */
class DuckDuckGoSearchClientTest {

    private fun clientReturning(status: HttpStatusCode, body: String): DuckDuckGoSearchClient {
        val engine = MockEngine {
            respond(body, status, headersOf(HttpHeaders.ContentType, "text/html"))
        }
        return DuckDuckGoSearchClient(HttpClient(engine) { expectSuccess = false }, Dispatchers.Unconfined)
    }

    @Test
    fun search_parsesHits_andDecodesUddgRedirector() = runTest {
        val html = """
            <div class="result">
              <a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com%2Fpage&rut=abc">Example Title</a>
              <a class="result__snippet">A snippet about the page.</a>
            </div>
        """.trimIndent()
        val hits = clientReturning(HttpStatusCode.OK, html).search("kotlin", max = 3)
        assertEquals(1, hits.size)
        assertEquals("Example Title", hits[0].title)
        assertEquals("https://example.com/page", hits[0].url) // uddg= unwrapped + URL-decoded
        assertEquals("A snippet about the page.", hits[0].snippet)
    }

    @Test
    fun search_httpError_returnsEmpty() = runTest {
        val hits = clientReturning(HttpStatusCode.ServiceUnavailable, "").search("q")
        assertTrue(hits.isEmpty())
    }

    @Test
    fun fetchOutcome_ok_extractsMainText() = runTest {
        val html = "<html><body><nav>menu</nav><article>Hello world body text.</article></body></html>"
        val out = clientReturning(HttpStatusCode.OK, html).fetchOutcome("https://x.test/a", 2500)
        assertTrue(out is FetchOutcome.Text)
        val text = (out as FetchOutcome.Text).text
        assertTrue(text.contains("Hello world body text."))
        assertTrue(!text.contains("menu"), "nav stripped")
    }

    @Test
    fun fetchOutcome_404_mapsTo4xxBucket() = runTest {
        val out = clientReturning(HttpStatusCode.NotFound, "nope").fetchOutcome("https://x.test/a", 2500)
        assertEquals("HTTP_4XX", (out as FetchOutcome.Error).code)
    }

    @Test
    fun fetchOutcome_500_mapsTo5xxBucket() = runTest {
        val out = clientReturning(HttpStatusCode.BadGateway, "boom").fetchOutcome("https://x.test/a", 2500)
        assertEquals("HTTP_5XX", (out as FetchOutcome.Error).code)
    }

    @Test
    fun fetchOutcome_nonHttpScheme_rejectedBeforeNetwork() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.OK) }
        val client = DuckDuckGoSearchClient(HttpClient(engine) { expectSuccess = false }, Dispatchers.Unconfined)
        val out = client.fetchOutcome("ftp://x.test/a", 2500)
        assertEquals("INVALID_URL", (out as FetchOutcome.Error).code)
    }

    @Test
    fun fetchOutcome_blankBody_isEmpty() = runTest {
        val out = clientReturning(HttpStatusCode.OK, "<html><body><article>   </article></body></html>")
            .fetchOutcome("https://x.test/a", 2500)
        assertTrue(out is FetchOutcome.Empty)
    }
}
