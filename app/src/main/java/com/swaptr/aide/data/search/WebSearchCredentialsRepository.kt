package com.swaptr.aide.data.search

import com.swaptr.aide.data.secure.EncryptedPreferences
import com.swaptr.aide.domain.search.WebSearchProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Uniform encryption-at-rest slot for any web-search provider that needs a dedicated key.
@Singleton
class WebSearchCredentialsRepository @Inject constructor(
    private val secrets: EncryptedPreferences,
) {

    fun apiKeyFlow(provider: WebSearchProviderId): Flow<String?> =
        secrets.observe(keyFor(provider)).map { it?.takeIf { v -> v.isNotBlank() } }

    suspend fun setApiKey(provider: WebSearchProviderId, value: String) {
        if (value.isBlank()) secrets.remove(keyFor(provider))
        else secrets.put(keyFor(provider), value)
    }

    suspend fun clear(provider: WebSearchProviderId) {
        secrets.remove(keyFor(provider))
    }

    private fun keyFor(provider: WebSearchProviderId): String =
        "web_search.${provider.name.lowercase()}.api_key"
}
