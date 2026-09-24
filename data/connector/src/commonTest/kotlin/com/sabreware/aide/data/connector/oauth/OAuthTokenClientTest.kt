package com.sabreware.aide.data.connector.oauth

import com.sabreware.aide.core.domain.connector.oauth.AuthorizationServerMetadata
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class OAuthTokenClientTest {

    private var lastForm: String = ""

    private fun http(responseJson: String): HttpClient =
        HttpClient(
            MockEngine { request ->
                lastForm = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
                respond(responseJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    private val asMeta = AuthorizationServerMetadata(
        issuer = "https://as.x.com",
        authorizationEndpoint = "https://as.x.com/auth",
        tokenEndpoint = "https://as.x.com/token",
    )

    @Test
    fun exchangeCode_parsesTokens_andSendsPkceAndResource() = runTest {
        val client = OAuthTokenClientImpl(http("""{"access_token":"AT","token_type":"Bearer","expires_in":3600,"refresh_token":"RT","scope":"files:read"}"""))
        val resp = client.exchangeCode(asMeta, "cid", "code123", "verifier123", "http://127.0.0.1:5/cb", "https://mcp.x.com")
        assertEquals("AT", resp.accessToken)
        assertEquals(3600L, resp.expiresIn)
        assertEquals("RT", resp.refreshToken)
        assertTrue(lastForm.contains("grant_type=authorization_code"), "grant_type")
        assertTrue(lastForm.contains("code_verifier=verifier123"), "code_verifier")
        assertTrue(lastForm.contains("resource="), "resource")
    }

    @Test
    fun refresh_sendsRefreshGrant_withResourceAndScope() = runTest {
        val client = OAuthTokenClientImpl(http("""{"access_token":"AT2","token_type":"Bearer","expires_in":1800}"""))
        val resp = client.refresh("https://as.x.com/token", "cid", "RT", "https://mcp.x.com", "files:read")
        assertEquals("AT2", resp.accessToken)
        assertTrue(lastForm.contains("grant_type=refresh_token"))
        assertTrue(lastForm.contains("refresh_token=RT"))
        // RFC 8707: the rotated token must re-bind to the MCP server (resource) + carry scope.
        assertTrue(lastForm.contains("resource="), "resource")
        assertTrue(lastForm.contains("scope=files"), "scope")
    }
}
