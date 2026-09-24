package com.sabreware.aide.core.domain.mcp

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Persisted MCP server configs (the source of truth for what *should* be connected). Auth header values
 * are secrets, so the impl ([com.sabreware.aide.data.connector.mcp.McpServerRepositoryImpl]) stores the list in the
 * Tink-encrypted prefs. The live connections + discovered tools live in
 * [com.sabreware.aide.data.connector.mcp.McpConnectionManager]; a launch bootstrap reconnects persisted servers.
 */
interface McpServerRepository {
    /**
     * The config list, cached app-wide: null until the encrypted store's first read of the session, then
     * the latest list (see [com.sabreware.aide.core.domain.model.ModelRegistryRepository.models] — same
     * shape, same reason: the first read decrypts from disk, so no surface should pay it per open).
     */
    val servers: StateFlow<List<McpServerConfig>?>

    /** The stored list, suspending through the first read if it hasn't resolved yet. */
    suspend fun currentServers(): List<McpServerConfig> = servers.filterNotNull().first()

    /** Upsert by URL. */
    suspend fun save(config: McpServerConfig)

    suspend fun remove(url: String)

    suspend fun setEnabled(url: String, enabled: Boolean)
}
