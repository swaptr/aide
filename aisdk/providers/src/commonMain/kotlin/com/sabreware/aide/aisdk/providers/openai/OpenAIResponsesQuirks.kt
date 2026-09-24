package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ProviderOptions
import com.sabreware.aide.aisdk.providers.options.mergedFor
import com.sabreware.aide.aisdk.providers.xai.xaiProviderToolNames
import com.sabreware.aide.aisdk.providers.xai.xaiToolBody
import kotlinx.serialization.json.JsonObject

// The Responses wire is no longer OpenAI's alone: xAI, Azure, Hugging Face's router and any
// `open-responses` server speak it, each with a small dialect. The model, request builder, prompt
// converter and stream mapper are therefore parameterized by (provider id, options namespace, endpoint)
// plus the quirk set below, rather than cloned per vendor — the same table-over-classes choice
// `Vendors.kt` makes for Chat Completions.

/**
 * How one vendor's Responses endpoint departs from OpenAI's own.
 *
 * Every knob here is wire-observable: a finish string OpenAI never sends, a usage block counted
 * differently, a tool surface the vendor rejects. Anything deeper than that — xAI's citations and
 * server-side search, Hugging Face's router headers — is a separate feature, not a quirk, and does not
 * belong in this table.
 */
internal data class ResponsesQuirks(
    /**
     * Vendor finish strings, consulted before the OpenAI mapping.
     *
     * OpenAI's Responses API reports nothing on a normal finish and spells truncation
     * `max_output_tokens`; xAI answers `completed` and spells truncation `length`, and Hugging Face's
     * router forwards whatever its downstream host said. An unmapped string falls through to the OpenAI
     * rules, so the raw value still reaches [FinishReason.raw] either way.
     */
    val finishReasons: Map<String, FinishReason.Unified> = emptyMap(),
    /**
     * xAI's usage rule: `input_tokens` may EXCLUDE the cached tokens, where OpenAI's includes them.
     *
     * The tell is arithmetic — a cached count larger than the input count — and without this correction
     * the no-cache share goes negative on exactly the calls where caching worked best.
     */
    val usageMayExcludeCachedTokens: Boolean = false,
    /**
     * The vendor's own provider-defined tools, consulted BEFORE OpenAI's table.
     *
     * Maps a tool id to the `type` the vendor's Responses endpoint expects. Without it, a vendor tool
     * misses OpenAI's table and is dropped with "not a Responses API tool" — a declared tool that never
     * reaches the wire, which is an affordance drawn over nothing.
     *
     * A vendor's tool BODY is built by [providerToolBodies], because the argument spellings differ per
     * tool and belong beside the tools they describe, not inside this shared request builder.
     */
    val providerToolNames: Map<String, String> = emptyMap(),
    /**
     * Builds one vendor tool's request body from `(id, wireName, args)`.
     *
     * Null falls back to OpenAI's own body shape, which is right for a vendor that merely renames the
     * type. xAI needs its own because several of its tools rename arguments per tool — and a generic
     * camelCase-to-snake pass would corrupt an MCP tool's `headers`, whose keys are arbitrary header
     * names rather than option names.
     */
    val providerToolBodies: ((String, String, JsonObject) -> JsonObject)? = null,
    /**
     * Hugging Face's tool surface: plain function tools only.
     *
     * Provider-defined tools (web search, code interpreter…) are refused with a warning rather than
     * forwarded, `tool_choice: "none"` has no wire form and is dropped with a warning, and a NAMED tool
     * choice nests as `{"type":"function","function":{"name":…}}` where OpenAI flattens the name to the
     * top level. Sending OpenAI's flat shape reads as an unknown field and the pin silently never
     * engages.
     */
    val functionToolsOnly: Boolean = false,
    /**
     * Azure AI Foundry's project endpoints reject a message item without an explicit
     * `type: "message"` discriminator, which OpenAI's own endpoint treats as optional. Sent on every
     * system, developer, user and assistant message item when set; an `item_reference` is unaffected.
     */
    val explicitMessageItemType: Boolean = false,
    /**
     * Whether the endpoint accepts `include: ["web_search_call.action.sources"]`. OpenAI does; a
     * compatible host that serves the web-search tool without that include value 400s on it, so the
     * request builder leaves the include out rather than the tool. A caller can also opt out per call
     * with `includeWebSearchSources: false`.
     */
    val supportsWebSearchSourcesInclude: Boolean = true,
    /**
     * The `open-responses` spec's strict input schemas for assistant history: a text part with no item
     * id goes out as an "easy" message whose `content` is the plain string, and one WITH an id goes out
     * as the complete output item it was — `status: "completed"`, `annotations` and `logprobs` present
     * even when empty. A server validating against the spec's schemas rejects OpenAI's shape, where an
     * assistant message carries an `output_text` array and an optional id.
     */
    val strictResponseInput: Boolean = false,
    /**
     * `detail` on an `input_image` whose part named none. OpenAI leaves the field out and lets the
     * endpoint default it; the `open-responses` reference always writes `auto`, and a server built
     * against that spec's schema may require the field.
     */
    val defaultImageDetail: String? = null,
)

/** An `open-responses` server's dialect — see [OpenResponsesProvider] for the knob it exposes. */
internal fun openResponsesQuirks(strictResponseInput: Boolean): ResponsesQuirks = ResponsesQuirks(
    strictResponseInput = strictResponseInput,
    defaultImageDetail = "auto",
)

/** xAI's `/v1/responses` dialect — see each [ResponsesQuirks] knob for the wire evidence. */
internal val XaiResponsesQuirks: ResponsesQuirks = ResponsesQuirks(
    finishReasons = mapOf(
        "stop" to FinishReason.Unified.Stop,
        "completed" to FinishReason.Unified.Stop,
        "length" to FinishReason.Unified.Length,
        "tool_calls" to FinishReason.Unified.ToolCalls,
        "function_call" to FinishReason.Unified.ToolCalls,
        "content_filter" to FinishReason.Unified.ContentFilter,
    ),
    usageMayExcludeCachedTokens = true,
    providerToolNames = xaiProviderToolNames,
    providerToolBodies = ::xaiToolBody,
)

/** The Hugging Face router's Responses dialect: plain finish strings, function tools only. */
internal val HuggingFaceResponsesQuirks: ResponsesQuirks = ResponsesQuirks(
    finishReasons = mapOf(
        "stop" to FinishReason.Unified.Stop,
        "length" to FinishReason.Unified.Length,
        "tool_calls" to FinishReason.Unified.ToolCalls,
        "content_filter" to FinishReason.Unified.ContentFilter,
        "error" to FinishReason.Unified.Error,
    ),
    functionToolsOnly = true,
)

/**
 * The call-level options for a Responses request, resolved across namespaces.
 *
 * A caller moving a working prompt from OpenAI to an Azure deployment keeps every `openai`-filed
 * option and overrides exactly the fields it files under `azure`. See [mergedFor] for the rule.
 */
internal fun mergedResponsesOptions(options: ProviderOptions?, namespace: String): JsonObject =
    options.mergedFor(OPENAI_PROVIDER_ID, namespace)

/**
 * A single string option under [namespace], falling back to the canonical `openai` key.
 *
 * The part-level counterpart of [mergedResponsesOptions]: replayed content carries its item ids and
 * encrypted payloads under whichever namespace the serving provider filed them, and a prompt is allowed
 * to mix turns from both.
 */
internal fun ProviderOptions?.responsesString(namespace: String, key: String): String? =
    this?.get(namespace).stringOrNull(key)
        ?: (if (namespace == OPENAI_PROVIDER_ID) null else this?.get(OPENAI_PROVIDER_ID).stringOrNull(key))
