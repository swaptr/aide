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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BraveWebSearch"
private const val ENDPOINT = "https://api.search.brave.com/res/v1/web/search"

// Needs X-Subscription-Token header; absent key → isAvailable=false and chain skips this slot.
@Singleton
class BraveWebSearchProvider @Inject constructor(
    @SearchHttp private val http: OkHttpClient,
    private val credentials: WebSearchCredentialsRepository,
) : WebSearchProvider {

    override val id: WebSearchProviderId = WebSearchProviderId.BRAVE
    override val displayName: String = "Brave"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun isAvailable(): Boolean =
        credentials.apiKeyFlow(WebSearchProviderId.BRAVE).first() != null

    override suspend fun search(query: String, max: Int): List<WebSearchResult> =
        withContext(Dispatchers.IO) {
            val token = credentials.apiKeyFlow(WebSearchProviderId.BRAVE).first()
                ?: throw IOException("Brave API key missing")
            val url = ENDPOINT.toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("count", max.toString())
                .build()
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", token)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val msg = "Brave web HTTP ${resp.code}"
                    Log.w(TAG, msg)
                    throw IOException(msg)
                }
                val raw = resp.body?.string().orEmpty()
                val parsed = runCatching {
                    json.decodeFromString(BraveResponse.serializer(), raw)
                }.getOrElse {
                    Log.w(TAG, "parse failed", it)
                    throw IOException("parse failed: ${it.message}")
                }
                parsed.web?.results.orEmpty().take(max).map { hit ->
                    WebSearchResult(
                        title = hit.title.trim(),
                        snippet = hit.description.trim(),
                        url = hit.url.trim(),
                    )
                }
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
