package com.sabreware.aide.core.domain.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A configured MCP server: its StreamableHTTP URL + how it authenticates ([McpAuth]: none / static
 * header / OAuth bearer). `@Serializable` so the repository can persist the list (the auth — header
 * value or OAuth tokens — is a secret → encrypted store).
 */
@Serializable
data class McpServerConfig(
    val url: String,
    val auth: McpAuth = McpAuth.None,
    val enabled: Boolean = true,
)

/** A tool discovered on an MCP server: its JSON-Schema input + which server owns it. */
data class McpToolDescriptor(
    val serverUrl: String,
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/** Result of an MCP tool call: the joined text content, or a typed error message. */
sealed interface McpCallResult {
    data class Ok(val text: String) : McpCallResult

    /**
     * A failed tool call. [authFailure] is set when the server rejected the request as unauthorized
     * (HTTP 401) — the signal [com.sabreware.aide.data.connector.mcp.McpConnectionManager] uses to refresh the OAuth
     * token, reconnect, and retry the call once before surfacing the error.
     */
    data class Err(val message: String, val authFailure: Boolean = false) : McpCallResult
}

/** Live status of a connected MCP server, surfaced to the management UI. */
data class McpServerStatus(
    val url: String,
    val enabled: Boolean,
    val toolNames: List<String>,
)
