package com.sabreware.aide.data.search.providers

import com.sabreware.aide.core.domain.search.FetchOutcome
import com.sabreware.aide.core.domain.search.WebFetchProvider
import com.sabreware.aide.core.domain.search.WebFetchProviderId
import com.sabreware.aide.data.search.DuckDuckGoSearchClient

// Terminal fetch fallback (always available) — the keyless DuckDuckGo HTML scraper. Wraps the existing
// [DuckDuckGoSearchClient.fetchOutcome] so the chain always has a usable last resort.
class DuckDuckGoWebFetchProvider(
    private val client: DuckDuckGoSearchClient,
) : WebFetchProvider {
    override val id: WebFetchProviderId = WebFetchProviderId.DUCKDUCKGO
    override suspend fun isAvailable(): Boolean = true
    override suspend fun fetch(url: String, maxChars: Int): FetchOutcome = client.fetchOutcome(url, maxChars)
}
