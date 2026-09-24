package com.sabreware.aide.aisdk.providers.google.interactions

import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.Warning
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The `tools` array, the `tool_choice` that goes with it, and anything that had to be dropped. */
internal data class PreparedInteractionsTools(
    val tools: List<JsonObject>?,
    val toolChoice: JsonElement?,
    val warnings: List<Warning>,
)

/**
 * The Interactions `tools[]` and `tool_choice`.
 *
 * A function tool's `parameters` is plain JSON Schema, passed through untouched — unlike the classic
 * surface, which takes OpenAPI 3.0 and needs the schema rewritten. Google's built-in tools are entries of
 * the same array here rather than sibling keys, one object per tool, discriminated by `type`.
 *
 * `tool_choice` is sent only when a FUNCTION tool is on offer. The API rejects a request that sets it
 * without one (`Function calling config is set without function_declarations`), so a choice that would
 * only govern built-in tools is dropped rather than turned into a 400.
 */
internal fun prepareGoogleInteractionsTools(
    tools: List<Tool>?,
    toolChoice: ToolChoice?,
): PreparedInteractionsTools {
    if (tools.isNullOrEmpty()) return PreparedInteractionsTools(null, null, emptyList())

    val warnings = mutableListOf<Warning>()
    val wire = tools.mapNotNull { tool ->
        when (tool) {
            is Tool.Function -> buildJsonObject {
                put("type", "function")
                put("name", tool.name)
                put("description", tool.description ?: "")
                put("parameters", tool.inputSchema)
            }
            is Tool.ProviderDefined -> tool.toBuiltIn(warnings)
        }
    }

    val hasFunctionTool = wire.any { it["type"] == JsonPrimitive("function") }
    val choice = toolChoice?.takeIf { hasFunctionTool }?.let { choice ->
        when (choice) {
            ToolChoice.Auto -> JsonPrimitive("auto")
            ToolChoice.Required -> JsonPrimitive("any")
            ToolChoice.None -> JsonPrimitive("none")
            // `AllowedTools.tools` is a list of function NAMES, not tool descriptors.
            is ToolChoice.Specific -> buildJsonObject {
                put(
                    "allowed_tools",
                    buildJsonObject {
                        put("mode", "validated")
                        put("tools", JsonArray(listOf(JsonPrimitive(choice.toolName))))
                    },
                )
            }
        }
    }

    return PreparedInteractionsTools(
        tools = wire.takeIf { it.isNotEmpty() },
        toolChoice = choice,
        warnings = warnings,
    )
}

/**
 * A built-in tool, or a warning naming the id this surface does not serve.
 *
 * The id is the specification's, not the wire key: a caller writes `google.google_search` and the API
 * reads `{"type": "google_search"}`. Arguments are re-spelled from the caller's camelCase to the wire's
 * snake_case per tool, because `computer_use` keeps `excludedPredefinedFunctions` camelCase on the wire
 * and a blanket rename would break it.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun Tool.ProviderDefined.toBuiltIn(warnings: MutableList<Warning>): JsonObject? = when (id) {
    "google.google_search" -> buildJsonObject {
        put("type", "google_search")
        // `searchTypes` is an object whose PRESENT keys select the search kinds — matching the shape the
        // classic surface's `googleSearch` tool takes — and the wire wants a list.
        (args["searchTypes"] as? JsonObject)?.let { types ->
            val list = buildList {
                if ("webSearch" in types) add("web_search")
                if ("imageSearch" in types) add("image_search")
            }
            if (list.isNotEmpty()) put("search_types", JsonArray(list.map(::JsonPrimitive)))
        }
    }

    "google.code_execution" -> buildJsonObject { put("type", "code_execution") }

    "google.url_context" -> buildJsonObject { put("type", "url_context") }

    "google.file_search" -> buildJsonObject {
        put("type", "file_search")
        args["fileSearchStoreNames"]?.let { put("file_search_store_names", it) }
        args["topK"]?.let { put("top_k", it) }
        args["metadataFilter"]?.let { put("metadata_filter", it) }
    }

    "google.google_maps" -> buildJsonObject {
        put("type", "google_maps")
        args["latitude"]?.let { put("latitude", it) }
        args["longitude"]?.let { put("longitude", it) }
        args["enableWidget"]?.let { put("enable_widget", it) }
    }

    "google.computer_use" -> buildJsonObject {
        put("type", "computer_use")
        put("environment", args["environment"] ?: JsonPrimitive("browser"))
        args["excludedPredefinedFunctions"]?.let { put("excludedPredefinedFunctions", it) }
    }

    "google.mcp_server" -> buildJsonObject {
        put("type", "mcp_server")
        args["name"]?.let { put("name", it) }
        args["url"]?.let { put("url", it) }
        args["headers"]?.let { put("headers", it) }
        args["allowedTools"]?.let { put("allowed_tools", it) }
    }

    "google.retrieval" -> buildJsonObject {
        put("type", "retrieval")
        put("retrieval_types", args["retrievalTypes"] ?: JsonArray(listOf(JsonPrimitive("vertex_ai_search"))))
        args["vertexAiSearchConfig"]?.let { put("vertex_ai_search_config", it) }
    }

    else -> {
        warnings += Warning.Unsupported(
            feature = "provider-defined tool $id",
            details = "provider-defined tool $id is not supported by google.interactions; tool dropped.",
        )
        null
    }
}
