package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.providers.options.mergedFor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The call's `providerOptions["anthropic"]`, which had no reader at all.
 *
 * Everything Anthropic charges extra for or gates behind a beta lives here — `cache_control`,
 * `metadata.user_id`, `mcp_servers`, containers and skills, context management, `service_tier` — and none
 * of it was reachable: `CallOptions.providerOptions` was declared, carried through the runtime, and then
 * dropped by every provider. A field a caller can set and no provider can send is worse than an absent
 * one, because the caller believes it took effect.
 *
 * **Unrecognised keys are forwarded, not discarded.** Anthropic's wire is uniformly `snake_case` and this
 * option surface is uniformly `camelCase`, so the translation between them is mechanical — see
 * [snakeCased]. Doing it generically rather than field by field is what makes a parameter Anthropic ships
 * next month reachable on the day it ships, instead of after a release of ours.
 */
internal class AnthropicOptions private constructor(private val raw: JsonObject) {

    /** Replay reasoning blocks back to the model. Off is for a proxy that rejects thinking on input. */
    val sendReasoning: Boolean = raw.boolOrNull("sendReasoning") ?: true

    /** `outputFormat` | `jsonTool` | `auto` — how [com.sabreware.aide.aisdk.ResponseFormat.Json] is served. */
    val structuredOutputMode: String = raw.stringOrNull("structuredOutputMode") ?: "auto"

    /** An explicit `thinking` object. Takes precedence over anything derived from the neutral effort. */
    val thinking: JsonObject? = raw["thinking"] as? JsonObject

    /** An explicit `output_config.effort`. Same precedence rule as [thinking]. */
    val effort: String? = raw.stringOrNull("effort")

    /** At most one tool call per assistant turn. Rides on `tool_choice`, not on its own field. */
    val disableParallelToolUse: Boolean? = raw.boolOrNull("disableParallelToolUse")

    /**
     * Fine-grained streaming of tool-call inputs: each function tool gets `eager_input_streaming: true`
     * unless it opts out via its own `providerOptions.anthropic.eagerInputStreaming`. On by default,
     * matching the reference. There is no top-level `tool_streaming` body field — forwarding this key
     * verbatim was a silently ignored request parameter.
     */
    val toolStreaming: Boolean = raw.boolOrNull("toolStreaming") ?: true

    /** `output_config.task_budget` — advisory total for an agentic turn; not a hard limit. */
    val taskBudget: JsonObject? = raw["taskBudget"] as? JsonObject

    /**
     * Betas the caller asked for outright, plus the ones its other options imply.
     *
     * Implied betas are not a convenience: `mcp_servers` without `mcp-client-2025-04-04` is a 400 that
     * names the header rather than the field, and nothing in the caller's request mentions a header.
     */
    fun betas(): Set<String> = buildSet {
        (raw["anthropicBeta"] as? JsonArray)?.forEach { it.stringOrNull()?.let(::add) }

        if ((raw["mcpServers"] as? JsonArray)?.isNotEmpty() == true) add("mcp-client-2025-04-04")

        (raw["contextManagement"] as? JsonObject)?.let { management ->
            add("context-management-2025-06-27")
            val edits = management["edits"] as? JsonArray
            if (edits?.any { (it as? JsonObject)?.stringOrNull("type") == COMPACT_EDIT } == true) {
                add("compact-2026-01-12")
            }
        }

        val skills = (raw["container"] as? JsonObject)?.get("skills") as? JsonArray
        if (skills?.isNotEmpty() == true) {
            add("code-execution-2025-08-25")
            add("skills-2025-10-02")
            add("files-api-2025-04-14")
        }

        when (val fallbacks = raw["fallbacks"]) {
            is JsonArray -> if (fallbacks.isNotEmpty()) add("server-side-fallback-2026-06-01")
            is JsonPrimitive -> if (fallbacks.stringOrNull() == "default") add("server-side-fallback-2026-07-01")
            else -> Unit
        }
    }

    /**
     * The keys spliced straight into the request body, snake-cased.
     *
     * [CONSUMED] is the set this class turns into something other than a body field of the same name —
     * a `thinking` object the thinking resolver owns, an effort that belongs under `output_config`. Every
     * other key is Anthropic's own, and forwarding it verbatim is the point.
     */
    fun extraBody(): JsonObject = buildJsonObject {
        raw.forEach { (key, value) ->
            if (key !in CONSUMED) put(key.snakeCased(), value.snakeCasedKeys())
        }
    }

    companion object {

        /**
         * Reads `providerOptions["anthropic"]` merged with `providerOptions[namespace]`.
         *
         * [namespace] is the id the HOSTING provider reports — `amazon-bedrock`, `google-vertex`,
         * `minimax` — so a caller files options under the provider it actually constructed and they
         * are read, while options under the canonical `anthropic` key keep working everywhere. See
         * [mergedFor] for the rule and why it is spelled once.
         */
        fun of(options: CallOptions, namespace: String? = null): AnthropicOptions =
            AnthropicOptions(options.providerOptions.mergedFor(ANTHROPIC_PROVIDER_ID, namespace))

        private const val COMPACT_EDIT = "compact_20260112"

        private val CONSUMED = setOf(
            "sendReasoning",
            "structuredOutputMode",
            "thinking",
            "effort",
            "taskBudget",
            "disableParallelToolUse",
            "toolStreaming",
            "anthropicBeta",
        )
    }
}

/**
 * `camelCase` to `snake_case`, applied to every key at every depth.
 *
 * A digit boundary counts: `webSearch20250305` is not a case this surface has, but `maxContentTokens`
 * and `clearAtLeast` are, and getting one of them wrong is a silently ignored field rather than an error.
 */
internal fun JsonElement.snakeCasedKeys(): JsonElement = when (this) {
    is JsonObject -> buildJsonObject { forEach { (key, value) -> put(key.snakeCased(), value.snakeCasedKeys()) } }
    is JsonArray -> JsonArray(map { it.snakeCasedKeys() })
    else -> this
}

internal fun String.snakeCased(): String = buildString {
    this@snakeCased.forEach { char ->
        if (char.isUpperCase()) {
            if (isNotEmpty()) append('_')
            append(char.lowercaseChar())
        } else {
            append(char)
        }
    }
}

internal fun JsonObject.stringOrNull(key: String): String? = this[key]?.stringOrNull()

internal fun JsonObject.boolOrNull(key: String): Boolean? =
    runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

internal fun JsonElement.stringOrNull(): String? =
    runCatching { jsonPrimitive }.getOrNull()?.takeIf { it.isString }?.content
