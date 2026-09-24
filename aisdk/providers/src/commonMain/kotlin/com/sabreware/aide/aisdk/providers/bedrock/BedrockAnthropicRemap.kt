package com.sabreware.aide.aisdk.providers.bedrock

import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bedrock's departures from the direct Anthropic API, applied to a finished request body.
 *
 * Bedrock requires NEWER versions of some tools than the direct API's defaults, renames one of them,
 * spells the betas they need differently, and expresses per-tool eager input streaming as a beta
 * rather than a field. Sending the un-remapped request is not degraded behaviour — it is a rejection,
 * so the maps below are ported from the reference verbatim rather than re-derived.
 */
internal object BedrockAnthropicRemap {

    /** Tool `type` upgrades Bedrock requires. */
    private val VERSION_MAP = mapOf(
        "bash_20241022" to "bash_20250124",
        "text_editor_20241022" to "text_editor_20250728",
        "computer_20241022" to "computer_20250124",
    )

    /** The one upgraded type whose NAME also changes. */
    private val NAME_MAP = mapOf(
        "text_editor_20250728" to "str_replace_based_edit_tool",
    )

    /** Tool `type` → the `anthropic_beta` value Bedrock wants for it. */
    private val BETA_MAP = mapOf(
        "bash_20250124" to "computer-use-2025-01-24",
        "bash_20241022" to "computer-use-2024-10-22",
        "text_editor_20250124" to "computer-use-2025-01-24",
        "text_editor_20241022" to "computer-use-2024-10-22",
        "text_editor_20250429" to "computer-use-2025-01-24",
        "text_editor_20250728" to "computer-use-2025-01-24",
        "computer_20250124" to "computer-use-2025-01-24",
        "computer_20241022" to "computer-use-2024-10-22",
        "tool_search_tool_regex_20251119" to "tool-search-tool-2025-10-19",
        // BM25 is not currently supported on Bedrock; the beta is included so Bedrock returns a
        // useful error rather than an unknown-tool one.
        "tool_search_tool_bm25_20251119" to "tool-search-tool-2025-10-19",
    )

    private const val EAGER_STREAMING_BETA = "fine-grained-tool-streaming-2025-05-14"

    /**
     * Rewrites the `tools` array in place of the direct-API one, adding any betas the rewrite implies.
     *
     * Per tool, in order: `eager_input_streaming` is stripped into its beta (Bedrock has no per-tool
     * field for it); a version in [VERSION_MAP] is upgraded, taking its beta and possibly its new
     * name; otherwise a known type still contributes its beta, and a name-only remap applies.
     */
    fun rewriteTools(tools: JsonArray, betas: MutableSet<String>): JsonArray = JsonArray(
        tools.map { element ->
            val tool = element as? JsonObject ?: return@map element
            if (tool["eager_input_streaming"]?.boolOrNull() == true) betas += EAGER_STREAMING_BETA
            val stripped = JsonObject(tool - "eager_input_streaming")
            val type = stripped["type"]?.stringOrNull() ?: return@map stripped
            val upgraded = VERSION_MAP[type]
            when {
                upgraded != null -> {
                    BETA_MAP[upgraded]?.let(betas::add)
                    buildJsonObject {
                        stripped.forEach { (key, value) ->
                            when (key) {
                                "type" -> put(key, JsonPrimitive(upgraded))
                                "name" -> put(key, JsonPrimitive(NAME_MAP[upgraded] ?: value.stringOrNull() ?: ""))
                                else -> put(key, value)
                            }
                        }
                    }
                }
                type in BETA_MAP -> {
                    BETA_MAP.getValue(type).let(betas::add)
                    stripped
                }
                type in NAME_MAP -> buildJsonObject {
                    stripped.forEach { (key, value) ->
                        if (key == "name") put(key, JsonPrimitive(NAME_MAP.getValue(type))) else put(key, value)
                    }
                }
                else -> stripped
            }
        },
    )

    /**
     * The two body fields Bedrock spells differently from the direct API.
     *
     * `tool_choice` accepts only `type` and `name` — `disable_parallel_tool_use` is rejected, so it
     * is dropped here rather than surfaced as a 400 the caller cannot map to an option. And the
     * thinking binding control is `block_binding.mismatch_behavior` where the Messages API says
     * `prefix_mismatch_behavior`: some regions (us-east-1) alias the Anthropic spelling, others
     * (eu-central-1) reject it, so the rename is unconditional.
     */
    fun rewriteBody(body: JsonObject): JsonObject {
        var rewritten = body
        (body["tool_choice"] as? JsonObject)?.let { choice ->
            val trimmed = buildJsonObject {
                choice["type"]?.let { put("type", it) }
                choice["name"]?.let { put("name", it) }
            }
            rewritten = JsonObject(rewritten + ("tool_choice" to trimmed))
        }
        (body["thinking"] as? JsonObject)?.let { thinking ->
            val behaviour = (thinking["block_binding"] as? JsonObject)?.get("prefix_mismatch_behavior")
                ?: return@let
            val binding = buildJsonObject { put("mismatch_behavior", behaviour) }
            rewritten = JsonObject(rewritten + ("thinking" to JsonObject(thinking + ("block_binding" to binding))))
        }
        return rewritten
    }
}

/**
 * Bedrock's error bodies are `{"message": "..."}` (ValidationException et al.); the default structure
 * already reads that shape, and Bedrock's throttling is a retryable condition it names by type.
 */
internal val BedrockErrors: ProviderErrorStructure = ProviderErrorStructure(
    isRetryable = { statusCode, _ -> if (statusCode == THROTTLING_STATUS) true else null },
)

private const val THROTTLING_STATUS = 429

private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
    runCatching { jsonPrimitive }.getOrNull()?.takeIf { it.isString }?.content

private fun kotlinx.serialization.json.JsonElement.boolOrNull(): Boolean? =
    runCatching { jsonPrimitive }.getOrNull()?.content?.toBooleanStrictOrNull()
