package com.sabreware.aide.core.domain.connector.oauth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OAuth 2.0 Protected Resource Metadata (RFC 9728) — served by the MCP server, points at its
 * authorization server(s). Parsed leniently (ignoreUnknownKeys).
 */
@Serializable
data class ProtectedResourceMetadata(
    val resource: String? = null,
    @SerialName("authorization_servers") val authorizationServers: List<String> = emptyList(),
    @SerialName("scopes_supported") val scopesSupported: List<String> = emptyList(),
    @SerialName("bearer_methods_supported") val bearerMethodsSupported: List<String> = emptyList(),
)

/**
 * OAuth 2.0 Authorization Server Metadata (RFC 8414) / OpenID Connect Discovery. Only the fields the
 * MCP client needs. [codeChallengeMethodsSupported] absent ⇒ PKCE unsupported ⇒ the spec requires us to
 * refuse the flow.
 */
@Serializable
data class AuthorizationServerMetadata(
    val issuer: String? = null,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("registration_endpoint") val registrationEndpoint: String? = null,
    @SerialName("scopes_supported") val scopesSupported: List<String> = emptyList(),
    @SerialName("code_challenge_methods_supported") val codeChallengeMethodsSupported: List<String> = emptyList(),
    @SerialName("token_endpoint_auth_methods_supported") val tokenEndpointAuthMethodsSupported: List<String> = emptyList(),
    @SerialName("grant_types_supported") val grantTypesSupported: List<String> = emptyList(),
    @SerialName("client_id_metadata_document_supported") val clientIdMetadataDocumentSupported: Boolean = false,
)

/** OAuth 2.1 token endpoint response (authorization_code exchange and refresh). */
@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String? = null,
)
