package com.swaptr.aide.data.search.providers

import android.util.Log
import com.swaptr.aide.data.search.SearchHttp
import com.swaptr.aide.data.search.WebSearchCredentialsRepository
import com.swaptr.aide.domain.search.WebSearchProvider
import com.swaptr.aide.domain.search.WebSearchProviderId
import com.swaptr.aide.domain.search.WebSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TavilyWebSearch"
private const val ENDPOINT = "https://api.tavily.com/search"
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

// Needs api_key in body; absent key → isAvailable=false and chain skips this slot.
@Singleton
class TavilyWebSearchProvider @Inject constructor(
    @SearchHttp private val http: OkHttpClient,
    private val credentials: WebSearchCredentialsRepository,
) : WebSearchProvider {

    override val id: WebSearchProviderId = WebSearchProviderId.TAVILY
    override val displayName: String = "Tavily"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun isAvailable(): Boolean =
        credentials.apiKeyFlow(WebSearchProviderId.TAVILY).first() != null

    override suspend fun search(query: String, max: Int): List<WebSearchResult> =
        withContext(Dispatchers.IO) {
            val key = credentials.apiKeyFlow(WebSearchProviderId.TAVILY).first()
                ?: throw IOException("Tavily API key missing")
            val body = json.encodeToString(
                TavilyRequest.serializer(),
                TavilyRequest(api_key = key, query = query, max_results = max),
            ).toRequestBody(JSON_MEDIA)
            val req = Request.Builder()
                .url(ENDPOINT)
                .header("Accept", "application/json")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val msg = "Tavily HTTP ${resp.code}"
                    Log.w(TAG, msg)
                    throw IOException(msg)
                }
                val raw = resp.body?.string().orEmpty()
                val parsed = runCatching {
                    json.decodeFromString(TavilyResponse.serializer(), raw)
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
