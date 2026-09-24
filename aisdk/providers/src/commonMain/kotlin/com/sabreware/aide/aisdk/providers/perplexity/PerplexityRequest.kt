package com.sabreware.aide.aisdk.providers.perplexity

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ProviderOptions
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.openai.PreparedTools
import com.sabreware.aide.aisdk.providers.openai.buildResponsesRequest
import com.sabreware.aide.aisdk.providers.openai.mergedResponsesOptions
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Metadata key for the `thought_signature` a `function_call` item carries on this wire. */
internal const val PERPLEXITY_THOUGHT_SIGNATURE_KEY: String = "thoughtSignature"

/** A request body, the tools the response mapper needs to read it, and what could not be honoured. */
internal data class PerplexityRequest(
    val body: JsonObject,
    val warnings: List<Warning>,
    val tools: PreparedTools,
)

/**
 * Builds the Agent API body on top of the shared Responses builder.
 *
 * The API reference (checked 2026-09-02) documents the request as OpenAI's Responses shape with a
 * handful of departures, and each is applied to the built body rather than to a copy of the builder:
 *
 * - **`preset` in place of `model`** when the id names one — "required if model is not provided".
 * - **`response_format`, not `text.format`**, for structured output; `text` is not a request field.
 * - **`reasoning.effort` takes `minimal|low|medium|high|xhigh|max`** with no `summary`, so the neutral
 *   effort is written directly instead of resolved against OpenAI's per-family ladders — the shared
 *   builder is told the model does not reason, which also keeps `developer` roles and `include` lists
 *   off a wire that documents neither.
 * - **`frequency_penalty` and `presence_penalty` exist here** (the quickstart lists both), where the
 *   Responses builder warns that they do not.
 * - **Input items carry `type: "message"`**, the documented shape, and a replayed `function_call`
 *   carries back its `thought_signature` — the Gemini signature this library exists to keep.
 *
 * What the reference has no wire form for is stripped from the prompt BEFORE the builder sees it, in
 * [forPerplexityReplay], so the builder never has to warn about an item it would otherwise reference.
 */
internal suspend fun buildPerplexityRequest(modelId: String, options: CallOptions, stream: Boolean): PerplexityRequest {
    val warnings = mutableListOf<Warning>()
    val replay = options.prompt.forPerplexityReplay()
    val built = buildResponsesRequest(
        modelId = modelId,
        options = options.copy(
            prompt = replay.prompt,
            providerOptions = options.providerOptions.asNonReasoningModel(),
            // Re-added below in Perplexity's own vocabulary.
            presencePenalty = null,
            frequencyPenalty = null,
            reasoning = ReasoningEffort.ProviderDefault,
        ),
        stream = stream,
        namespace = PERPLEXITY_PROVIDER_ID,
        quirks = PerplexityResponsesQuirks,
    )
    warnings += built.warnings

    val merged = mergedResponsesOptions(options.providerOptions, PERPLEXITY_PROVIDER_ID)
    val preset = PerplexityPresets.isPreset(modelId)
    val body = buildJsonObject {
        built.body.forEach { (key, value) ->
            when (key) {
                "model" -> put(if (preset) "preset" else "model", value)
                "input" -> put("input", (value as? JsonArray)?.forPerplexity(replay.signatures) ?: value)
                // `include` selects OpenAI-only payloads (encrypted reasoning, search sources) that this
                // API has no field for.
                "include" -> Unit
                "text" -> (value as? JsonObject)?.toResponseFormat(warnings)?.let { put("response_format", it) }
                else -> put(key, value)
            }
        }
        options.frequencyPenalty?.let { put("frequency_penalty", it) }
        options.presencePenalty?.let { put("presence_penalty", it) }
        // A verbatim `reasoning` object from the caller's options is already on the body and wins.
        if ("reasoning" !in merged) {
            perplexityEffort(options.reasoning, warnings)?.let { putJsonObject("reasoning") { put("effort", it) } }
        }
    }
    return PerplexityRequest(body, warnings, built.tools)
}

/**
 * The prompt as the Agent API can replay it, plus the signatures its function calls must carry back.
 *
 * The API reference documents three input item kinds — a message, a `function_call` and a
 * `function_call_output` — and nothing else. Everything a previous turn produced that has no item is
 * dropped here rather than in the shared builder, which would otherwise emit an `item_reference` for
 * it (a Text part keyed by item id, a vendor-executed call, a vendor tool result) or warn about it (a
 * reasoning part with nothing replayable, one of this package's own `Content.Custom` items). Those
 * are Perplexity's own records of a research step; it does not take them back.
 */
internal fun Prompt.forPerplexityReplay(): PerplexityReplay {
    val signatures = mutableMapOf<String, String>()
    val prompt = map { message ->
        if (message !is ModelMessage.Assistant) return@map message
        val kept = message.content.mapNotNull { part ->
            when (part) {
                is AssistantPart.Text -> AssistantPart.Text(part.text)
                is AssistantPart.ToolCall -> part.takeUnless { it.providerExecuted }?.also { call ->
                    call.providerOptions?.get(PERPLEXITY_PROVIDER_ID)
                        ?.optString(PERPLEXITY_THOUGHT_SIGNATURE_KEY)
                        ?.let { signatures[call.toolCallId] = it }
                }
                is AssistantPart.Custom -> part.takeUnless { it.kind.startsWith("$PERPLEXITY_PROVIDER_ID.") }
                is AssistantPart.Reasoning, is AssistantPart.ToolResult -> null
                else -> part
            }
        }
        ModelMessage.Assistant(kept, message.providerOptions)
    }
    return PerplexityReplay(prompt, signatures)
}

internal data class PerplexityReplay(val prompt: Prompt, val signatures: Map<String, String>)

/**
 * Tells the shared builder this is not a reasoning model, under the key it reads for exactly that.
 *
 * Filed under the vendor namespace, which wins over a canonical `openai` entry field by field, so a
 * caller cannot turn OpenAI's reasoning rules back on for a wire that documents none of them.
 */
private fun ProviderOptions?.asNonReasoningModel(): ProviderOptions {
    val own = this?.get(PERPLEXITY_PROVIDER_ID).orEmpty()
    return this.orEmpty() + (PERPLEXITY_PROVIDER_ID to JsonObject(own + ("forceReasoning" to JsonPrimitive(false))))
}

/** The neutral effort in the Agent API's own vocabulary, which has every level but `none`. */
private fun perplexityEffort(effort: ReasoningEffort, warnings: MutableList<Warning>): String? = when (effort) {
    ReasoningEffort.ProviderDefault -> null
    ReasoningEffort.None -> {
        warnings += Warning.Unsupported(
            feature = "reasoningEffort",
            details = "The Agent API's reasoning.effort has no 'none'; the request was sent without one.",
        )
        null
    }
    ReasoningEffort.Minimal -> "minimal"
    ReasoningEffort.Low -> "low"
    ReasoningEffort.Medium -> "medium"
    ReasoningEffort.High -> "high"
    ReasoningEffort.XHigh -> "xhigh"
}

/**
 * Structured output in the shape the API reference documents:
 * `response_format: {type: "json_schema", json_schema: {name, schema, description?, strict?}}`.
 *
 * The Responses builder's `text.format` is the same fields one level shallower under a key this API
 * does not have. A schema-less `json_object` is not documented and is dropped with a warning rather
 * than sent as a shape the caller would have to debug from a 400; plain text is the default and needs
 * no field at all.
 */
private fun JsonObject.toResponseFormat(warnings: MutableList<Warning>): JsonObject? {
    if ("verbosity" in this) {
        warnings += Warning.Unsupported("textVerbosity", "The Agent API has no text.verbosity field.")
    }
    val format = optObject("format") ?: return null
    return when (format.optString("type")) {
        "json_schema" -> buildJsonObject {
            put("type", "json_schema")
            putJsonObject("json_schema") {
                format["name"]?.let { put("name", it) }
                format["description"]?.let { put("description", it) }
                format["strict"]?.let { put("strict", it) }
                format["schema"]?.let { put("schema", it) }
            }
        }

        "json_object" -> {
            warnings += Warning.Unsupported(
                feature = "responseFormat",
                details = "The Agent API documents json_schema only; a schema-less JSON request was sent as text.",
            )
            null
        }

        else -> null
    }
}

/**
 * Input items in the documented shape: every message carries `type: "message"`, and a replayed
 * `function_call` carries the `thought_signature` the model issued with it.
 */
private fun JsonArray.forPerplexity(signatures: Map<String, String>): JsonArray = JsonArray(
    map { element ->
        val item = element as? JsonObject ?: return@map element
        when {
            "role" in item && "type" !in item ->
                JsonObject(mapOf("type" to JsonPrimitive("message")) + item)

            item.optString("type") == "function_call" -> signatures[item.optString("call_id")]
                ?.let { JsonObject(item + ("thought_signature" to JsonPrimitive(it))) }
                ?: item

            else -> item
        }
    },
)
