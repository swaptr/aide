package com.sabreware.aide.data.connector.registry

import com.sabreware.aide.core.domain.connector.ConnectorAuthType
import com.sabreware.aide.core.domain.connector.ConnectorSource
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

class RegistryMappingTest {

    @Test
    fun mapper_dropsEntriesWithoutStreamableHttpRemote() {
        assertNull(RegistryMapper.toConnector(RegistryServer(name = "io.x/foo", remotes = emptyList())))
        assertNull(RegistryMapper.toConnector(RegistryServer(name = "io.x/bar", remotes = listOf(RegistryRemote("sse", "https://x")))))
    }

    @Test
    fun mapper_mapsFields_andStripsHostPrefixForBrandDomain() {
        val c = RegistryMapper.toConnector(
            RegistryServer(
                name = "com.acme/weather",
                title = "Acme Weather",
                description = "Weather tools.",
                remotes = listOf(RegistryRemote("streamable-http", "https://mcp.acme.com/mcp")),
                websiteUrl = "https://www.acme.com",
            ),
        )!!
        assertEquals("Acme Weather", c.name)
        assertEquals("https://mcp.acme.com/mcp", c.serverUrl)
        assertEquals(ConnectorAuthType.UNKNOWN, c.authType)
        assertEquals(ConnectorSource.REGISTRY, c.source)
        assertEquals("acme.com", c.brandDomain) // www. stripped
    }

    @Test
    fun mapper_prettifiesNameWhenTitleMissing() {
        val c = RegistryMapper.toConnector(
            RegistryServer(name = "io.github.foo/weather-bar", remotes = listOf(RegistryRemote("streamable-http", "https://x.io/mcp"))),
        )!!
        assertEquals("Weather Bar", c.name)
    }

    @Test
    fun client_paginatesAcrossCursors() = runTest {
        val page1 = """{"servers":[{"server":{"name":"a/x","title":"X","remotes":[{"type":"streamable-http","url":"https://x"}]}}],"metadata":{"next_cursor":"C2"}}"""
        val page2 = """{"servers":[{"server":{"name":"b/y","title":"Y","remotes":[{"type":"streamable-http","url":"https://y"}]}}],"metadata":{}}"""
        val client = McpRegistryClient {
            HttpClient(
                MockEngine { req ->
                    val body = if (req.url.parameters["cursor"] == null) page1 else page2
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ) {
                expectSuccess = false
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        }
        assertEquals(listOf("X", "Y"), client.fetchAllRemote().map { it.name })
    }
}
