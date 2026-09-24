package com.sabreware.aide.data.connector.oauth

import com.sabreware.aide.core.domain.connector.oauth.AuthorizationServerMetadata
import com.sabreware.aide.core.domain.connector.oauth.OAuthClientStore
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
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class ClientRegistrarTest {

    private class FakeClientStore : OAuthClientStore {
        private val ids = mutableMapOf<String, String>()
        override suspend fun clientId(key: String): String? = ids[key]
        override suspend fun putClientId(key: String, clientId: String) { ids[key] = clientId }
    }

    private fun http(register: String?): HttpClient =
        HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/register" && register != null) {
                    respond(register, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    respond("", HttpStatusCode.NotFound)
                }
            },
        ) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    private val asMeta = AuthorizationServerMetadata(
        issuer = "https://as.x.com",
        authorizationEndpoint = "https://as.x.com/auth",
        tokenEndpoint = "https://as.x.com/token",
        registrationEndpoint = "https://as.x.com/register",
    )

    @Test
    fun dcr_returnsClientId_andCachesByIssuerAndRedirect() = runTest {
        val store = FakeClientStore()
        val id = ClientRegistrarImpl(http("""{"client_id":"abc123"}"""), store).clientId(asMeta, "http://127.0.0.1:9/cb")
        assertEquals("abc123", id)
        // Cached under issuer|redirect so a different redirect (port/scheme) re-registers.
        assertEquals("abc123", store.clientId("https://as.x.com|http://127.0.0.1:9/cb"))
        assertNull(store.clientId("https://as.x.com|com.sabreware.aide://oauth-callback"))
    }

    @Test
    fun noRegistrationEndpoint_returnsNull() = runTest {
        val meta = asMeta.copy(registrationEndpoint = null)
        assertNull(ClientRegistrarImpl(http(null), FakeClientStore()).clientId(meta, "http://127.0.0.1:9/cb"))
    }
}
