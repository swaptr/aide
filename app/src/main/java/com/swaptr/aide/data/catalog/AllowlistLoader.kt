package com.swaptr.aide.data.catalog

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json

// Process-singleton, cached; parse failure returns empty allowlist so startup degrades
// gracefully instead of crashing.
object AllowlistLoader {

    private const val TAG = "AllowlistLoader"
    private const val ASSET_NAME = "model_allowlist.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: ModelAllowlist? = null

    fun load(context: Context): ModelAllowlist {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val parsed = readFromAssets(context) ?: ModelAllowlist()
            cached = parsed
            return parsed
        }
    }

    private fun readFromAssets(context: Context): ModelAllowlist? = try {
        context.applicationContext.assets.open(ASSET_NAME).bufferedReader().use { reader ->
            val raw = reader.readText()
            json.decodeFromString(ModelAllowlist.serializer(), raw)
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to load $ASSET_NAME", t)
        null
    }
}
