package com.sabreware.aide.core.domain.search

// Enum order = default auto-pick priority. DUCKDUCKGO is last because the scraper
// fallback needs no key — never preferred when a key-bearing provider is configured.
enum class WebSearchProviderId {
    BRAVE,
    TAVILY,
    OLLAMA,
    DUCKDUCKGO,
}

data class WebSearchResult(
    val title: String,
    val snippet: String,
    val url: String,
)

interface WebSearchProvider {

    val id: WebSearchProviderId

    val displayName: String

    // Empty list = no results; throwing = I tried and failed. Tool layer distinguishes both.
    suspend fun isAvailable(): Boolean

    suspend fun search(query: String, max: Int): List<WebSearchResult>
}
