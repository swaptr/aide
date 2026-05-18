package com.swaptr.aide.data.catalog

import android.content.Context

// init() must run from AideApp.onCreate before any DI consumer; pre-init reads return
// empty list (defensive — no NPE if UI races startup).
object ModelCatalog {

    @Volatile
    private var cached: List<ModelSpec> = emptyList()

    // Idempotent: only the first call reads the asset.
    fun init(context: Context) {
        if (cached.isNotEmpty()) return
        synchronized(this) {
            if (cached.isNotEmpty()) return
            val allowlist = AllowlistLoader.load(context)
            cached = allowlist.models.map { it.toModelSpec() }
        }
    }

    val models: List<ModelSpec> get() = cached

    fun findById(id: String): ModelSpec? = cached.firstOrNull { it.id == id }
}
