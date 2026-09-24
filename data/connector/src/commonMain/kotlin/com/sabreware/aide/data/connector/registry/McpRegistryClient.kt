package com.sabreware.aide.data.connector.registry

import com.sabreware.aide.core.domain.connector.Connector
import com.sabreware.aide.core.domain.connector.ConnectorAuthType
import com.sabreware.aide.core.domain.connector.ConnectorSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter

/**
 * Fetches remote (`streamable-http`) connectors from the official MCP Registry, cursor-paginating up to
 * [maxPages]. Stdio/package-only servers are dropped (unrunnable on Android). The [httpClientFactory] is a
 * fresh finite Ktor client per fetch (closed after) — same one-shot pattern as `RemoteAllowlistBootstrap`;
 * tests pass a MockEngine-backed client.
 */
class McpRegistryClient(private val httpClientFactory: () -> HttpClient) {

    suspend fun fetchAllRemote(maxPages: Int = 10, pageSize: Int = 100): List<Connector> {
        val client = httpClientFactory()
        try {
            val out = ArrayList<Connector>()
            var cursor: String? = null
            var pages = 0
            do {
                val page: RegistryPage = client.get(url(pageSize, cursor)).body()
                page.servers.forEach { entry -> RegistryMapper.toConnector(entry.server)?.let(out::add) }
                cursor = page.metadata?.nextCursor
                pages++
            } while (!cursor.isNullOrBlank() && pages < maxPages)
            return out
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * Server-side search via the registry's `?search=` parameter (case-insensitive substring on the server
     * *name* — the registry's search is intentionally simple). One page is plenty for a query. Covers the whole
     * registry, not just the cached slice; callers fall back to a local cache filter when offline.
     */
    suspend fun searchRemote(query: String, limit: Int = 50): List<Connector> {
        val client = httpClientFactory()
        try {
            val page: RegistryPage = client.get("$ENDPOINT?limit=$limit&search=${query.encodeURLParameter()}").body()
            return page.servers.mapNotNull { entry -> RegistryMapper.toConnector(entry.server) }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun url(limit: Int, cursor: String?): String {
        val base = "$ENDPOINT?limit=$limit"
        return if (cursor.isNullOrBlank()) base else "$base&cursor=${cursor.encodeURLParameter()}"
    }

    private companion object {
        const val ENDPOINT = "https://registry.modelcontextprotocol.io/v0/servers"
    }
}

/** Pure mapping registry server doc → [Connector] (or null if no usable streamable-http remote). */
object RegistryMapper {

    fun toConnector(server: RegistryServer?): Connector? {
        if (server == null) return null
        val remoteUrl = server.remotes
            .firstOrNull { it.type == "streamable-http" && !it.url.isNullOrBlank() }
            ?.url ?: return null
        val display = server.title?.takeIf { it.isNotBlank() } ?: prettify(server.name) ?: return null
        return Connector(
            id = server.name?.takeIf { it.isNotBlank() } ?: remoteUrl,
            name = display,
            description = server.description?.trim().orEmpty(),
            category = RegistryCategoryHeuristic.categorize(display, server.description.orEmpty()),
            serverUrl = remoteUrl,
            // Registry doesn't say how a server authenticates — probe with a 401 at connect time.
            authType = ConnectorAuthType.UNKNOWN,
            brandDomain = brandDomain(server.websiteUrl, remoteUrl),
            iconSlug = null,
            popularityRank = null,
            source = ConnectorSource.REGISTRY,
            websiteUrl = server.websiteUrl,
            repositoryUrl = server.repository?.url,
        )
    }

    /** "ac.inference.sh/mcp" / "io.github.foo/weather-bar" → "Weather Bar". */
    private fun prettify(name: String?): String? {
        val tail = name?.substringAfterLast('/')?.substringAfterLast('.')?.takeIf { it.isNotBlank() } ?: return null
        return tail.split('-', '_', ' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
    }

    private fun brandDomain(websiteUrl: String?, serverUrl: String): String? {
        val host = hostOf(websiteUrl) ?: hostOf(serverUrl) ?: return null
        return host.removePrefix("www.").removePrefix("mcp.").removePrefix("api.").removePrefix("server.")
    }

    private fun hostOf(u: String?): String? =
        u?.let { runCatching { Url(it).host }.getOrNull() }?.lowercase()
}
