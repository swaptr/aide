package com.sabreware.aide.core.domain.connector.oauth

/** Discovers the authorization server for an MCP server (RFC 9728 → RFC 8414 / OIDC). */
interface OAuthDiscovery {
    /**
     * Protected Resource Metadata. Prefers the `resource_metadata` hint parsed from a 401's
     * [wwwAuthenticate] header; otherwise probes the well-known URIs (sub-path then root).
     */
    suspend fun protectedResourceMetadata(serverUrl: String, wwwAuthenticate: String?): ProtectedResourceMetadata?

    /**
     * Sends an unauthenticated request to [serverUrl] and, if the server answers 401, returns its raw
     * `WWW-Authenticate` header — whose `resource_metadata` parameter (RFC 9728 §5.1) points at the
     * Protected Resource Metadata. Feed the result straight into [protectedResourceMetadata]'s
     * `wwwAuthenticate` so discovery is spec-compliant instead of relying only on well-known probing. null
     * when the server didn't challenge (then discovery falls back to the well-known URIs).
     */
    suspend fun resourceMetadataChallenge(serverUrl: String): String?

    /** Authorization server metadata, trying the spec's oauth-authorization-server / openid-configuration order. */
    suspend fun authServerMetadata(issuer: String): AuthorizationServerMetadata?
}

/**
 * Obtains an OAuth `client_id` for an authorization server using the best available mechanism, per the
 * MCP spec priority: pre-registered (cached) → CIMD → Dynamic Client Registration. Returns null when none
 * is usable (the UI then prompts the user to paste a manually-registered client_id).
 */
interface ClientRegistrar {
    suspend fun clientId(asMetadata: AuthorizationServerMetadata, redirectUri: String): String?
}

/** Exchanges an authorization code for tokens, and refreshes them. */
interface OAuthTokenClient {
    suspend fun exchangeCode(
        asMetadata: AuthorizationServerMetadata,
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String,
        resource: String,
    ): TokenResponse

    suspend fun refresh(
        tokenEndpoint: String,
        clientId: String,
        refreshToken: String,
        resource: String,
        scope: String? = null,
    ): TokenResponse
}

/**
 * Encrypted cache of OAuth `client_id`s, keyed per (authorization-server, redirect-uri) so DCR isn't
 * repeated. OAuth *tokens* are NOT stored here — they live (encrypted) inside the installed
 * `McpServerConfig.auth`, which is what reconnect/refresh read.
 */
interface OAuthClientStore {
    suspend fun clientId(key: String): String?
    suspend fun putClientId(key: String, clientId: String)
}
