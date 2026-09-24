package com.sabreware.aide.data.connector.mcp

import com.sabreware.aide.core.domain.connector.oauth.OAuthTokenClient
import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.mcp.McpAuth
import com.sabreware.aide.core.domain.mcp.McpCallResult
import com.sabreware.aide.core.domain.mcp.McpClient
import com.sabreware.aide.core.domain.mcp.McpConnections
import com.sabreware.aide.core.domain.mcp.McpServerConfig
import com.sabreware.aide.core.domain.mcp.McpServerRepository
import com.sabreware.aide.core.domain.mcp.McpServerStatus
import com.sabreware.aide.core.domain.mcp.McpToolCaller
import com.sabreware.aide.core.domain.mcp.McpToolDescriptor
import com.sabreware.aide.core.domain.mcp.toAideTool
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.data.connector.oauth.canonicalResource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject

/**
 * Owns the live MCP connections + the [AideTool.Function]s discovered from each server. The tool bundle
 * ([com.sabreware.aide.data.tools.ToolBundleFactoryImpl]) folds [currentTools] into every chat session, so
 * MCP tools flow through the normal `ToolDispatcher` like built-ins (provider-agnostic). Writes are
 * serialized under a mutex; each write republishes a `@Volatile` tool snapshot that [currentTools] reads
 * lock-free (the bundle builder is synchronous). Persistence of server configs is a separate slice.
 */
class McpConnectionManager(
    private val client: McpClient,
    private val tokenClient: OAuthTokenClient,
    private val repo: McpServerRepository,
) : McpConnections, McpToolCaller {
    private class Entry(var config: McpServerConfig, val tools: List<AideTool.Function>)

    private val servers = LinkedHashMap<String, Entry>()
    private val mutex = Mutex()

    @Volatile
    private var toolSnapshot: List<AideTool> = emptyList()

    private val _status = MutableStateFlow<List<McpServerStatus>>(emptyList())
    override val status: StateFlow<List<McpServerStatus>> = _status.asStateFlow()

    /** Lock-free snapshot of ENABLED servers' tools — folded into each session's tool bundle. */
    override fun currentTools(): List<AideTool> = toolSnapshot

    /**
     * Connect (or reconnect) [config]'s server and map its tools. Replaces any prior entry for the URL. An
     * OAuth token within [SKEW_SEC] of expiry is refreshed (+ persisted) FIRST — done here (not just in the
     * cold-start bootstrap) so EVERY connect path (bootstrap, manual reconnect, browse-connect) uses a live
     * token, on every platform.
     */
    override suspend fun connect(config: McpServerConfig): Result<List<McpToolDescriptor>> = mutex.withLock {
        val fresh = refreshIfNeeded(config)
        runCatching {
            val descriptors = client.connect(fresh)
            servers[fresh.url] = Entry(fresh, descriptors.map { it.toAideTool(this@McpConnectionManager) })
            republishLocked()
            AideLog.i(TAG, "connected ${fresh.url}: ${descriptors.size} tools")
            descriptors
        }.onFailure { AideLog.w(TAG, "connect failed for ${fresh.url}: ${it.message}") }
    }

    /**
     * Invokes an MCP tool, transparently recovering from a mid-session OAuth expiry: if the call comes back
     * an auth failure (HTTP 401, see [McpCallResult.Err.authFailure]), force-refresh the token, reconnect
     * with it, and retry the call EXACTLY once. Header/None servers — and any server whose token can't be
     * refreshed — surface the original error unchanged (no blanket retry). This is the sole [callTool] the
     * discovered [toAideTool]s route through (folded into every chat session's tool bundle).
     */
    override suspend fun callTool(serverUrl: String, toolName: String, arguments: JsonObject): McpCallResult {
        val result = client.callTool(serverUrl, toolName, arguments)
        if (result !is McpCallResult.Err || !result.authFailure) return result
        if (!refreshAndReconnect(serverUrl)) return result
        return client.callTool(serverUrl, toolName, arguments)
    }

    /**
     * Force-refresh [url]'s OAuth token (its access token was just rejected server-side) and reconnect with
     * the fresh one. Serialized under [mutex] like every other connection write; returns false — so the
     * original error stands — for a non-OAuth server, an unrefreshable token, or a failed reconnect.
     */
    private suspend fun refreshAndReconnect(url: String): Boolean = mutex.withLock {
        val current = servers[url]?.config ?: return false
        val refreshed = refreshOAuthToken(current) ?: return false
        runCatching {
            val descriptors = client.connect(refreshed)
            servers[refreshed.url] = Entry(refreshed, descriptors.map { it.toAideTool(this@McpConnectionManager) })
            republishLocked()
            AideLog.i(TAG, "reconnected ${refreshed.url} after a mid-session 401 refresh")
        }.onFailure { AideLog.w(TAG, "401 reconnect failed for ${refreshed.url}: ${it.message}") }.isSuccess
    }

    /** Refresh an OAuth access token within [SKEW_SEC] of expiry before (re)connecting; persist the rotation. */
    private suspend fun refreshIfNeeded(config: McpServerConfig): McpServerConfig {
        val auth = config.auth as? McpAuth.OAuth ?: return config
        val expiresAt = auth.expiresAtEpochSec ?: return config
        if (Clock.System.now().epochSeconds < expiresAt - SKEW_SEC) return config
        return refreshOAuthToken(config) ?: config
    }

    /**
     * Rotate an OAuth access token via the refresh grant and persist it — used both proactively near expiry
     * ([refreshIfNeeded]) and reactively on a mid-session 401 ([refreshAndReconnect]). null when [config]
     * isn't OAuth, carries no refresh token, or the refresh call fails.
     */
    private suspend fun refreshOAuthToken(config: McpServerConfig): McpServerConfig? {
        val auth = config.auth as? McpAuth.OAuth ?: return null
        val refreshToken = auth.refreshToken ?: return null
        val token = runCatching {
            tokenClient.refresh(
                tokenEndpoint = auth.tokenEndpoint,
                clientId = auth.clientId,
                refreshToken = refreshToken,
                resource = canonicalResource(config.url), // RFC 8707 — re-bind the rotated token to this server
                scope = auth.scope,
            )
        }.getOrNull() ?: return null
        val refreshed = config.copy(
            auth = auth.copy(
                accessToken = token.accessToken,
                refreshToken = token.refreshToken ?: refreshToken,
                expiresAtEpochSec = token.expiresIn?.let { Clock.System.now().epochSeconds + it },
                scope = token.scope ?: auth.scope,
            ),
        )
        repo.save(refreshed)
        return refreshed
    }

    /** Drops the connection to [url] and its tools. */
    override suspend fun disconnect(url: String): Unit = mutex.withLock {
        servers.remove(url)
        runCatching { client.disconnect(url) }
        republishLocked()
    }

    /** Toggles a connected server on/off without disconnecting; off servers contribute no tools. */
    override suspend fun setEnabled(url: String, enabled: Boolean): Unit = mutex.withLock {
        servers[url]?.let { it.config = it.config.copy(enabled = enabled) }
        republishLocked()
    }

    private fun republishLocked() {
        toolSnapshot = servers.values.filter { it.config.enabled }.flatMap { it.tools }
        _status.value = servers.values.map { entry ->
            McpServerStatus(
                url = entry.config.url,
                enabled = entry.config.enabled,
                toolNames = entry.tools.map { it.name },
            )
        }
    }

    private companion object {
        private const val TAG = "McpConnectionManager"
        private const val SKEW_SEC = 60L
    }
}
