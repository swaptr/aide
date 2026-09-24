package com.sabreware.aide.core.domain.connector.directory

import com.sabreware.aide.core.domain.connector.Connector
import kotlinx.coroutines.flow.Flow

/**
 * A pluggable source of browsable MCP connectors — a registry or directory. Each adapter fetches from its own
 * backend (bundled data, an HTTP registry, …) and maps to the app's canonical [Connector]. The app-facing
 * catalog ([com.sabreware.aide.core.domain.connector.ConnectorCatalog]) is the priority-ordered merge of every
 * *enabled* directory; the user picks which are enabled (see [ConnectorDirectorySelection]).
 *
 * Add a new source by implementing this interface and `@Binds @IntoSet`-ing it in `ConnectorModule` — nothing
 * else changes (the aggregate, search, settings list, and bootstrap all iterate the multibound set).
 */
interface ConnectorDirectory {

    val descriptor: ConnectorDirectoryDescriptor

    /**
     * A synchronous initial slice, used only to seed the aggregate catalog eagerly so the UI is never empty on
     * the first frame. Bundled directories return their full in-memory list; network directories return empty
     * (their data arrives asynchronously via [catalog]).
     */
    fun seed(): List<Connector> = emptyList()

    /**
     * The directory's current connectors. Bundled directories emit once; network directories emit the cached
     * slice immediately and re-emit after a [refresh].
     */
    fun catalog(): Flow<List<Connector>>

    /** Re-fetch + cache (network directories; TTL-gated inside). No-op for bundled directories. Never throws. */
    suspend fun refresh()

    /**
     * Search this directory for [query]. Local directories filter their in-memory slice (name + description);
     * remote directories may call a server-side search endpoint (and should fall back to a local filter when
     * offline). Implementations should treat a blank query as "everything".
     */
    suspend fun search(query: String): List<Connector>
}

/** Identity + display + capability metadata for a [ConnectorDirectory]. Pure data — carries no behavior. */
data class ConnectorDirectoryDescriptor(
    val id: ConnectorDirectoryId,
    val title: String,
    val description: String,
    val kind: ConnectorDirectoryKind,
    /**
     * How this directory resolves a search. **Display metadata only** — actual behavior is polymorphic via
     * [ConnectorDirectory.search]; no code branches on this. It exists so the settings screen can label a
     * source ("on-device" vs "remote search").
     */
    val searchKind: ConnectorSearchKind,
    /** Merge precedence when the same connector appears in several directories; **lower wins** load-bearing fields. */
    val priority: Int,
    val builtIn: Boolean = true,
)

/** Where a directory's data physically comes from. The launch bootstrap refreshes only [REGISTRY] directories. */
enum class ConnectorDirectoryKind { BUNDLED, REGISTRY }

/** How a directory resolves a search query (display metadata; see [ConnectorDirectoryDescriptor.searchKind]). */
enum class ConnectorSearchKind { LOCAL, REMOTE }
