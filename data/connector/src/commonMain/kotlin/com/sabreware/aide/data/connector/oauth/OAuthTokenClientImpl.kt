package com.sabreware.aide.data.connector.oauth

import com.sabreware.aide.core.domain.connector.oauth.AuthorizationServerMetadata
import com.sabreware.aide.core.domain.connector.oauth.OAuthTokenClient
import com.sabreware.aide.core.domain.connector.oauth.TokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters

/**
 * OAuth 2.1 token endpoint: authorization_code exchange and refresh. Form-encoded POST, JSON response.
 * The `resource` (RFC 8707) is sent on the exchange so the token is bound to the specific MCP server.
 */
class OAuthTokenClientImpl(
    private val http: HttpClient,
) : OAuthTokenClient {

    override suspend fun exchangeCode(
        asMetadata: AuthorizationServerMetadata,
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String,
        resource: String,
    ): TokenResponse {
        val resp = http.submitForm(
            url = asMetadata.tokenEndpoint,
            formParameters = parameters {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirectUri)
                append("client_id", clientId)
                append("code_verifier", codeVerifier)
                append("resource", resource)
            },
        )
        check(resp.status.isSuccess()) { "token endpoint returned ${resp.status}" }
        return resp.body()
    }

    override suspend fun refresh(
        tokenEndpoint: String,
        clientId: String,
        refreshToken: String,
        resource: String,
        scope: String?,
    ): TokenResponse {
        val resp = http.submitForm(
            url = tokenEndpoint,
            formParameters = parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", clientId)
                // RFC 8707: re-bind the rotated token to the same MCP server on refresh (some AS require it).
                append("resource", resource)
                if (!scope.isNullOrBlank()) append("scope", scope)
            },
        )
        check(resp.status.isSuccess()) { "refresh ${resp.status}: ${resp.bodyAsText()}" }
        return resp.body()
    }
}
