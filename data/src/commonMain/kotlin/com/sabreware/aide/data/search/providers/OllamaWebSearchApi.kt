package com.sabreware.aide.data.search.providers

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

// POST /api/web_fetch — single-page fetch (distinct from /api/web_search). Cloud-only, Bearer auth.
@Serializable
internal data class OllamaWebFetchRequest(
    val url: String,
)

@Serializable
internal data class OllamaWebFetchResponse(
    val title: String = "",
    val content: String = "",
    val links: List<String> = emptyList(),
)
