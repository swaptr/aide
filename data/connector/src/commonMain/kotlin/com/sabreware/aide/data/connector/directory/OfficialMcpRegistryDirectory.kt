package com.sabreware.aide.data.connector.directory

import com.sabreware.aide.core.domain.connector.Connector
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectory
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryDescriptor
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryId
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryKind
import com.sabreware.aide.core.domain.connector.directory.ConnectorSearchKind
import com.sabreware.aide.core.domain.connector.directory.matchingQuery
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.data.connector.registry.McpRegistryClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The official community MCP Registry (registry.modelcontextprotocol.io) as a directory. Network-backed:
 * [catalog] serves the on-disk cache (loaded lazily on first subscription) and re-emits after [refresh]
 * re-fetches the remote slice (TTL-gated, 24h). [search] hits the registry's server-side `?search=` for
 * full-registry coverage, falling back to a local name+description filter over the cache when offline.
 */
class OfficialMcpRegistryDirectory(
    private val client: McpRegistryClient,
    private val cache: ConnectorDirectoryCache,
    private val io: CoroutineDispatcher,
) : ConnectorDirectory {

    override val descriptor = ConnectorDirectoryDescriptor(
        id = ID,
        title = "MCP Registry",
        description = "The official community registry of remote MCP servers.",
        kind = ConnectorDirectoryKind.REGISTRY,
        searchKind = ConnectorSearchKind.REMOTE,
        priority = 20,
    )

    private val state = MutableStateFlow<List<Connector>>(emptyList())
    private val loadLock = Mutex()
    @Volatile private var loaded = false

    private suspend fun ensureLoaded() {
        if (loaded) return
        loadLock.withLock {
            if (loaded) return
            state.value = cache.load(ID)
            loaded = true
        }
    }

    override fun catalog(): Flow<List<Connector>> = flow {
        ensureLoaded()
        emitAll(state)
    }

    override suspend fun refresh() {
        ensureLoaded()
        if (cache.isFresh(ID, TTL_MILLIS)) return
        runCatching {
            val fresh = withContext(io) { client.fetchAllRemote() }
            if (cache.write(ID, fresh)) state.value = fresh
        }.onFailure { AideLog.w(TAG, "registry refresh failed: ${it.message}") }
    }

    override suspend fun search(query: String): List<Connector> {
        if (query.isBlank()) {
            ensureLoaded()
            return state.value
        }
        // Remote first (full-registry, name-only); fall back to local name+description over the cache.
        return runCatching { withContext(io) { client.searchRemote(query) } }
            .getOrElse { cache.load(ID).matchingQuery(query) }
    }

    companion object {
        val ID = ConnectorDirectoryId("official-mcp-registry")
        private const val TAG = "McpRegistryDir"
        private const val TTL_MILLIS = 24L * 60 * 60 * 1000
    }
}
