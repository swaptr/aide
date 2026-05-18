package com.swaptr.aide.data.search.providers

import android.util.Log
import com.swaptr.aide.data.provider.OllamaConfig
import com.swaptr.aide.data.provider.ProviderConfigRepository
import com.swaptr.aide.data.search.SearchHttp
import com.swaptr.aide.domain.search.WebSearchProvider
import com.swaptr.aide.domain.search.WebSearchProviderId
import com.swaptr.aide.domain.search.WebSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// Reuses chat-side Ollama bearer (no separate web-search key); self-hosted w/o token = unavailable.
// See: https://docs.ollama.com/capabilities/web-search
@Singleton
class OllamaWebSearchProvider @Inject constructor(
    @SearchHttp private val http: OkHttpClient,
    private val providerConfig: ProviderConfigRepository,
) : WebSearchProvider {

    override val id: WebSearchProviderId = WebSearchProviderId.OLLAMA
    override val displayName: String = "Ollama"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun isAvailable(): Boolean {
        val cfg = providerConfig.ollamaConfigFlow.first()
        return !cfg?.authToken.isNullOrBlank()
    }

    override suspend fun search(query: String, max: Int): List<WebSearchResult> =
        withContext(Dispatchers.IO) {
            val cfg = providerConfig.ollamaConfigFlow.first()
                ?: throw IOException("Ollama not configured")
            val token = cfg.authToken?.takeIf { it.isNotBlank() }
                ?: throw IOException("Ollama API key missing")

            val body = json.encodeToString(
                OllamaWebSearchRequest.serializer(),
                OllamaWebSearchRequest(query = query, max_results = max),
            ).toRequestBody(JSON_MEDIA)

            val req = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .post(body)
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val msg = "Ollama web_search HTTP ${resp.code}"
                    Log.w(TAG, msg)
                    throw IOException(msg)
                }
                val raw = resp.body?.string().orEmpty()
                val parsed = runCatching {
                    json.decodeFromString(OllamaWebSearchResponse.serializer(), raw)
                }.getOrElse {
                    Log.w(TAG, "parse failed", it)
                    throw IOException("parse failed: ${it.message}")
                }
                parsed.results.take(max).map { hit ->
                    WebSearchResult(
                        title = hit.title.trim(),
                        snippet = hit.content.trim(),
                        url = hit.url.trim(),
                    )
                }
            }
        }

    companion object {
        private const val TAG = "OllamaWebSearch"
        private const val ENDPOINT = "${OllamaConfig.CLOUD_BASE_URL}/api/web_search"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
