package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.util.ProviderJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// OpenAI Responses API wire types (POST /v1/responses).
//
// This is a DIFFERENT wire from Chat Completions, not a dialect of it, and the difference is the reason
// this package exists. Chat Completions carries a `messages` array of role/content pairs and has no
// representation for reasoning at all; Responses carries an `input` array of typed ITEMS — a reasoning
// item, a function call, a function call output, a web search call — each with its own id, and reasoning
// is one of them. That is what makes an OpenAI chain of thought replayable across a tool round instead of
// being re-derived and re-billed every time.
//
// Only the RESPONSE side is modelled as data classes. The request's `input` is a 25-arm union whose arms
// share almost no fields, so it is built as `JsonObject` in OpenAIResponsesPrompt — the same choice
// AnthropicPrompt makes, and for the same reason: a nullable-everything data class covering that union
// says nothing a reader can rely on.

/** POST /v1/responses, and the `response` payload nested inside every terminal stream chunk. */
@Serializable
internal data class OpenAIResponse(
    val id: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    val model: String? = null,
    /**
     * Absent rather than empty when the call failed. Several OpenAI-compatible upstreams answer 200 with
     * no `output` at all, which is a failure the caller must be told about rather than an empty turn.
     */
    val output: List<@Serializable(with = OpenAIOutputItemSerializer::class) OpenAIOutputItem>? = null,
    val error: OpenAIResponseError? = null,
    @SerialName("incomplete_details") val incompleteDetails: OpenAIIncompleteDetails? = null,
    @SerialName("service_tier") val serviceTier: String? = null,
    val reasoning: OpenAIResponseReasoning? = null,
    /**
     * Kept as the raw object and decoded on read — see [decodeResponsesUsage]. The typed fields are the
     * ones normalized into [com.sabreware.aide.aisdk.Usage]; everything else OpenAI puts here
     * (`total_tokens`, the orchestration counters, whatever ships next) survives into `Usage.raw`
     * instead of being dropped at decode. Explicitly `null` on a failed response.
     */
    val usage: JsonObject? = null,
)

@Serializable
internal data class OpenAIResponseError(
    val message: String,
    val type: String? = null,
    val code: String? = null,
    val param: String? = null,
)

@Serializable
internal data class OpenAIIncompleteDetails(val reason: String? = null)

/** Only `context` is read; the request-side `effort`/`summary` echo back here and are not needed. */
@Serializable
internal data class OpenAIResponseReasoning(val context: String? = null)

/**
 * One item in `output`.
 *
 * A single nullable-field class covers every item type, and the mappers branch on [type] — the same
 * shape `AnthropicContentBlock` uses. The alternative, a sealed hierarchy with a custom discriminator,
 * turns the day OpenAI ships a new item type into a decode failure that kills the whole response instead
 * of an item nothing maps.
 */
@Serializable
internal data class OpenAIOutputItem(
    val type: String,
    val id: String? = null,
    val status: String? = null,

    // message
    val role: String? = null,
    val content: List<OpenAIOutputContent>? = null,
    /** `commentary` | `final_answer` — which half of a two-part answer this message is. */
    val phase: String? = null,

    // reasoning
    val summary: List<OpenAISummaryPart>? = null,
    /**
     * The encrypted chain of thought, returned only with `include: ["reasoning.encrypted_content"]`.
     *
     * This is OpenAI's counterpart to Anthropic's thinking `signature`: opaque, mandatory to replay
     * verbatim, and the single value whose loss makes a reasoning model re-derive and re-bill its
     * reasoning on every tool round.
     */
    @SerialName("encrypted_content") val encryptedContent: String? = null,

    // function_call / custom_tool_call / mcp_call / computer_call / local_shell_call
    @SerialName("call_id") val callId: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    /** `custom_tool_call` only: the model's free-form (non-JSON) input. */
    val input: String? = null,
    /**
     * `function_call` / `custom_tool_call`: the model may go on generating without waiting for this
     * call's result — GPT-6's async tool calling. Filed on the call's metadata so a replay sends it back.
     */
    val async: Boolean? = null,
    /** `function_call`: the tool's namespace and, for a call a hosted program made, who made it. */
    val namespace: String? = null,
    val caller: JsonObject? = null,
    /** `apply_patch_call`: the file operation, kept whole — `{type, path, diff?}`. */
    val operation: JsonObject? = null,
    /** web_search_call, computer_call, local_shell_call: kept whole; the shapes differ per tool. */
    val action: JsonObject? = null,
    val actions: JsonElement? = null,
    @SerialName("pending_safety_checks") val pendingSafetyChecks: JsonElement? = null,

    // file_search_call
    val queries: List<String>? = null,
    val results: JsonElement? = null,

    // code_interpreter_call
    val code: String? = null,
    @SerialName("container_id") val containerId: String? = null,
    val outputs: JsonElement? = null,

    // image_generation_call: the base64 image itself
    val result: String? = null,

    // mcp_call / mcp_approval_request / mcp_list_tools
    @SerialName("server_label") val serverLabel: String? = null,
    val output: JsonElement? = null,
    /** String or object depending on the failure; carried opaquely. */
    val error: JsonElement? = null,
    @SerialName("approval_request_id") val approvalRequestId: String? = null,

    /**
     * The untouched item, present ONLY for a namespaced (`ns:kind`) type — an Open Responses
     * extension's item. See [OpenAIOutputItemSerializer] for why the rest of this class never sees
     * such an item's fields.
     */
    val extension: JsonObject? = null,
)

/**
 * Decodes an extension item as `{type, id, status, extension}` and every other item as itself.
 *
 * A namespaced type belongs to an extension, and its fields are the extension's to define: an
 * `acme:document_search_result` may well carry `result` as an object where OpenAI's
 * `image_generation_call` carries it as a string. Decoding those fields into [OpenAIOutputItem] would
 * fail on the first such collision — and in the JSON response take the whole turn down with it, since
 * one bad element fails the `output` array. So an extension item is reduced to the three fields the
 * spec guarantees, and the object itself rides in [OpenAIOutputItem.extension] for the extension to
 * read. Applied at the two places an item is decoded, rather than as the class's own serializer, so
 * the generated serializer stays the one it delegates to.
 */
internal object OpenAIOutputItemSerializer : JsonTransformingSerializer<OpenAIOutputItem>(OpenAIOutputItem.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val item = element as? JsonObject ?: return element
        val type = item.namespacedType() ?: return element
        return buildJsonObject {
            put("type", type)
            item.stringField("id")?.let { put("id", it) }
            item.stringField("status")?.let { put("status", it) }
            put("extension", item)
        }
    }
}

@Serializable
internal data class OpenAIOutputContent(
    val type: String,
    val text: String? = null,
    val annotations: List<OpenAIAnnotation> = emptyList(),
    val logprobs: JsonElement? = null,
)

/**
 * A citation attached to generated text.
 *
 * `url_citation` is what a web search produces and is the one that becomes a `Content.Source.Url`; the
 * file variants become `Content.Source.Document`.
 */
@Serializable
internal data class OpenAIAnnotation(
    val type: String,
    val url: String? = null,
    val title: String? = null,
    @SerialName("file_id") val fileId: String? = null,
    val filename: String? = null,
    @SerialName("container_id") val containerId: String? = null,
    val index: Int? = null,
    @SerialName("start_index") val startIndex: Int? = null,
    @SerialName("end_index") val endIndex: Int? = null,
)

@Serializable
internal data class OpenAISummaryPart(
    val type: String? = null,
    val text: String = "",
)

@Serializable
internal data class OpenAIResponsesUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("input_tokens_details") val inputTokensDetails: OpenAIInputTokensDetails? = null,
    @SerialName("output_tokens_details") val outputTokensDetails: OpenAIOutputTokensDetails? = null,
)

@Serializable
internal data class OpenAIInputTokensDetails(
    @SerialName("cached_tokens") val cachedTokens: Int? = null,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Int? = null,
)

@Serializable
internal data class OpenAIOutputTokensDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Int? = null,
)

/** The typed view of a raw `usage` object; null where the response carried none. */
internal fun JsonObject?.decodeResponsesUsage(): OpenAIResponsesUsage? =
    this?.let { ProviderJson.decodeFromJsonElement(OpenAIResponsesUsage.serializer(), it) }

// ---- Streaming ------------------------------------------------------------------------------------
//
// The Responses API streams one JSON document per SSE frame, routed by the document's own `type`. The
// `event:` line carries the same value, but the reference's recorded fixtures contain only `data:`
// lines — so routing on the payload is both correct and the only thing that works against them.

@Serializable
internal data class OpenAIResponsesChunk(
    val type: String,
    @SerialName("sequence_number") val sequenceNumber: Int? = null,

    /** response.created / in_progress / completed / incomplete / failed. */
    val response: OpenAIResponse? = null,

    /** response.output_item.added / done. */
    @Serializable(with = OpenAIOutputItemSerializer::class)
    val item: OpenAIOutputItem? = null,
    @SerialName("output_index") val outputIndex: Int? = null,
    @SerialName("item_id") val itemId: String? = null,
    @SerialName("summary_index") val summaryIndex: Int? = null,
    @SerialName("content_index") val contentIndex: Int? = null,

    /** Text, reasoning-summary, function-call-argument and custom-tool-input deltas all use this. */
    val delta: String? = null,
    /** The completed value on a `.done` chunk — `code` for the code interpreter, `text` for a message. */
    val text: String? = null,
    val code: String? = null,

    val annotation: OpenAIAnnotation? = null,
    @SerialName("partial_image_b64") val partialImageB64: String? = null,

    /**
     * The `error` chunk, in the shape OpenAI's OpenAPI documents it: message and code at the top level.
     * The nested shape is [error].
     */
    val message: String? = null,
    /**
     * The `error` chunk's other observed shape — a nested object, which is what OpenAI actually sent for
     * an `insufficient_quota` failure raised after the 200. A client that models only one of the two
     * reports a mid-stream failure as an unparseable frame.
     */
    val error: OpenAIResponseError? = null,

    /**
     * The untouched frame, present ONLY for a namespaced (`ns:kind`) chunk type — an Open Responses
     * extension's streaming event. Its fields are the extension's, for the same reason as
     * [OpenAIOutputItem.extension]: a `delta` that is an object where OpenAI's is a string would
     * otherwise fail the frame, and a failed frame is silently skipped.
     */
    val extension: JsonObject? = null,
)

/** [OpenAIOutputItemSerializer]'s counterpart for a stream frame: an extension event keeps only its `type`. */
internal object OpenAIResponsesChunkSerializer :
    JsonTransformingSerializer<OpenAIResponsesChunk>(OpenAIResponsesChunk.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val chunk = element as? JsonObject ?: return element
        val type = chunk.namespacedType() ?: return element
        return buildJsonObject {
            put("type", type)
            chunk["sequence_number"]?.let { put("sequence_number", it) }
            put("extension", chunk)
        }
    }
}
