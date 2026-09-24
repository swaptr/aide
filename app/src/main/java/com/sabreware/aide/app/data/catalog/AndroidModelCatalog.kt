package com.sabreware.aide.app.data.catalog

import android.content.Context
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.catalog.ModelCatalog
import com.sabreware.aide.data.catalog.toModelSpec

/**
 * The bundled model allowlist (assets), parsed once. `@Singleton` so consumers inject it rather than
 * reading a global object with a load-bearing `init()` order; the parse is lazy + memoised on first
 * access (small, synchronous, main-safe), so there is nothing to initialize at app startup.
 */
// Android impl of the commonMain [ModelCatalog] interface — reads the bundled `model_allowlist.json`
// from assets (via AllowlistLoader). Desktop/iOS ship no on-device allowlist (empty impl).
class AndroidModelCatalog(
    private val context: Context,
) : ModelCatalog {
    override val models: List<ChatModelSpec> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AllowlistLoader.load(context).models.map { it.toModelSpec() }
    }

    override fun findById(id: String): ChatModelSpec? = models.firstOrNull { it.id == id }
}
