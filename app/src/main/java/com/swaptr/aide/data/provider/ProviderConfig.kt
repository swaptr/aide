package com.swaptr.aide.data.provider

// Self-hosted and cloud share wire protocol; only baseUrl + auth differ. isCloud disambiguates
// UI mode (engines don't branch on it; registry skips /api/tags probe for cloud).
data class OllamaConfig(
    val baseUrl: String,
    val authToken: String? = null,
    val isCloud: Boolean = false,
) {
    companion object {
        const val CLOUD_BASE_URL = "https://ollama.com"
    }
}

sealed interface ConnectionTestResult {
    data class Ok(val modelCount: Int) : ConnectionTestResult
    data class Failed(val message: String) : ConnectionTestResult
}
