package com.sabreware.aide.data.search.providers

import com.sabreware.aide.core.domain.search.WebSearchCredentialsRepository
import com.sabreware.aide.core.domain.search.WebSearchProvider
import com.sabreware.aide.core.domain.search.WebSearchProviderId
import com.sabreware.aide.core.domain.search.WebSearchResult
import com.sabreware.aide.core.domain.util.AideLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

private const val TAG = "BraveWebSearch"
private const val ENDPOINT = "https://api.search.brave.com/res/v1/web/search"

// Needs X-Subscription-Token header; absent key → isAvailable=false and chain skips this slot.
class BraveWebSearchProvider(
    private val http: HttpClient,
    private val credentials: WebSearchCredentialsRepository,
) : WebSearchProvider {

    override val id: WebSearchProviderId = WebSearchProviderId.BRAVE
    override val displayName: String = "Brave"

    override suspend fun isAvailable(): Boolean =
        credentials.apiKeyFlow(WebSearchProviderId.BRAVE).first() != null

    override suspend fun search(query: String, max: Int): List<WebSearchResult> {
        val token = credentials.apiKeyFlow(WebSearchProviderId.BRAVE).first()
            ?: throw IOException("Brave API key missing")
        val resp = http.get(ENDPOINT) {
            accept(ContentType.Application.Json)
            header("X-Subscription-Token", token)
            parameter("q", query)
            parameter("count", max.toString())
        }
        if (!resp.status.isSuccess()) {
            val msg = "Brave web HTTP ${resp.status.value}"
            AideLog.w(TAG, msg)
            throw IOException(msg)
        }
        val parsed = runCatching { resp.body<BraveResponse>() }.getOrElse {
            AideLog.w(TAG, "parse failed", it)
            throw IOException("parse failed: ${it.message}")
        }
        return parsed.web?.results.orEmpty().take(max).map { hit ->
            WebSearchResult(
                title = hit.title.trim(),
                snippet = hit.description.trim(),
                url = hit.url.trim(),
            )
        }
    }
}

@Serializable
private data class BraveResponse(
    val web: BraveWeb? = null,
)

@Serializable
private data class BraveWeb(
    val results: List<BraveHit> = emptyList(),
)

@Serializable
private data class BraveHit(
    val title: String = "",
    val url: String = "",
    val description: String = "",
)
