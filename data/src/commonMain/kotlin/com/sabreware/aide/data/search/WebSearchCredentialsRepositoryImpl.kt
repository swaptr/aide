package com.sabreware.aide.data.search

import com.sabreware.aide.core.domain.search.WebSearchCredentialsRepository
import com.sabreware.aide.core.domain.search.WebSearchProviderId
import com.sabreware.aide.core.domain.secure.SecureStore
import com.sabreware.aide.core.domain.cache.snapshotCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

// Uniform encryption-at-rest slot for any web-search provider that needs a dedicated key.
class WebSearchCredentialsRepositoryImpl(
    private val secrets: SecureStore,
    appScope: CoroutineScope,
) : WebSearchCredentialsRepository {

    override val configured: StateFlow<Set<WebSearchProviderId>?> =
        combine(WebSearchProviderId.entries.map { id -> apiKeyFlow(id).map { key -> id.takeIf { key != null } } }) {
            it.filterNotNull().toSet()
        }.snapshotCache(appScope, "web search keys")

    override fun apiKeyFlow(provider: WebSearchProviderId): Flow<String?> =
        secrets.observe(keyFor(provider)).map { it?.takeIf { v -> v.isNotBlank() } }

    override suspend fun setApiKey(provider: WebSearchProviderId, value: String) {
        if (value.isBlank()) secrets.remove(keyFor(provider))
        else secrets.put(keyFor(provider), value)
    }

    override suspend fun clear(provider: WebSearchProviderId) {
        secrets.remove(keyFor(provider))
    }

    private fun keyFor(provider: WebSearchProviderId): String =
        "web_search.${provider.name.lowercase()}.api_key"
}
