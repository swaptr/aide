package com.sabreware.aide.core.domain.search

// Enum order = fetch-provider priority. DUCKDUCKGO is last: the keyless HTML-scrape fallback, never
// preferred when a key-bearing provider (Ollama Cloud) is configured. Mirrors WebSearchProviderId.
enum class WebFetchProviderId {
    OLLAMA,
    DUCKDUCKGO,
}

/**
 * A single web-page fetcher behind the [WebFetcher] chain ([com.sabreware.aide.data.search.ChainedWebFetcher]).
 * Mirrors [WebSearchProvider]: ordered by [id] priority and gated by [isAvailable] so a key-bearing
 * provider opts in only when configured. Returns a typed [FetchOutcome]; an [FetchOutcome.Error] lets the
 * chain fall through to the next provider.
 */
interface WebFetchProvider {
    val id: WebFetchProviderId
    suspend fun isAvailable(): Boolean
    suspend fun fetch(url: String, maxChars: Int): FetchOutcome
}
