package com.sabreware.aide.data.connector.mcp

import com.sabreware.aide.core.domain.cache.snapshotCache
import com.sabreware.aide.core.domain.mcp.McpServerConfig
import com.sabreware.aide.core.domain.mcp.McpServerRepository
import com.sabreware.aide.core.domain.secure.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persists the MCP server list as one JSON blob in the encrypted [SecureStore] (auth header values are
 * secrets). Mirrors [com.sabreware.aide.data.connection.ConnectionRepositoryImpl]'s use of
 * [SecureStore]; writes are read-modify-write (serialized by the single settings caller).
 */
class McpServerRepositoryImpl(
    private val secrets: SecureStore,
    appScope: CoroutineScope,
) : McpServerRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(McpServerConfig.serializer())

    // The first read decrypts from disk, so it is cached app-wide; warmed after the first frame by the
    // reconnect bootstrap's currentServers(), so even the first Connectors open seeds from it.
    override val servers: StateFlow<List<McpServerConfig>?> =
        secrets.observe(KEY).map { blob -> blob?.let(::decode) ?: emptyList() }
            .snapshotCache(appScope, "mcp servers")

    override suspend fun save(config: McpServerConfig) =
        mutate { current -> current.filter { it.url != config.url } + config }

    override suspend fun remove(url: String) =
        mutate { current -> current.filter { it.url != url } }

    override suspend fun setEnabled(url: String, enabled: Boolean) =
        mutate { current -> current.map { if (it.url == url) it.copy(enabled = enabled) else it } }

    private suspend fun mutate(transform: (List<McpServerConfig>) -> List<McpServerConfig>) {
        // Read the STORE, not [servers] — a read-modify-write against the replay cache could fold a stale
        // snapshot back in and drop a concurrent write.
        val current = secrets.observe(KEY).first()?.let(::decode) ?: emptyList()
        secrets.put(KEY, json.encodeToString(serializer, transform(current)))
    }

    private fun decode(blob: String): List<McpServerConfig> =
        runCatching { json.decodeFromString(serializer, blob) }.getOrDefault(emptyList())

    private companion object {
        private const val KEY = "mcp.servers"
    }
}
