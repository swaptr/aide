package com.swaptr.aide.data.search.providers

import com.swaptr.aide.data.search.DuckDuckGoSearchClient
import com.swaptr.aide.domain.search.WebSearchProvider
import com.swaptr.aide.domain.search.WebSearchProviderId
import com.swaptr.aide.domain.search.WebSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// Terminal fallback (isAvailable always true) so the resolver always has a usable provider.
@Singleton
class DuckDuckGoWebSearchProvider @Inject constructor(
    private val client: DuckDuckGoSearchClient,
) : WebSearchProvider {

    override val id: WebSearchProviderId = WebSearchProviderId.DUCKDUCKGO
    override val displayName: String = "DuckDuckGo"

    override suspend fun isAvailable(): Boolean = true

    override suspend fun search(query: String, max: Int): List<WebSearchResult> =
        withContext(Dispatchers.IO) {
            client.search(query, max).map {
                WebSearchResult(title = it.title, snippet = it.snippet, url = it.url)
            }
        }
}
