package com.sabreware.aide.data.search.providers

import com.sabreware.aide.core.domain.provider.OllamaCloud
import com.sabreware.aide.core.domain.search.FetchOutcome
import com.sabreware.aide.core.domain.search.WebFetchProvider
import com.sabreware.aide.core.domain.search.WebFetchProviderId
import com.sabreware.aide.core.domain.search.WebSearchCredentialsRepository
import com.sabreware.aide.core.domain.search.WebSearchProviderId
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

// Ollama single-page fetch (POST /api/web_fetch). Per the official docs this is a CLOUD-ONLY hosted API
// (ollama.com, free-account API key) — NOT served by self-hosted/local Ollama. Keyed in its OWN slot via
// WebSearchCredentialsRepository (shares the Ollama web-search key; entered in Web Search settings),
// independent of any chat provider; no key = unavailable → the chain falls back to DuckDuckGo.
// See: https://docs.ollama.com/capabilities/web-search
class OllamaWebFetcher(
    private val http: HttpClient,
    private val credentials: WebSearchCredentialsRepository,
) : WebFetchProvider {

    override val id: WebFetchProviderId = WebFetchProviderId.OLLAMA

    override suspend fun isAvailable(): Boolean =
        credentials.apiKeyFlow(WebSearchProviderId.OLLAMA).first() != null

    override suspend fun fetch(url: String, maxChars: Int): FetchOutcome {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            return FetchOutcome.Error("INVALID_URL", "url must start with http(s)://")
        }
        val token = credentials.apiKeyFlow(WebSearchProviderId.OLLAMA).first()
            ?: return FetchOutcome.Error("FETCH_FAILED", "Ollama API key missing")
        return try {
            val resp = http.post(ENDPOINT) {
                bearerAuth(token)
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
                setBody(OllamaWebFetchRequest(url = url))
            }
            if (!resp.status.isSuccess()) {
                val bucket = if (resp.status.value in 400..499) "HTTP_4XX" else "HTTP_5XX"
                return FetchOutcome.Error(bucket, "Ollama web_fetch HTTP ${resp.status.value}")
            }
            val parsed = runCatching { resp.body<OllamaWebFetchResponse>() }.getOrElse {
                return FetchOutcome.Error("FETCH_FAILED", "parse failed: ${it.message}")
            }
            val text = parsed.content.trim()
            when {
                text.isEmpty() -> FetchOutcome.Empty("blank_body")
                text.length <= maxChars -> FetchOutcome.Text(text, truncated = false)
                else -> FetchOutcome.Text(text.substring(0, maxChars).trimEnd() + "…", truncated = true)
            }
        } catch (e: io.ktor.client.network.sockets.SocketTimeoutException) {
            FetchOutcome.Error("TIMEOUT", e.message ?: "timeout")
        } catch (e: IOException) {
            FetchOutcome.Error("NETWORK", e.message ?: (e::class.simpleName ?: "error"))
        }
    }

    companion object {
        private val ENDPOINT = "${OllamaCloud.BASE_URL}/api/web_fetch"
    }
}
