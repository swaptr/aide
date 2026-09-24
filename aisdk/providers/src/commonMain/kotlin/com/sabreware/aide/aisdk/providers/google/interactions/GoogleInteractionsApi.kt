package com.sabreware.aide.aisdk.providers.google.interactions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Gemini Interactions API wire types (POST /interactions, GET /interactions/{id}).
//
// A different surface from `generateContent`: the conversation is a list of STEPS rather than
// role-tagged contents, the model's turn comes back as steps too (`thought`, `model_output`,
// `function_call`, the built-in `*_call` / `*_result` pairs), and the server can hold the conversation
// for the client (`store`, `previous_interaction_id`). Field names are snake_case, unlike the classic
// surface's camelCase — a step copied from one to the other is rejected, so nothing here is shared with
// `GoogleApi.kt`.
//
// The step, content-block and delta unions each have arms whose fields overlap, and the reference keeps
// every arm `loose()`. One flat class per union, discriminated by `type`, is the honest shape: a strict
// sealed union would reject a real wire event the moment Google adds a field to one arm, and a `thought`
// step arrives as `{"type":"thought"}` with nothing else at all.
//
// Request models declare no Kotlin defaults for required fields: ProviderJson sets encodeDefaults = false.

@Serializable
internal data class InteractionsRequest(
    val model: String? = null,
    val agent: String? = null,
    val input: List<InteractionsStep>,
    @SerialName("system_instruction") val systemInstruction: String? = null,
    /** The tool union, built as JSON: every arm has its own key set and one is camelCase on the wire. */
    val tools: List<JsonObject>? = null,
    @SerialName("response_format") val responseFormat: List<JsonObject>? = null,
    @SerialName("response_modalities") val responseModalities: List<String>? = null,
    @SerialName("generation_config") val generationConfig: InteractionsGenerationConfig? = null,
    @SerialName("agent_config") val agentConfig: JsonObject? = null,
    @SerialName("previous_interaction_id") val previousInteractionId: String? = null,
    @SerialName("service_tier") val serviceTier: String? = null,
    val store: Boolean? = null,
    val stream: Boolean? = null,
    /** `"remote"`, an environment id, or the object form — see [GoogleInteractionsOptions.environment]. */
    val environment: JsonElement? = null,
    val background: Boolean? = null,
)

@Serializable
internal data class InteractionsGenerationConfig(
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("top_k") val topK: Int? = null,
    val seed: Int? = null,
    @SerialName("stop_sequences") val stopSequences: List<String>? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    @SerialName("thinking_level") val thinkingLevel: String? = null,
    @SerialName("thinking_summaries") val thinkingSummaries: String? = null,
    /** `auto` | `any` | `none` | `validated`, or `{"allowed_tools": {...}}`. */
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
)

/**
 * One step: an element of the request's `input`, of the response's `steps`, and of a `step.start` event.
 *
 * `user_input` and `model_output` carry [content]; `thought` carries [signature] and [summary];
 * `function_call` carries [id], [name], [arguments] and [signature]; the built-in `*_call` steps carry
 * [id] and [arguments], the `*_result` steps [callId], [result] and [isError].
 */
@Serializable
internal data class InteractionsStep(
    val type: String,
    val content: List<InteractionsContent>? = null,
    val id: String? = null,
    val name: String? = null,
    val arguments: JsonObject? = null,
    val signature: String? = null,
    val summary: List<InteractionsContent>? = null,
    @SerialName("call_id") val callId: String? = null,
    val result: JsonElement? = null,
    @SerialName("is_error") val isError: Boolean? = null,
    @SerialName("server_name") val serverName: String? = null,
    @SerialName("search_type") val searchType: String? = null,
)

/**
 * A content block inside a step, and a thought-summary item.
 *
 * `text` carries [text] and [annotations]; `image` / `audio` / `video` / `document` carry [data] (base64)
 * or [uri] with [mimeType]; `function_result` — which is a content block on a `user_input` step, never a
 * step of its own — carries [callId], [name], [result] and [isError].
 */
@Serializable
internal data class InteractionsContent(
    val type: String,
    val text: String? = null,
    val annotations: List<InteractionsAnnotation>? = null,
    val data: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    val uri: String? = null,
    val resolution: String? = null,
    /** A video block's `agentic` / `static` / `{type: static, start_offset, end_offset, fps}`. */
    val processing: JsonElement? = null,
    @SerialName("call_id") val callId: String? = null,
    val name: String? = null,
    /** A string, an array of text/image blocks, or whatever the vendor sends — hence untyped. */
    val result: JsonElement? = null,
    @SerialName("is_error") val isError: Boolean? = null,
    val signature: String? = null,
)

/** A citation on a text block: `url_citation`, `file_citation` or `place_citation`. */
@Serializable
internal data class InteractionsAnnotation(
    val type: String,
    val url: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("document_uri") val documentUri: String? = null,
    @SerialName("start_index") val startIndex: Int? = null,
    @SerialName("end_index") val endIndex: Int? = null,
)

// ---- Response -------------------------------------------------------------------------------------

/**
 * `POST /interactions` and `GET /interactions/{id}`.
 *
 * [id] is absent when the call ran with `store: false`: there is no server-side record for the client to
 * name. [usage] is kept as the raw object so `Usage.raw` carries every counter Google sends, including
 * the ones this layer does not read; [InteractionsUsage] is decoded from it for the ones it does.
 */
@Serializable
internal data class InteractionsResponse(
    val id: String? = null,
    val created: String? = null,
    val updated: String? = null,
    val status: String,
    val model: String? = null,
    val agent: String? = null,
    val steps: List<InteractionsStep>? = null,
    val usage: JsonObject? = null,
    @SerialName("service_tier") val serviceTier: String? = null,
    @SerialName("previous_interaction_id") val previousInteractionId: String? = null,
    @SerialName("response_modalities") val responseModalities: List<String>? = null,
)

@Serializable
internal data class InteractionsUsage(
    @SerialName("total_input_tokens") val totalInputTokens: Int? = null,
    @SerialName("total_output_tokens") val totalOutputTokens: Int? = null,
    @SerialName("total_thought_tokens") val totalThoughtTokens: Int? = null,
    @SerialName("total_cached_tokens") val totalCachedTokens: Int? = null,
    @SerialName("total_tool_use_tokens") val totalToolUseTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("output_tokens_by_modality") val outputTokensByModality: List<InteractionsModalityTokens>? = null,
)

@Serializable
internal data class InteractionsModalityTokens(
    val modality: String? = null,
    val tokens: Int? = null,
)

// ---- Server-sent events -----------------------------------------------------------------------------

/**
 * One SSE payload of `POST /interactions` with `stream: true` or `GET /interactions/{id}?stream=true`.
 *
 * Routed on [eventType]. `interaction.created` and `interaction.completed` carry [interaction]; the
 * status transitions carry [interactionId] and [status]; `step.start` carries [index] and [step],
 * `step.delta` [index] and [delta], `step.stop` [index] alone; `error` carries [error].
 */
@Serializable
internal data class InteractionsEvent(
    @SerialName("event_type") val eventType: String,
    /** What `last_event_id` resumes from after a dropped connection. */
    @SerialName("event_id") val eventId: String? = null,
    val interaction: InteractionsEventInteraction? = null,
    @SerialName("interaction_id") val interactionId: String? = null,
    val status: String? = null,
    val index: Int? = null,
    val step: InteractionsStep? = null,
    val delta: InteractionsDelta? = null,
    val error: InteractionsError? = null,
)

@Serializable
internal data class InteractionsEventInteraction(
    /** An EMPTY string, not an absent key, when the call ran with `store: false`. */
    val id: String? = null,
    val created: String? = null,
    val model: String? = null,
    val agent: String? = null,
    val status: String? = null,
    val usage: JsonObject? = null,
    @SerialName("service_tier") val serviceTier: String? = null,
)

/**
 * A `step.delta` payload.
 *
 * [arguments] is a STRING on an `arguments_delta` — a fragment of the function call's JSON, which the
 * consumer accumulates — and an OBJECT on a built-in tool call, which arrives whole. One field, two
 * shapes, hence [JsonElement].
 */
@Serializable
internal data class InteractionsDelta(
    val type: String,
    val text: String? = null,
    /** The `thought_summary` item. */
    val content: InteractionsContent? = null,
    val signature: String? = null,
    val arguments: JsonElement? = null,
    val id: String? = null,
    val annotations: List<InteractionsAnnotation>? = null,
    val data: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    val uri: String? = null,
    val resolution: String? = null,
    @SerialName("call_id") val callId: String? = null,
    val result: JsonElement? = null,
    @SerialName("is_error") val isError: Boolean? = null,
    val name: String? = null,
    @SerialName("server_name") val serverName: String? = null,
    @SerialName("search_type") val searchType: String? = null,
)

@Serializable
internal data class InteractionsError(
    /** A string in the reference's schema; a primitive here because a numeric code is a plausible drift. */
    val code: JsonPrimitive? = null,
    val message: String? = null,
)

/** The step types that report a built-in tool Google ran, and the ones that report what it returned. */
internal val BUILTIN_TOOL_CALL_TYPES: Set<String> = setOf(
    "google_search_call",
    "code_execution_call",
    "url_context_call",
    "file_search_call",
    "google_maps_call",
    "mcp_server_tool_call",
)

internal val BUILTIN_TOOL_RESULT_TYPES: Set<String> = setOf(
    "google_search_result",
    "code_execution_result",
    "url_context_result",
    "file_search_result",
    "google_maps_result",
    "mcp_server_tool_result",
)

/** `google_search_call` → `google_search`; an MCP call is named by its `name` instead. */
internal fun builtinToolName(stepType: String, name: String?): String = when (stepType) {
    "mcp_server_tool_call", "mcp_server_tool_result" -> name ?: "mcp_server_tool"
    else -> stepType.removeSuffix("_call").removeSuffix("_result")
}

/** `completed`, `failed`, `cancelled` and `incomplete`: the statuses nothing follows. */
internal fun isTerminalStatus(status: String?): Boolean = status in TERMINAL_STATUSES

private val TERMINAL_STATUSES = setOf("completed", "failed", "cancelled", "incomplete")
