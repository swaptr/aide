package com.swaptr.aide.data.provider

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.ollamaCacheDataStore by preferencesDataStore(name = "ollama_tag_cache")

// /api/show is one round trip per tag — caching avoids gating cold start behind N calls.
@Serializable
data class CachedOllamaTag(
    val name: String,
    // Lazily hydrated so refreshing 50-tag library is one /api/tags trip, not 51.
    val capabilities: List<String> = emptyList(),
    /** `${family}.context_length` from `/api/show.model_info`. Lazily populated. */
    val contextLength: Long? = null,
)

// (baseUrl, isCloud) keys the cache; specs rebuilt on read so wire-schema changes
// don't need a cache migration.
@Serializable
data class CachedOllamaTags(
    val baseUrl: String,
    val isCloud: Boolean,
    val tags: List<CachedOllamaTag>,
    val fetchedAt: Long,
)

// Holds one entry; lets the Cloud tab render instantly on cold start.
@Singleton
class OllamaTagsCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val cacheFlow: Flow<CachedOllamaTags?> =
        context.ollamaCacheDataStore.data.map { prefs ->
            prefs[KEY_CACHE_JSON]?.let(::decode)
        }

    suspend fun write(entry: CachedOllamaTags) {
        val encoded = json.encodeToString(CachedOllamaTags.serializer(), entry)
        context.ollamaCacheDataStore.edit { it[KEY_CACHE_JSON] = encoded }
    }

    suspend fun clear() {
        context.ollamaCacheDataStore.edit { it.remove(KEY_CACHE_JSON) }
    }

    private fun decode(s: String): CachedOllamaTags? =
        runCatching { json.decodeFromString(CachedOllamaTags.serializer(), s) }.getOrNull()

    companion object {
        private val KEY_CACHE_JSON = stringPreferencesKey("entry_json")

        /** Auto-refresh threshold. Older snapshots are still served, but a fetch fires alongside. */
        const val TTL_MS: Long = 60L * 60L * 1000L
    }
}
