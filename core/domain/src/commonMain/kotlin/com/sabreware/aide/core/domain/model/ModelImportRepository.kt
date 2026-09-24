package com.sabreware.aide.core.domain.model

import kotlinx.coroutines.flow.Flow

/**
 * **What has been imported.** The entries are merged into the registry alongside the bundled allowlist, so
 * an imported model behaves like any other on-disk local model.
 *
 * Every target has one, and a target that cannot import answers "none" — that is a real answer, the same
 * shape as an empty bundled catalog, not a stub. Doing the importing is a separate, optional capability:
 * see [ModelImporter].
 */
interface ModelImportRepository {
    fun observe(): Flow<List<ImportedModelEntry>>

    /** Delete the imported file and drop the persisted entry. No-op if [id] isn't an imported model. */
    suspend fun remove(id: String)
}

/**
 * **Importing a local model file** — a capability, bound only where it exists.
 *
 * Split out from [ModelImportRepository] because the two are not the same question. Reading which models
 * have been imported is something every target must answer (the registry merges them into its catalog);
 * performing an import needs a platform file picker and managed-storage copy, and desktop has neither
 * today. It used to be bound there anyway, as an `import()` that returned
 * `Result.failure(UnsupportedOperationException)` — a capability present in the graph that refuses at the
 * last moment, which is the shape the contribution rule exists to prevent. A target without it binds
 * nothing, and the UI omits the affordance.
 */
interface ModelImporter {
    /**
     * Copy the file at [sourceUri] (a content-uri string) into managed storage and persist the entry.
     * [onProgress] reports copy fraction 0..1. Returns the created entry, or a failure if the copy fails.
     */
    suspend fun import(
        sourceUri: String,
        config: ModelImportConfig,
        onProgress: (Float) -> Unit = {},
    ): Result<ImportedModelEntry>
}
