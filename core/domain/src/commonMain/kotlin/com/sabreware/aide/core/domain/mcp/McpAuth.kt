package com.sabreware.aide.core.domain.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Auth a connected MCP server uses, embedded in [McpServerConfig]. Mirrors Google AI Edge Gallery's
 * `McpAuth` proto oneof (`none | request_header | oauth`) so the models line up — but where Gallery left
 * [OAuth] an empty disabled stub, aide fills it in. The whole config is persisted encrypted via Tink.
 */
@Serializable
sealed interface McpAuth {
    @Serializable
    @SerialName("none")
    data object None : McpAuth

    /** Static request header (e.g. `Authorization: Bearer <key>` or a custom API-key header). */
    @Serializable
    @SerialName("header")
    data class Header(val name: String, val value: String) : McpAuth

    /** OAuth 2.1 bearer token obtained via the connector login flow. Secret — encrypted at rest. */
    @Serializable
    @SerialName("oauth")
    data class OAuth(
        val accessToken: String,
        val refreshToken: String? = null,
        val expiresAtEpochSec: Long? = null,
        /** Where to refresh — the authorization server's token endpoint. */
        val tokenEndpoint: String,
        val clientId: String,
        val scope: String? = null,
        /** RFC 8707 canonical MCP server URI the token is bound to. */
        val resource: String,
    ) : McpAuth
}
