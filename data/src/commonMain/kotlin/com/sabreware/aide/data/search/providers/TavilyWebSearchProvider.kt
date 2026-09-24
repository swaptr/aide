package com.sabreware.aide.data.search.providers

import com.sabreware.aide.core.domain.search.WebSearchCredentialsRepository
import com.sabreware.aide.core.domain.search.WebSearchProvider
import com.sabreware.aide.core.domain.search.WebSearchProviderId
import com.sabreware.aide.core.domain.search.WebSearchResult
import com.sabreware.aide.core.domain.util.AideLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

private const val TAG = "TavilyWebSearch"
private const val ENDPOINT = "https://api.tavily.com/search"

// Needs api_key in body; absent key → isAvailable=false and chain skips this slot.
class TavilyWebSearchProvider(
    private val http: HttpClient,
    private val credentials: WebSearchCredentialsRepository,
) : WebSearchProvider {

    override val id: WebSearchProviderId = WebSearchProviderId.TAVILY
    override val displayName: String = "Tavily"

    override suspend fun isAvailable(): Boolean =
        credentials.apiKeyFlow(WebSearchProviderId.TAVILY).first() != null

    override suspend fun search(query: String, max: Int): List<WebSearchResult> {
        val key = credentials.apiKeyFlow(WebSearchProviderId.TAVILY).first()
            ?: throw IOException("Tavily API key missing")
        val resp = http.post(ENDPOINT) {
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(TavilyRequest(api_key = key, query = query, max_results = max))
        }
        if (!resp.status.isSuccess()) {
            val msg = "Tavily HTTP ${resp.status.value}"
            AideLog.w(TAG, msg)
            throw IOException(msg)
        }
        val parsed = runCatching { resp.body<TavilyResponse>() }.getOrElse {
            AideLog.w(TAG, "parse failed", it)
            throw IOException("parse failed: ${it.message}")
        }
        return parsed.results.take(max).map { hit ->
            WebSearchResult(
                title = hit.title.trim(),
                snippet = hit.content.trim(),
                url = hit.url.trim(),
            )
        }
    }
}

@Serializable
private data class TavilyRequest(
    val api_key: String,
    val query: String,
    val max_results: Int = 5,
    val search_depth: String = "basic",
)

@Serializable
private data class TavilyResponse(
    val results: List<TavilyHit> = emptyList(),
)

@Serializable
private data class TavilyHit(
    val title: String = "",
    val url: String = "",
    val content: String = "",
)
