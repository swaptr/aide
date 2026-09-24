package com.sabreware.aide.core.domain.search

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-web-search-provider API key store (Brave / Tavily / Ollama Cloud), keyed by [WebSearchProviderId]
 * and backed by Tink-encrypted prefs (impl in the data layer). Independent of any chat provider's
 * credentials — mirrors [com.sabreware.aide.core.domain.provider.ProviderConfigRepository]. Consumers depend
 * only on this interface (domain), so presentation can read it without an inward data dependency.
 */
interface WebSearchCredentialsRepository {
    fun apiKeyFlow(provider: WebSearchProviderId): Flow<String?>

    /**
     * Which providers have a key — an app-wide snapshot cache (null = not read yet), so the settings sheet
     * and the tool's provider name share one set of decrypts instead of each running its own.
     */
    val configured: StateFlow<Set<WebSearchProviderId>?>

    suspend fun setApiKey(provider: WebSearchProviderId, value: String)
    suspend fun clear(provider: WebSearchProviderId)
}
