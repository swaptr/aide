package com.sabreware.aide.data.connector.registry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for the official MCP Registry `GET /v0/servers` response. Parsed leniently (ignoreUnknownKeys),
 * so the many fields we don't use are dropped. See https://registry.modelcontextprotocol.io.
 */
@Serializable
data class RegistryPage(
    val servers: List<RegistryEntry> = emptyList(),
    val metadata: RegistryMeta? = null,
)

@Serializable
data class RegistryMeta(
    @SerialName("next_cursor") val nextCursor: String? = null,
    val count: Int? = null,
)

/** One list item: the server doc plus registry `_meta` (which we ignore). */
@Serializable
data class RegistryEntry(val server: RegistryServer? = null)

@Serializable
data class RegistryServer(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val version: String? = null,
    val remotes: List<RegistryRemote> = emptyList(),
    val repository: RegistryRepo? = null,
    val websiteUrl: String? = null,
)

@Serializable
data class RegistryRemote(val type: String? = null, val url: String? = null)

@Serializable
data class RegistryRepo(val url: String? = null, val source: String? = null)
