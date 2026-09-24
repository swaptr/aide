package com.sabreware.aide.data.search

import com.fleeksoft.ksoup.Ksoup
import com.sabreware.aide.core.domain.search.FetchOutcome
import com.sabreware.aide.core.domain.search.WebFetcher
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

// Unofficial HTML scraper; brittle — pinned to DDG's current markup, returns zero hits if classes change.
// `suspend` (Ktor) + withContext(IO) so the network call and the JSoup CPU parse stay off the caller thread.
class DuckDuckGoSearchClient(
    private val http: HttpClient,
    private val ioDispatcher: CoroutineDispatcher,
) : WebFetcher {

    data class Hit(val title: String, val snippet: String, val url: String)

    suspend fun search(query: String, max: Int = 3): List<Hit> = withContext(ioDispatcher) {
        val url = ENDPOINT + query.encodeURLParameter()
        val resp = http.get(url) {
            header("User-Agent", USER_AGENT)
            header("Accept-Language", "en-US,en;q=0.9")
        }
        if (!resp.status.isSuccess()) return@withContext emptyList()
        val body = resp.bodyAsText()
        val doc = Ksoup.parse(body)
        doc.select("div.result").take(max).mapNotNull { el ->
            val link = el.selectFirst("a.result__a") ?: return@mapNotNull null
            val title = link.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val rawSnippet = el.selectFirst("a.result__snippet")?.text()?.trim().orEmpty()
            Hit(
                title = title,
                snippet = trimSnippet(title, rawSnippet),
                url = decodeUddg(link.attr("href")),
            )
        }
    }

    override suspend fun fetchOutcome(url: String, maxChars: Int): FetchOutcome = withContext(ioDispatcher) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            return@withContext FetchOutcome.Error("INVALID_URL", "url must start with http(s)://")
        }
        try {
            val resp = http.get(url) {
                header("User-Agent", USER_AGENT)
                header("Accept-Language", "en-US,en;q=0.9")
            }
            if (!resp.status.isSuccess()) {
                val bucket = if (resp.status.value in 400..499) "HTTP_4XX" else "HTTP_5XX"
                return@withContext FetchOutcome.Error(bucket, "HTTP ${resp.status.value}")
            }
            val raw = resp.bodyAsText()
            val doc = Ksoup.parse(raw)
            doc.select(
                "script, style, nav, footer, header, aside, noscript, form, iframe",
            ).remove()
            val main = doc.selectFirst("main, article, [role=main]") ?: doc.body()
            val text = main.text().replace(WHITESPACE_RUN, " ").trim()
            when {
                text.isEmpty() -> FetchOutcome.Empty("blank_body")
                text.length <= maxChars ->
                    FetchOutcome.Text(text, truncated = false)
                else ->
                    FetchOutcome.Text(text.substring(0, maxChars).trimEnd() + "…", truncated = true)
            }
        } catch (e: io.ktor.client.network.sockets.SocketTimeoutException) {
            // Ktor surfaces a read timeout as its own multiplatform type (subtype of IOException).
            FetchOutcome.Error("TIMEOUT", e.message ?: "timeout")
        } catch (e: IOException) {
            FetchOutcome.Error("NETWORK", e.message ?: (e::class.simpleName ?: "error"))
        }
    }

    // Strip title-prefix duplication + length cap so the model's context isn't burned on filler.
    private fun trimSnippet(title: String, snippet: String): String {
        if (snippet.isEmpty()) return snippet
        val stripped = if (snippet.startsWith(title, ignoreCase = true)) {
            snippet.substring(title.length).trimStart(*PREFIX_TRIM)
        } else snippet
        return if (stripped.length <= MAX_SNIPPET) stripped
        else stripped.substring(0, MAX_SNIPPET).trimEnd() + "…"
    }

    // Strip DDG's `uddg=` redirector wrapper so the model sees the real target URL.
    private fun decodeUddg(href: String): String {
        if (href.isBlank()) return href
        val marker = "uddg="
        val idx = href.indexOf(marker)
        if (idx < 0) return if (href.startsWith("//")) "https:$href" else href
        val tail = href.substring(idx + marker.length)
        val end = tail.indexOf('&').let { if (it < 0) tail.length else it }
        return runCatching { tail.substring(0, end).decodeURLPart() }
            .getOrDefault(href)
    }

    companion object {
        private const val ENDPOINT = "https://html.duckduckgo.com/html/?q="
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val MAX_SNIPPET = 200
        private val WHITESPACE_RUN = Regex("\\s+")

        fun hostOf(url: String): String =
            runCatching { Url(url).host }.getOrDefault("")

        private val PREFIX_TRIM = charArrayOf(' ', '-', '–', '—', '·', ':', '|', ' ')
    }
}
