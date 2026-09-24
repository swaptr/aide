package com.sabreware.aide.aisdk.providers.openaicompatible

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// OpenAI Chat Completions wire types (POST /chat/completions, stream=true).
//
// Chat Completions rather than the Responses API on purpose: it is the shape every compatible server
// speaks — Ollama, LM Studio, vLLM, llama.cpp, Groq, Together, Fireworks, DeepSeek, OpenRouter — and
// /responses is OpenAI-proper only.
//
// Request models declare no Kotlin defaults for fields the server requires: ProviderJson sets
// encodeDefaults = false, so a defaulted field is omitted from the wire. See aisdk/DESIGN.md.

@Serializable
internal data class OpenAIChatRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val stream: Boolean,
    @SerialName("stream_options") val streamOptions: OpenAIStreamOptions? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /**
     * The o-series and gpt-5 reject `max_tokens` outright and take this instead.
     *
     * Not an alias: sending both is also a 400, so exactly one of the two is ever populated.
     */
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    /** Not in OpenAI's own schema; several compatible servers (Ollama, vLLM) accept it. */
    @SerialName("top_k") val topK: Int? = null,
    @SerialName("presence_penalty") val presencePenalty: Double? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Double? = null,
    val seed: Int? = null,
    val stop: List<String>? = null,
    val tools: List<OpenAITool>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    @SerialName("response_format") val responseFormat: JsonElement? = null,
    /** o-series and gpt-5: `minimal` | `low` | `medium` | `high`. */
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

/** Ask for a final usage chunk; without it a streamed response reports no token counts at all. */
@Serializable
internal data class OpenAIStreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean,
)

@Serializable
internal data class OpenAIMessage(
    val role: String,
    /** String or an array of parts; JsonElement keeps both forms open. */
    val content: JsonElement? = null,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAIToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    /**
     * DeepSeek-R1 style separate reasoning channel, echoed back on replay by servers that require it.
     */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    /**
     * OpenRouter's structured reasoning blocks.
     *
     * This is the one aggregator that solved signed-reasoning replay: typed blocks carrying
     * `signature` (Anthropic) or `data` (encrypted), which must be sent back UNMODIFIED and in order or
     * the upstream vendor rejects the turn. Carried opaquely — see [REASONING_DETAILS_KEY].
     */
    @SerialName("reasoning_details") val reasoningDetails: List<JsonObject>? = null,
)

@Serializable
internal data class OpenAITool(
    val type: String,
    /**
     * Null for a vendor's own server-side tool, which is a bare `{"type": ...}` entry.
     *
     * Groq's `browser_search` is the shape: it takes no arguments and the vendor runs it, so there is
     * no function to describe. With `encodeDefaults = false` a null is omitted rather than sent as
     * `"function": null`, which is what keeps the bare entry bare.
     */
    val function: OpenAIFunction? = null,
)

@Serializable
internal data class OpenAIFunction(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject,
    val strict: Boolean? = null,
)

@Serializable
internal data class OpenAIToolCall(
    val id: String? = null,
    val index: Int? = null,
    val type: String? = null,
    val function: OpenAIToolCallFunction? = null,
    /**
     * Gemini's `thought_signature`, as it rides the compatible wire.
     *
     * Gemini issues a signature per function call and rejects the next turn if the call is replayed
     * without it. Anyone reaching Gemini through OpenRouter, Vercel's gateway or a self-hosted proxy is
     * on this path, so dropping the field here reproduces the exact defect this library exists to fix.
     */
    @SerialName("extra_content") val extraContent: OpenAIExtraContent? = null,
)

@Serializable
internal data class OpenAIExtraContent(
    val google: OpenAIGoogleExtraContent? = null,
)

@Serializable
internal data class OpenAIGoogleExtraContent(
    @SerialName("thought_signature") val thoughtSignature: String? = null,
)

@Serializable
internal data class OpenAIToolCallFunction(
    val name: String? = null,
    /** Streams as fragments that CONCATENATE; they are never cumulative. */
    val arguments: String? = null,
)

// ---- Streaming ------------------------------------------------------------------------------------

@Serializable
internal data class OpenAIStreamChunk(
    val id: String? = null,
    val model: String? = null,
    val created: Long? = null,
    val choices: List<OpenAIStreamChoice> = emptyList(),
    val usage: OpenAIUsage? = null,
    /**
     * Groq reports token counts here and nowhere else, and never honours `stream_options`.
     *
     * Read unconditionally rather than behind a vendor flag: no other server emits the field, so the
     * fallback costs nothing and one fewer knob can be set wrong.
     */
    @SerialName("x_groq") val xGroq: OpenAIGroqEnvelope? = null,
    /**
     * Sources the model consulted. Perplexity repeats the full list on every chunk; xAI sends it once
     * on the last. Both are handled by de-duplicating on the URL.
     */
    val citations: List<String>? = null,
)

@Serializable
internal data class OpenAIGroqEnvelope(val usage: OpenAIUsage? = null)

@Serializable
internal data class OpenAIStreamChoice(
    val index: Int? = null,
    val delta: OpenAIStreamDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class OpenAIStreamDelta(
    val role: String? = null,
    /**
     * A string on almost every server, and an ARRAY of typed parts on Mistral's reasoning models.
     *
     * Typed as `JsonElement` because it was typed as `String?`: Magistral streams `delta.content` as
     * `[{"type":"thinking",…}]`, so every content chunk failed to decode, the failure was swallowed, and
     * the caller got an empty turn with no error and no warning. See [contentParts].
     */
    val content: JsonElement? = null,
    val refusal: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAIToolCall>? = null,
    /** DeepSeek-R1 and friends: reasoning on its own channel rather than inline in `content`. */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    /** Some servers spell the same thing this way. */
    val reasoning: String? = null,
    /** OpenRouter's structured blocks, streamed per chunk. */
    @SerialName("reasoning_details") val reasoningDetails: List<JsonObject>? = null,
)

/**
 * The text and reasoning carried by one `delta.content`, in the order the server wrote them.
 *
 * Both forms end up here so the stream loop has one shape to handle: a bare string is one text part,
 * Mistral's array is however many typed parts it holds. `thinking` nests a second array of text parts,
 * which is Mistral's own shape and not a mistake.
 */
internal fun JsonElement.contentParts(): List<OpenAIContentPart> = when (this) {
    is JsonPrimitive -> if (isString && content.isNotEmpty()) {
        listOf(OpenAIContentPart.Text(content))
    } else {
        emptyList()
    }

    is JsonArray -> mapNotNull { entry ->
        val part = entry as? JsonObject ?: return@mapNotNull null
        when (part["type"]?.jsonPrimitive?.content) {
            "text" -> part["text"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotEmpty() }?.let { OpenAIContentPart.Text(it) }

            "thinking" -> part["thinking"]?.jsonArray.orEmpty()
                .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
                .takeIf { it.isNotEmpty() }?.let { OpenAIContentPart.Thinking(it) }

            // image_url and reference parts are input echoes with no place in a generated turn.
            else -> null
        }
    }

    else -> emptyList()
}

internal sealed interface OpenAIContentPart {
    data class Text(val text: String) : OpenAIContentPart
    data class Thinking(val text: String) : OpenAIContentPart
}

@Serializable
internal data class OpenAIUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("prompt_tokens_details") val promptTokensDetails: OpenAIPromptTokensDetails? = null,
    @SerialName("completion_tokens_details") val completionTokensDetails: OpenAICompletionTokensDetails? = null,
    /** Perplexity reports its reasoning count here rather than under `completion_tokens_details`. */
    @SerialName("reasoning_tokens") val reasoningTokens: Int? = null,
)

@Serializable
internal data class OpenAIPromptTokensDetails(
    @SerialName("cached_tokens") val cachedTokens: Int? = null,
)

@Serializable
internal data class OpenAICompletionTokensDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Int? = null,
)

/** GET /models → `{ data: [{ id, … }] }`. */
@Serializable
internal data class OpenAIModelsResponse(
    val data: List<OpenAIModelEntry> = emptyList(),
)

@Serializable
internal data class OpenAIModelEntry(val id: String)
