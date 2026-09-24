package com.sabreware.aide.core.domain.search

/**
 * Runs the available web-search providers in priority order (honouring the user's override),
 * returning the first non-empty result within the time budget. Implementation
 * ([com.sabreware.aide.data.search.ProviderChainImpl]) is bound via Hilt; consumers depend only on this.
 */
interface ProviderChain {

    /** First non-empty result set from the provider chain, or empty if none answered in time. */
    suspend fun search(query: String, max: Int): List<WebSearchResult>

    /** Human-readable name of the active provider ("Auto" when no override is set). */
    suspend fun displayName(): String
}
