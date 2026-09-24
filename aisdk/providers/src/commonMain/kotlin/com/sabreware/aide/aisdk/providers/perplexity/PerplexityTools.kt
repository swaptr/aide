package com.sabreware.aide.aisdk.providers.perplexity

import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.JsonSchema
import com.sabreware.aide.aisdk.providers.openai.ResponsesQuirks
import com.sabreware.aide.aisdk.util.ProviderToolFactory
import com.sabreware.aide.aisdk.util.providerExecutedTool
import com.sabreware.aide.aisdk.util.wireToolNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The Agent API's server-side tools, from the tools overview and the API reference (checked
 * 2026-09-02): `web_search`, `fetch_url`, `finance_search`, `people_search`, `sandbox`, `mcp` and
 * `connector`. A `function` tool is the ordinary client-executed kind and needs no factory.
 *
 * **Every one is provider-executed.** Perplexity runs them inside the research loop and reports what
 * they found as output items — `search_results`, `fetch_url_results`, `finance_results`,
 * `people_search_results`, `sandbox_results`, `mcp_call` — so the runtime never dispatches one and could
 * not, holding no crawler. The input schemas are empty because the MODEL decides what to search for;
 * a caller's configuration rides in `args` at declaration time, in the spellings the docs use.
 *
 * A preset already carries its own tools, and "tools merge per tool instead of replacing the whole
 * set", so declaring `webSearch(args)` beside `preset: "low"` overrides only web search's options.
 */
public object PerplexityTools {

    /** Declared first, inside the object: `all` is read while the object is still initializing. */
    private val EmptyObjectSchema: JsonSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
        put("additionalProperties", false)
    }

    /**
     * Live web search with domain, date and location filters.
     *
     * `args` takes `searchContextSize` (`low`/`medium`/`high`), `maxResults` (1–50), `maxTokens`,
     * `maxTokensPerPage`, `filters` (`searchDomainFilter` — up to 20 entries, `-` prefix to exclude —
     * `searchRecencyFilter`, `searchAfterDateFilter`, `searchBeforeDateFilter`,
     * `lastUpdatedAfterFilter`, `lastUpdatedBeforeFilter`) and `userLocation` (`country`, `region`,
     * `city`, `latitude`, `longitude`). The wire's own snake_case spellings are accepted too.
     */
    public val webSearch: ProviderToolFactory = providerExecutedTool(
        id = "$PERPLEXITY_PROVIDER_ID.web_search",
        wireName = "web_search",
        inputSchema = EmptyObjectSchema,
        outputSchema = searchResultsSchema(),
    )

    /** Pull and extract the content of specific URLs. `args` takes `maxUrls` (1–10). */
    public val fetchUrl: ProviderToolFactory = providerExecutedTool(
        id = "$PERPLEXITY_PROVIDER_ID.fetch_url",
        wireName = "fetch_url",
        inputSchema = EmptyObjectSchema,
        outputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("contents") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("url") { put("type", "string") }
                            putJsonObject("title") { put("type", "string") }
                            putJsonObject("snippet") { put("type", "string") }
                        }
                    }
                }
            }
        },
    )

    /** Structured financial and market data. Takes no configuration. */
    public val financeSearch: ProviderToolFactory = providerExecutedTool(
        id = "$PERPLEXITY_PROVIDER_ID.finance_search",
        wireName = "finance_search",
        inputSchema = EmptyObjectSchema,
        outputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("tickers") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                putJsonObject("categories") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                putJsonObject("results") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("category") { put("type", "string") }
                            putJsonObject("content") { put("type", "string") }
                            putJsonObject("tickers") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                            putJsonObject("sources") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                        }
                    }
                }
            }
        },
    )

    /** Find professionals and people. `args` takes `maxTokens` and `maxTokensPerPage`. */
    public val peopleSearch: ProviderToolFactory = providerExecutedTool(
        id = "$PERPLEXITY_PROVIDER_ID.people_search",
        wireName = "people_search",
        inputSchema = EmptyObjectSchema,
        outputSchema = searchResultsSchema(),
    )

    /** Run code in an isolated container; the result names `stdout`, `stderr` and an exit code. */
    public val sandbox: ProviderToolFactory = providerExecutedTool(
        id = "$PERPLEXITY_PROVIDER_ID.sandbox",
        wireName = "sandbox",
        inputSchema = EmptyObjectSchema,
        outputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("status") { put("type", "string") }
                putJsonObject("code") { put("type", "string") }
                putJsonObject("stdout") { put("type", "string") }
                putJsonObject("stderr") { put("type", "string") }
                putJsonObject("exit_code") { put("type", "integer") }
                putJsonObject("duration_ms") { put("type", "integer") }
            }
            putJsonArray("required") { add("status") }
        },
    )

    /**
     * A remote MCP server Perplexity calls on the caller's behalf.
     *
     * `args` REQUIRES `serverLabel` and `serverUrl`, and takes `authorization`, `headers`,
     * `allowedTools` and `deferLoading`. `headers` is never walked into: its keys are HTTP header names.
     */
    public val mcp: ProviderToolFactory = providerExecutedTool(
        id = "$PERPLEXITY_PROVIDER_ID.mcp",
        wireName = "mcp",
        inputSchema = EmptyObjectSchema,
        outputSchema = mcpCallSchema(),
    )

    /**
     * An organization-owned connector, addressed by `id`.
     *
     * `args` REQUIRES `id` and `serverLabel`, and takes `serverDescription` and `allowedTools`.
     */
    public val connector: ProviderToolFactory = providerExecutedTool(
        id = "$PERPLEXITY_PROVIDER_ID.connector",
        wireName = "connector",
        inputSchema = EmptyObjectSchema,
        outputSchema = mcpCallSchema(),
    )

    /** Every Agent API tool, for a caller offering the lot and for [perplexityProviderToolNames]. */
    public val all: List<ProviderToolFactory> =
        listOf(webSearch, fetchUrl, financeSearch, peopleSearch, sandbox, mcp, connector)
}

/** `search_results` and `people_search_results` share one result shape. */
private fun searchResultsSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("queries") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
        putJsonObject("results") {
            put("type", "array")
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") { put("type", "integer") }
                    putJsonObject("url") { put("type", "string") }
                    putJsonObject("title") { put("type", "string") }
                    putJsonObject("snippet") { put("type", "string") }
                    putJsonObject("date") { put("type", "string") }
                    putJsonObject("last_updated") { put("type", "string") }
                    putJsonObject("source") { put("type", "string") }
                }
            }
        }
    }
}

private fun mcpCallSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("name") { put("type", "string") }
        putJsonObject("arguments") { put("type", "string") }
        putJsonObject("output") { put("type", "string") }
        putJsonObject("error") { put("type", "string") }
    }
}

/** Tool id to the `type` the Agent API expects, for `ToolNameMapping` and the request builder. */
internal val perplexityProviderToolNames: Map<String, String> = PerplexityTools.all.wireToolNames()

/**
 * How each tool's declaration-time `args` is spelled on the wire.
 *
 * Per-tool tables rather than a recursive camelCase pass, for the reason the Responses builder gives:
 * an MCP tool's `headers` are HTTP header NAMES used as keys, and a generic rename corrupts every one.
 * Only the documented options are renamed; a key already in the wire's spelling, or one Perplexity
 * ships after this file was written, rides through untouched.
 */
private val PerplexityToolArgumentNames: Map<String, Map<String, String>> = mapOf(
    "$PERPLEXITY_PROVIDER_ID.web_search" to mapOf(
        "searchContextSize" to "search_context_size",
        "maxResults" to "max_results",
        "maxTokens" to "max_tokens",
        "maxTokensPerPage" to "max_tokens_per_page",
        "userLocation" to "user_location",
    ),
    "$PERPLEXITY_PROVIDER_ID.people_search" to mapOf(
        "maxTokens" to "max_tokens",
        "maxTokensPerPage" to "max_tokens_per_page",
    ),
    "$PERPLEXITY_PROVIDER_ID.fetch_url" to mapOf("maxUrls" to "max_urls"),
    "$PERPLEXITY_PROVIDER_ID.mcp" to mapOf(
        "serverLabel" to "server_label",
        "serverUrl" to "server_url",
        "allowedTools" to "allowed_tools",
        "deferLoading" to "defer_loading",
    ),
    "$PERPLEXITY_PROVIDER_ID.connector" to mapOf(
        "serverLabel" to "server_label",
        "serverDescription" to "server_description",
        "allowedTools" to "allowed_tools",
    ),
)

/** The web-search `filters` object's own keys, from the web-search tool page. */
private val WebSearchFilterNames: Map<String, String> = mapOf(
    "searchDomainFilter" to "search_domain_filter",
    "searchRecencyFilter" to "search_recency_filter",
    "searchAfterDateFilter" to "search_after_date_filter",
    "searchBeforeDateFilter" to "search_before_date_filter",
    "lastUpdatedAfterFilter" to "last_updated_after_filter",
    "lastUpdatedBeforeFilter" to "last_updated_before_filter",
)

/** One tool's body: the wire `type`, then the caller's configuration with the documented keys renamed. */
internal fun perplexityToolBody(id: String, wireName: String, args: JsonObject): JsonObject {
    val renames = PerplexityToolArgumentNames[id].orEmpty()
    return buildJsonObject {
        put("type", wireName)
        args.forEach { (key, value) ->
            val wire = renames[key] ?: key
            val nested = value as? JsonObject
            put(
                wire,
                if (wire == "filters" && nested != null && wireName == "web_search") {
                    nested.renamed(WebSearchFilterNames)
                } else {
                    value
                },
            )
        }
    }
}

private fun JsonObject.renamed(table: Map<String, String>): JsonObject =
    buildJsonObject { this@renamed.forEach { (key, value) -> put(table[key] ?: key, value) } }

/**
 * The Agent API's dialect of the Responses wire.
 *
 * Its own tool table, consulted before OpenAI's and never inheriting it — a `openai.local_shell` sent
 * here would be a 400 in place of the warning the caller should get. `failed` is the one terminal
 * `status` the reference schema names beside `completed`, and it is an error, not a stop.
 */
internal val PerplexityResponsesQuirks: ResponsesQuirks = ResponsesQuirks(
    finishReasons = mapOf("failed" to FinishReason.Unified.Error, "error" to FinishReason.Unified.Error),
    providerToolNames = perplexityProviderToolNames,
    providerToolBodies = ::perplexityToolBody,
)
