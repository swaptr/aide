package com.sabreware.aide.core.domain.catalog

import com.sabreware.aide.core.domain.model.ChatModelSpec

/**
 * The bundled on-device model allowlist as the registry sees it — a synchronous, platform-free view.
 * Platform impls: Android = `AndroidModelCatalog` (reads the bundled `model_allowlist.json` via
 * `AllowlistLoader`); Desktop = empty (ships no on-device allowlist). Kept a plain interface (not a
 * `suspend` resource read) because the registry consults `models`/`findById` synchronously inside
 * non-suspend `combine` lambdas — see `ModelRegistryRepositoryImpl`.
 */
interface ModelCatalog {
    val models: List<ChatModelSpec>
    fun findById(id: String): ChatModelSpec?
}
