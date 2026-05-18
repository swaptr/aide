package com.swaptr.aide.data.search.providers

import kotlinx.serialization.Serializable

// Cloud-only endpoint (self-hosted doesn't serve it); always needs Bearer auth.
// See: https://docs.ollama.com/capabilities/web-search
@Serializable
internal data class OllamaWebSearchRequest(
    val query: String,
    val max_results: Int? = null,
)

@Serializable
internal data class OllamaWebSearchResponse(
    val results: List<OllamaWebSearchHit> = emptyList(),
)

@Serializable
internal data class OllamaWebSearchHit(
    val title: String = "",
    val url: String = "",
    val content: String = "",
)
