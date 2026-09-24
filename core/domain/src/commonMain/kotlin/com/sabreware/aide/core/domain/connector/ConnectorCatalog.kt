package com.sabreware.aide.core.domain.connector

import kotlinx.coroutines.flow.StateFlow

/**
 * Read port for the app-facing connector catalog: the priority-ordered merge of every *enabled*
 * [com.sabreware.aide.core.domain.connector.directory.ConnectorDirectory] (curated overlay, registries, …). Category
 * filtering stays in the ViewModel (cheap in-memory `filter`); [search] fans out to the directories so remote
 * registries can search server-side.
 */
interface ConnectorCatalog {
    /**
     * Reactive merged catalog; re-emits when the directory selection changes or any directory refreshes. A
     * StateFlow so a surface can paint its first frame from [StateFlow.value] (bundled directories at worst).
     */
    fun observeAll(): StateFlow<List<Connector>>

    /** One-shot snapshot of the merged catalog (served from the eager in-memory state). */
    suspend fun all(): List<Connector>

    /** Search across all enabled directories (local filter and/or remote endpoints), merged + deduped. */
    suspend fun search(query: String): List<Connector>
}
