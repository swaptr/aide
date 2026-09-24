package com.sabreware.aide.data.search.providers

import com.sabreware.aide.core.domain.provider.OllamaCloud
import com.sabreware.aide.core.domain.search.WebSearchCredentialsRepository
import com.sabreware.aide.core.domain.search.WebSearchProvider
import com.sabreware.aide.core.domain.search.WebSearchProviderId
import com.sabreware.aide.core.domain.search.WebSearchResult
import com.sabreware.aide.core.domain.util.AideLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.flow.first

// Per the official docs, Ollama web search is a CLOUD-ONLY hosted API (ollama.com, free-account API key) —
// NOT served by self-hosted/local Ollama. Keyed in its OWN slot via WebSearchCredentialsRepository (exactly
// like Brave/Tavily), independent of any chat provider: no key = unavailable → the chain falls back. The key
// is entered in Web Search settings. See: https://docs.ollama.com/capabilities/web-search
class OllamaWebSearchProvider(
    private val http: HttpClient,
    private val credentials: WebSearchCredentialsRepository,
) : WebSearchProvider {

    override val id: WebSearchProviderId = WebSearchProviderId.OLLAMA
    override val displayName: String = "Ollama"

    override suspend fun isAvailable(): Boolean =
        credentials.apiKeyFlow(WebSearchProviderId.OLLAMA).first() != null

    override suspend fun search(query: String, max: Int): List<WebSearchResult> {
        val token = credentials.apiKeyFlow(WebSearchProviderId.OLLAMA).first()
            ?: throw IOException("Ollama API key missing")

        val resp = http.post(ENDPOINT) {
            bearerAuth(token)
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(OllamaWebSearchRequest(query = query, max_results = max))
        }
        if (!resp.status.isSuccess()) {
            val msg = "Ollama web_search HTTP ${resp.status.value}"
            AideLog.w(TAG, msg)
            throw IOException(msg)
        }
        val parsed = runCatching { resp.body<OllamaWebSearchResponse>() }.getOrElse {
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

    companion object {
        private const val TAG = "OllamaWebSearch"
        private const val ENDPOINT = "${OllamaCloud.BASE_URL}/api/web_search"
    }
}
