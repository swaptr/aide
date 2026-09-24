package com.sabreware.aide.data.connector.directory

import com.sabreware.aide.core.common.persist.DocumentStore
import com.sabreware.aide.core.common.persist.Durability
import com.sabreware.aide.core.common.persist.PersistedDocument
import com.sabreware.aide.core.domain.connector.Connector
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryId
import com.sabreware.aide.core.domain.util.AideLog
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * Per-directory cache of a network directory's connector slice. Bundled directories never touch it; tests
 * substitute a fake. Never throws — a botched payload just leaves the previous cache.
 */
interface ConnectorDirectoryCache {
    /** Cached slice for [id]; empty if none. */
    suspend fun load(id: ConnectorDirectoryId): List<Connector>

    /** Persists [connectors] (must be non-empty) and updates the slice; returns true on success. */
    suspend fun write(id: ConnectorDirectoryId, connectors: List<Connector>): Boolean

    /** True when a cache for [id] exists and is younger than [ttlMillis] (skip the network refresh). */
    suspend fun isFresh(id: ConnectorDirectoryId, ttlMillis: Long): Boolean
}

/** One directory's cached slice and when it was fetched. [Connector] is reused as-is: its schema is the wire's. */
@Serializable
data class CachedDirectory(val fetchedAt: Long = 0L, val connectors: List<Connector> = emptyList())

/** Every network directory's slice, keyed by [ConnectorDirectoryId.value] — the [DocumentConnectorDirectoryCache.Document] schema. */
@Serializable
data class ConnectorDirectories(val directories: Map<String, CachedDirectory> = emptyMap())

/**
 * The ONE [ConnectorDirectoryCache], for every target. It replaced an Android and a desktop class that were
 * the same 75 lines apart from which directory they wrote to — a per-platform class whose only difference
 * was policy — and each kept its own JSON, its own in-memory map and locks, and a freshness check that read
 * file mtimes. Now the schema is a [PersistedDocument] (versioned under `data/connector/schemas/`), the
 * path comes from each host's `PlatformPaths` like every other document, the store is the in-memory copy,
 * and freshness is the recorded fetch time rather than whatever the filesystem says about mtime.
 */
class DocumentConnectorDirectoryCache(
    private val store: DocumentStore<ConnectorDirectories>,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ConnectorDirectoryCache {

    override suspend fun load(id: ConnectorDirectoryId): List<Connector> =
        store.awaitReady().directories[id.value]?.connectors.orEmpty()

    override suspend fun write(id: ConnectorDirectoryId, connectors: List<Connector>): Boolean {
        if (connectors.isEmpty()) {
            AideLog.w(TAG, "directory ${id.value} returned no connectors; keeping current cache")
            return false
        }
        val entry = CachedDirectory(fetchedAt = now(), connectors = connectors)
        val saved = store.update { it.copy(directories = it.directories + (id.value to entry)) } != null
        if (saved) AideLog.i(TAG, "cached ${connectors.size} connectors for ${id.value}")
        return saved
    }

    override suspend fun isFresh(id: ConnectorDirectoryId, ttlMillis: Long): Boolean {
        val entry = store.awaitReady().directories[id.value] ?: return false
        return entry.connectors.isNotEmpty() && now() - entry.fetchedAt < ttlMillis
    }

    companion object {
        private const val TAG = "ConnectorDirCache"

        val Document = PersistedDocument(
            name = "connector_directories",
            version = 1,
            serializer = ConnectorDirectories.serializer(),
            default = ConnectorDirectories(),
            durability = Durability.Cache,
        )
    }
}
