package com.sabreware.aide.data.connector.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class OAuthDiscoveryTest {

    private fun client(route: (path: String, full: String) -> String?): HttpClient =
        HttpClient(
            MockEngine { request ->
                val body = route(request.url.encodedPath, request.url.toString())
                if (body == null) respond("", HttpStatusCode.NotFound)
                else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    @Test
    fun prm_discoveredFromWellKnownRoot() = runTest {
        val http = client { path, _ ->
            if (path == "/.well-known/oauth-protected-resource")
                """{"resource":"https://mcp.x.com","authorization_servers":["https://as.x.com"]}"""
            else null
        }
        val prm = OAuthDiscoveryImpl(http).protectedResourceMetadata("https://mcp.x.com/mcp", null)
        assertEquals(listOf("https://as.x.com"), prm?.authorizationServers)
    }

    @Test
    fun prm_prefersWwwAuthenticateHint() = runTest {
        val hintUrl = "https://mcp.x.com/.well-known/oauth-protected-resource/tenant"
        val http = client { _, full ->
            if (full == hintUrl) """{"authorization_servers":["https://as.x.com"]}""" else null
        }
        val prm = OAuthDiscoveryImpl(http).protectedResourceMetadata(
            "https://mcp.x.com/mcp",
            """Bearer resource_metadata="$hintUrl", scope="files:read"""",
        )
        assertNotNull(prm)
        assertEquals(listOf("https://as.x.com"), prm.authorizationServers)
    }

    @Test
    fun resourceMetadataChallenge_returnsWwwAuthenticateOn401() = runTest {
        val header = """Bearer resource_metadata="https://mcp.x.com/.well-known/oauth-protected-resource""""
        val http = HttpClient(
            MockEngine { respond("", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, header)) },
        ) { expectSuccess = false }
        assertEquals(header, OAuthDiscoveryImpl(http).resourceMetadataChallenge("https://mcp.x.com/mcp"))
    }

    @Test
    fun resourceMetadataChallenge_nullWhenServerDoesNotChallenge() = runTest {
        val http = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK) }) { expectSuccess = false }
        assertNull(OAuthDiscoveryImpl(http).resourceMetadataChallenge("https://mcp.x.com/mcp"))
    }

    @Test
    fun asMetadata_fromOAuthAuthorizationServer() = runTest {
        val http = client { path, _ ->
            if (path == "/.well-known/oauth-authorization-server")
                """{"issuer":"https://as.x.com","authorization_endpoint":"https://as.x.com/auth","token_endpoint":"https://as.x.com/token","code_challenge_methods_supported":["S256"]}"""
            else null
        }
        val meta = OAuthDiscoveryImpl(http).authServerMetadata("https://as.x.com")
        assertEquals("https://as.x.com/token", meta?.tokenEndpoint)
        assertEquals("https://as.x.com/auth", meta?.authorizationEndpoint)
        assertTrue(meta?.codeChallengeMethodsSupported?.contains("S256") == true)
    }
}
