package com.sabreware.aide.app.data.catalog

import android.content.Context
import android.util.Log
import com.sabreware.aide.data.catalog.ModelAllowlist
import java.io.File
import kotlinx.serialization.json.Json

// Process-singleton, cached. Source priority: on-disk remote cache → bundled asset → empty (defensive,
// no crash on startup). A bad/empty source is skipped so a botched remote update can't brick the catalog.
object AllowlistLoader {

    private const val TAG = "AllowlistLoader"
    private const val ASSET_NAME = "model_allowlist.json"
    private const val CACHE_NAME = "model_allowlist_cache.json"

    /**
     * Remote publish endpoint for the version-keyed allowlist JSON (e.g. a raw-host
     * `…/model_allowlist/v3.json`). Empty = remote fetch disabled; the bundled asset is the only source.
     * Fill this to enable [RemoteAllowlistBootstrap]'s background refresh.
     */
    const val REMOTE_URL = ""

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: ModelAllowlist? = null

    fun load(context: Context): ModelAllowlist {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val parsed = readFromCache(context) ?: readFromAssets(context) ?: ModelAllowlist()
            cached = parsed
            return parsed
        }
    }

    /**
     * Validates [raw] (must parse and list ≥1 model) and writes it to the on-disk cache so the NEXT launch
     * picks it up. Returns true on success. Never throws — a bad payload simply leaves the current source.
     */
    fun writeCache(context: Context, raw: String): Boolean = try {
        val parsed = json.decodeFromString(ModelAllowlist.serializer(), raw)
        if (parsed.models.isEmpty()) {
            Log.w(TAG, "remote allowlist parsed but had no models; keeping current source")
            false
        } else {
            cacheFile(context).writeText(raw)
            Log.i(TAG, "cached remote allowlist (${parsed.models.size} models)")
            true
        }
    } catch (t: Throwable) {
        Log.w(TAG, "remote allowlist invalid; keeping current source", t)
        false
    }

    private fun cacheFile(context: Context): File = File(context.applicationContext.filesDir, CACHE_NAME)

    private fun readFromCache(context: Context): ModelAllowlist? {
        val file = cacheFile(context)
        if (!file.exists()) return null
        return try {
            json.decodeFromString(ModelAllowlist.serializer(), file.readText())
        } catch (t: Throwable) {
            Log.w(TAG, "bad allowlist cache; falling back to bundled asset", t)
            null
        }
    }

    private fun readFromAssets(context: Context): ModelAllowlist? = try {
        context.applicationContext.assets.open(ASSET_NAME).bufferedReader().use { reader ->
            json.decodeFromString(ModelAllowlist.serializer(), reader.readText())
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to load $ASSET_NAME", t)
        null
    }
}
