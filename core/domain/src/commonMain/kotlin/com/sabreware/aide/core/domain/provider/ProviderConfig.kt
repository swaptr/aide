package com.sabreware.aide.core.domain.provider

/**
 * One connection's endpoint and credential, as the provider it backs reads them on each call.
 *
 * How the key is presented on the wire is the provider's business, not this record's: the `:aisdk`
 * provider for a vendor already knows whether it wants a bearer token, `x-api-key` or `xi-api-key`.
 */
data class ProviderConfig(
    val baseUrl: String,
    val apiKey: String? = null,
)

/** Ollama Cloud sentinel base URL (the managed ollama.com endpoint). */
object OllamaCloud {
    const val BASE_URL = "https://ollama.com"
}

sealed interface ConnectionTestResult {
    data class Ok(val modelCount: Int) : ConnectionTestResult
    data class Failed(val message: String) : ConnectionTestResult
}
