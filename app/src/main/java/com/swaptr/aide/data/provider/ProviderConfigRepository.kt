package com.swaptr.aide.data.provider

import com.swaptr.aide.data.secure.EncryptedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderConfigRepository @Inject constructor(
    private val secrets: EncryptedPreferences,
) {

    val ollamaConfigFlow: Flow<OllamaConfig?> = combine(
        secrets.observe(KEY_OLLAMA_BASE_URL),
        secrets.observe(KEY_OLLAMA_TOKEN),
        secrets.observe(KEY_OLLAMA_IS_CLOUD),
    ) { baseUrl, token, isCloudRaw ->
        if (baseUrl.isNullOrBlank()) null
        else OllamaConfig(
            baseUrl = baseUrl.trim(),
            authToken = token?.takeIf { it.isNotBlank() },
            isCloud = isCloudRaw == "true",
        )
    }

    suspend fun setOllamaConfig(config: OllamaConfig) {
        secrets.put(KEY_OLLAMA_BASE_URL, config.baseUrl)
        if (config.authToken.isNullOrBlank()) secrets.remove(KEY_OLLAMA_TOKEN)
        else secrets.put(KEY_OLLAMA_TOKEN, config.authToken)
        secrets.put(KEY_OLLAMA_IS_CLOUD, if (config.isCloud) "true" else "false")
    }

    suspend fun clearOllamaConfig() {
        secrets.remove(KEY_OLLAMA_BASE_URL)
        secrets.remove(KEY_OLLAMA_TOKEN)
        secrets.remove(KEY_OLLAMA_IS_CLOUD)
    }

    companion object {
        private const val KEY_OLLAMA_BASE_URL = "ollama.base_url"
        private const val KEY_OLLAMA_TOKEN = "ollama.token"
        private const val KEY_OLLAMA_IS_CLOUD = "ollama.is_cloud"
    }
}
