package com.sabreware.aide.data.connector.directory

import com.sabreware.aide.core.domain.connector.Connector
import com.sabreware.aide.core.domain.connector.ConnectorAuthType
import com.sabreware.aide.core.domain.connector.ConnectorCategory
import com.sabreware.aide.core.domain.connector.ConnectorSource
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryId
import com.sabreware.aide.data.connector.registry.McpRegistryClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.errors.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class OfficialMcpRegistryDirectoryTest {

    private fun con(name: String, domain: String) =
        Connector("id:$name", name, "d", ConnectorCategory.OTHER, "https://$name", ConnectorAuthType.UNKNOWN, domain, null, null, ConnectorSource.REGISTRY)

    private class FakeCache(private val data: Map<ConnectorDirectoryId, List<Connector>> = emptyMap()) : ConnectorDirectoryCache {
        override suspend fun load(id: ConnectorDirectoryId): List<Connector> = data[id] ?: emptyList()
        override suspend fun write(id: ConnectorDirectoryId, connectors: List<Connector>): Boolean = true
        override suspend fun isFresh(id: ConnectorDirectoryId, ttlMillis: Long): Boolean = false
    }

    private fun client(engine: MockEngine) = McpRegistryClient {
        HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    @Test
    fun search_hitsRemoteSearchEndpoint_andMaps() = runTest {
        val body = """{"servers":[{"server":{"name":"a/x","title":"Remote X","remotes":[{"type":"streamable-http","url":"https://x"}]}}]}"""
        var requestedSearch: String? = null
        val dir = OfficialMcpRegistryDirectory(
            client(MockEngine { req ->
                requestedSearch = req.url.parameters["search"]
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }),
            FakeCache(),
            Dispatchers.Unconfined,
        )
        val results = dir.search("x")
        assertEquals(listOf("Remote X"), results.map { it.name })
        assertEquals("x", requestedSearch) // server-side search was used
    }

    @Test
    fun search_fallsBackToLocalCacheFilter_whenRemoteFails() = runTest {
        val cached = listOf(con("Notion", "notion.so"), con("Linear", "linear.app"))
        val dir = OfficialMcpRegistryDirectory(
            client(MockEngine { throw IOException("offline") }),
            FakeCache(mapOf(OfficialMcpRegistryDirectory.ID to cached)),
            Dispatchers.Unconfined,
        )
        // Remote throws → fall back to a name/description filter over the cached slice.
        assertEquals(listOf("Notion"), dir.search("notion").map { it.name })
    }
}
