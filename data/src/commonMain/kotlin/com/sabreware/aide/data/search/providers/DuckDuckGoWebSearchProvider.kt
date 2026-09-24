package com.sabreware.aide.data.search.providers

import com.sabreware.aide.core.domain.search.WebSearchProvider
import com.sabreware.aide.core.domain.search.WebSearchProviderId
import com.sabreware.aide.core.domain.search.WebSearchResult
import com.sabreware.aide.data.search.DuckDuckGoSearchClient

// Terminal fallback (isAvailable always true) so the resolver always has a usable provider.
class DuckDuckGoWebSearchProvider(
    private val client: DuckDuckGoSearchClient,
) : WebSearchProvider {

    override val id: WebSearchProviderId = WebSearchProviderId.DUCKDUCKGO
    override val displayName: String = "DuckDuckGo"

    override suspend fun isAvailable(): Boolean = true

    // client.search() is now suspend and already hops to Dispatchers.IO for the network + JSoup parse.
    override suspend fun search(query: String, max: Int): List<WebSearchResult> =
        client.search(query, max).map {
            WebSearchResult(title = it.title, snippet = it.snippet, url = it.url)
        }
}
