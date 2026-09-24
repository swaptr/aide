package com.sabreware.aide.core.domain.mcp

import kotlinx.serialization.json.JsonObject

/**
 * Port for the MCP (Model Context Protocol) client. Connects to a StreamableHTTP MCP server, discovers
 * its tools, and invokes them. The impl ([com.sabreware.aide.data.connector.mcp.McpClientImpl]) wraps the official
 * `io.modelcontextprotocol:kotlin-sdk` over our Ktor OkHttp engine. Discovered tools become first-class
 * [com.sabreware.aide.core.domain.llm.AideTool.Function]s via [toAideTool], so they flow through the normal
 * `ToolDispatcher` (idempotency / confirm / trace) provider-agnostically — unlike Gallery's single
 * prompt-injected meta-tool, which only works for its on-device prompt-based model.
 */
interface McpClient : McpToolCaller {
    /** Connects to [config]'s server (replacing any prior connection to the same URL) and lists its tools. */
    suspend fun connect(config: McpServerConfig): List<McpToolDescriptor>

    // callTool is inherited from [McpToolCaller].

    /** Drops the connection to [serverUrl]. */
    suspend fun disconnect(serverUrl: String)

    /** Closes all connections + the underlying HTTP client. */
    suspend fun close()
}

/**
 * Invokes a tool on a connected MCP server. Split out from [McpClient] so the retry-owning
 * [com.sabreware.aide.data.connector.mcp.McpConnectionManager] can present itself as the tool caller — wrapping the raw
 * client with a mid-session token-refresh + reconnect + single retry on a 401 (see [McpCallResult.Err]'s
 * `authFailure`) — while the discovered [toAideTool]s stay unaware of which layer they route through.
 */
fun interface McpToolCaller {
    /** Invokes [toolName] on the server at [serverUrl] with JSON [arguments]. */
    suspend fun callTool(serverUrl: String, toolName: String, arguments: JsonObject): McpCallResult
}
