package com.sabreware.aide.core.domain.search

/**
 * Fetches a web page and returns its main text content (capped), distinguishing an empty body from a
 * typed error. Implementation ([com.sabreware.aide.data.search.DuckDuckGoSearchClient]) is bound via Hilt.
 *
 * `suspend` because the implementation does non-blocking network I/O (Ktor); the sole caller
 * ([com.sabreware.aide.core.domain.tools.WebFetchToolset]) is a suspend tool handler.
 */
interface WebFetcher {
    suspend fun fetchOutcome(url: String, maxChars: Int): FetchOutcome
}
