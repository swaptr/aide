package com.sabreware.aide.core.domain.connector

import kotlinx.serialization.Serializable

/**
 * A browsable MCP connector in the catalog — one remote (`streamable-http`) MCP server a user can
 * discover and Connect. Distinct from [com.sabreware.aide.core.domain.mcp.McpServerConfig], which is the
 * *installed* server; connecting a [Connector] produces an `McpServerConfig`.
 *
 * Built by merging the live MCP Registry with the in-code curated overlay (see ConnectorMerge).
 */
@Serializable
data class Connector(
    /** Stable identity — curated slug or registry name. Used as the icon cache key + dedup key. */
    val id: String,
    val name: String,
    val description: String,
    val category: ConnectorCategory,
    /** The streamable-http MCP endpoint to connect to. */
    val serverUrl: String,
    /** What Connect will do: none / static header / OAuth. UNKNOWN = decide at connect time via a 401 probe. */
    val authType: ConnectorAuthType,
    /** Brand host for icon resolution, e.g. "notion.so" (derived from websiteUrl / remote host). */
    val brandDomain: String? = null,
    /** Simple Icons slug override, e.g. "notion". Falls back to a slug derived from [name]. */
    val iconSlug: String? = null,
    /** Curated popularity rank (1 = most popular); null for registry long-tail entries. */
    val popularityRank: Int? = null,
    val source: ConnectorSource = ConnectorSource.REGISTRY,
    val websiteUrl: String? = null,
    val repositoryUrl: String? = null,
)

/** How a connector authenticates. Mirrors Google AI Edge Gallery's `McpAuth` oneof shape. */
enum class ConnectorAuthType { NONE, HEADER, OAUTH, UNKNOWN }

/** Where a [Connector] came from after the merge. */
enum class ConnectorSource { CURATED, REGISTRY, MERGED }
