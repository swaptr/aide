package com.sabreware.aide.core.domain.search

import kotlinx.coroutines.flow.Flow

/**
 * Resolves which [WebSearchProvider] is active: honours the user's explicit override, else walks the
 * priority list to the first available provider. Implementation
 * ([com.sabreware.aide.data.search.WebSearchResolverImpl]) is bound via Hilt; consumers depend only on
 * this interface.
 */
interface WebSearchResolver {

    /** The active provider — override if set + available, else first available by priority. */
    suspend fun resolve(): WebSearchProvider

    /** Display name of the active provider, re-emitted whenever the override preference changes. */
    fun activeProviderNameFlow(): Flow<String>

    /** All known providers in priority order. */
    fun allProviders(): List<WebSearchProvider>
}
