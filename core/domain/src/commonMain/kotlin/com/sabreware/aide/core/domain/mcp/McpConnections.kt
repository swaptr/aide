package com.sabreware.aide.core.domain.mcp

import com.sabreware.aide.core.domain.llm.AideTool
import kotlinx.coroutines.flow.StateFlow

/**
 * UI-facing port over the live MCP connections. Lets presentation observe status and drive
 * connect/disconnect/toggle without importing the data-layer manager (keeps the `ui -> data` edge at 0).
 * The data-layer `McpConnectionManager` is the sole implementation; the tool registry folds that
 * manager's discovered tools into chat through the concrete type (a data -> data edge, which is fine).
 */
interface McpConnections {
    /** Live status of every known connection (enabled flag + discovered tool names). */
    val status: StateFlow<List<McpServerStatus>>

    /** Connect (or reconnect) [config]'s server and map its tools. Replaces any prior entry for the URL. */
    suspend fun connect(config: McpServerConfig): Result<List<McpToolDescriptor>>

    /** Drops the connection to [url] and its tools. */
    suspend fun disconnect(url: String)

    /** Toggles a connected server on/off without disconnecting; off servers contribute no tools. */
    suspend fun setEnabled(url: String, enabled: Boolean)

    /**
     * Tools from every connected+enabled server, right now. MCP is the one dynamic slot in the tool stack —
     * these arrive at runtime and belong to no [com.sabreware.aide.core.domain.tools.Toolset] — so the
     * bundle factory folds them in separately.
     */
    fun currentTools(): List<AideTool>
}
