package com.sabreware.aide.aisdk.providers.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Anthropic Messages API wire types (POST /v1/messages, stream=true).
//
// Moved here from AIDE's own `:data:llm/anthropic`, which is the implementation that already gets signed
// thinking replay right — the thing this whole port exists to preserve. The wire model carried over
// unchanged; only its edges moved (transport is now :aisdk:util, and the neutral types are the spec's).
//
// The shape differs from OpenAI in three load-bearing ways the mapper handles:
//   * `system` is TOP-LEVEL, not a message role.
//   * `max_tokens` is REQUIRED, not optional.
//   * content is always an array of typed blocks (text / tool_use / tool_result / image / thinking).

@Serializable
internal data class AnthropicChatRequest(
    val model: String,
    // Required by Anthropic. No default: always serialized.
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<AnthropicMessage>,
    // Top-level system prompt (the mapper hoists the system message out of the list). A bare string, or
    // an array of text blocks when one carries a `cache_control`.
    val system: JsonElement? = null,
    // Raw objects: a function tool and each of Anthropic's twenty server-side tools are different
    // shapes, and a union of twenty data classes buys nothing over the JSON they all end up as.
    val tools: List<JsonObject>? = null,
    // "auto" | "any" | "none" | {type:"tool", name:"…"} — JsonElement covers every form; null omits the
    // field, and the server default is auto.
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    // Extended thinking. When present the assistant streams thinking/signature blocks, and the prior
    // signed block must be replayed across tool rounds; budget_tokens < max_tokens; tool_choice limited
    // to auto/none. Null = no extended thinking.
    val thinking: AnthropicThinking? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("top_k") val topK: Int? = null,
    @SerialName("stop_sequences") val stopSequences: List<String>? = null,
    // Reasoning depth, task budget and structured-output format all live here:
    // {"effort": low|medium|high|xhigh|max, "task_budget": {…}, "format": {"type":"json_schema", …}}.
    // `effort: high` equals omitting the field, and it is never combined with thinking type "disabled"
    // (Opus 5 400s at xhigh/max).
    @SerialName("output_config") val outputConfig: JsonObject? = null,
    // No default: always serialized (Anthropic defaults stream=false).
    val stream: Boolean,
)

/**
 * The thinking knob, both generations:
 *  - `{type:"enabled", budget_tokens}` — manual extended thinking. The ONLY mode on ≤4.5 models;
 *    rejected with a 400 by 4.7+ (including Opus/Sonnet 5 and Fable 5).
 *  - `{type:"adaptive"}` — 4.6+; depth steered by `output_config.effort`, not a budget.
 *  - `{type:"disabled"}` — adaptive models that allow thinking off (fable/mythos reject it).
 *
 * `display:"summarized"` opts into the visible trace; newer models otherwise default to "omitted",
 * meaning a signature with no readable text.
 */
@Serializable
internal data class AnthropicThinking(
    /**
     * No Kotlin default, deliberately. `ProviderJson` sets `encodeDefaults = false`, so a field left at
     * its default is omitted from the wire — and a `thinking` object with no `type` is a 400 on every
     * ordinary request. A request model must never rely on a Kotlin default for a field the server
     * requires, so the call site states it every time.
     *
     * Nullable since the thinking-binding controls: a binding-only recovery request is
     * `{"block_binding": …}` with no `type` at all, so null here is a real value meaning OMIT rather
     * than a default the wire would silently lose.
     */
    val type: String?,
    @SerialName("budget_tokens") val budgetTokens: Int? = null,
    /** `omitted` | `summarized` | `updates` — the last streams thinking between tool calls. */
    val display: String? = null,
    @SerialName("block_binding") val blockBinding: AnthropicBlockBinding? = null,
)

/**
 * How a replayed thinking prefix that no longer matches the model's own is handled: `error` fails the
 * request, `drop_block` drops the block and reports it under `input_transformations`. Needs the
 * `thinking-binding-controls-2026-08-01` beta, which the request builder adds.
 */
@Serializable
internal data class AnthropicBlockBinding(
    @SerialName("prefix_mismatch_behavior") val prefixMismatchBehavior: String,
)

@Serializable
internal data class AnthropicMessage(
    /**
     * `user` or `assistant` — tool results ride in a user turn — plus `system` for a MID-CONVERSATION
     * system message, which is the one case the initial system prompt is not hoisted top-level. See
     * `AnthropicPrompt.kt` for when a system message lands here rather than in `system`.
     */
    val role: String,
    /** Always an array of typed blocks; raw objects keep the union open to blocks we do not model. */
    val content: List<JsonObject>,
    /** Mid-conversation system only: `next_user_message` clears the message when the user next speaks. */
    @SerialName("clear_at") val clearAt: String? = null,
    /** Mid-conversation system only: `{"effort": …}` for the turn that follows this message. */
    @SerialName("output_config") val outputConfig: JsonObject? = null,
)

// ---- Streaming SSE events -------------------------------------------------------------------------
// Anthropic emits message_start, content_block_{start,delta,stop}, message_delta, message_stop, ping and
// error. One event type covers them all with nullable fields, so any event decodes and the mapper
// branches on `type` — a vendor adding an event kind is then inert rather than fatal.

@Serializable
internal data class AnthropicStreamEvent(
    val type: String,
    /** content_block_* carry the block index, shared across all blocks in the message. */
    val index: Int? = null,
    val message: AnthropicStreamMessage? = null,
    @SerialName("content_block") val contentBlock: AnthropicContentBlock? = null,
    /** content_block_delta (text/input_json/thinking/signature) AND message_delta (stop_reason). */
    val delta: AnthropicDelta? = null,
    /** message_delta: CUMULATIVE output-token usage for the round. */
    val usage: AnthropicUsage? = null,
    /** message_delta, event top-level (a sibling of `delta`): which context edits the server applied. */
    @SerialName("context_management") val contextManagement: AnthropicContextManagement? = null,
    /** message_delta, event top-level: input blocks the server altered before inference. */
    @SerialName("input_transformations") val inputTransformations: List<AnthropicInputTransformation>? = null,
    val error: AnthropicError? = null,
)

@Serializable
internal data class AnthropicStreamMessage(
    val id: String? = null,
    val model: String? = null,
    val role: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: AnthropicUsage? = null,
    /** The code-execution container, `{expires_at, id}` here; skills arrive on message_delta. */
    val container: AnthropicContainer? = null,
    /** Input blocks the server altered before inference — a dropped mismatched thinking block, today. */
    @SerialName("input_transformations") val inputTransformations: List<AnthropicInputTransformation>? = null,
)

/**
 * One input block the server transformed before inference, reported when the thinking-binding
 * controls drop a mismatched thinking block. Every field is lenient: the reference marks all three as
 * open-ended strings so a new transformation type stays forward compatible, and this decoder skips a
 * frame it cannot decode — a stricter shape here would cost the `message_delta` the report rides on,
 * stop reason and usage included.
 */
@Serializable
internal data class AnthropicInputTransformation(
    val type: String? = null,
    val path: String? = null,
    val reason: String? = null,
)

/** The code-execution container a turn ran in. Its id is what a next step reuses. */
@Serializable
internal data class AnthropicContainer(
    @SerialName("expires_at") val expiresAt: String? = null,
    val id: String? = null,
    val skills: List<AnthropicContainerSkill>? = null,
)

@Serializable
internal data class AnthropicContainerSkill(
    /** `anthropic` or `custom`. */
    val type: String? = null,
    @SerialName("skill_id") val skillId: String? = null,
    val version: String? = null,
)

/** Why the turn stopped, beyond the bare `stop_reason` string. */
@Serializable
internal data class AnthropicStopDetails(
    val type: String? = null,
    val category: String? = null,
    val explanation: String? = null,
    @SerialName("recommended_model") val recommendedModel: String? = null,
)

/** The context edits the server applied, kept as raw objects — each edit type has its own fields. */
@Serializable
internal data class AnthropicContextManagement(
    @SerialName("applied_edits") val appliedEdits: List<JsonObject> = emptyList(),
)

@Serializable
internal data class AnthropicContentBlock(
    /**
     * `text` | `tool_use` | `thinking` | `redacted_thinking` | `server_tool_use` | `mcp_tool_use`, plus
     * the `*_tool_result` families a server-side tool answers with.
     */
    val type: String,
    val id: String? = null,
    val name: String? = null,
    val text: String? = null,
    val thinking: String? = null,
    /**
     * A server-side tool's input, complete at content_block_start for some tools and streamed as deltas
     * for others — hence the emptiness check at the mapper rather than a preference for one or the other.
     */
    val input: JsonElement? = null,
    /** `*_tool_result` blocks: which `server_tool_use` block this answers. */
    @SerialName("tool_use_id") val toolUseId: String? = null,
    /** `*_tool_result` payload. An array of results, or an object describing why there are none. */
    val content: JsonElement? = null,
    /** `mcp_tool_use`: which MCP server ran the tool. */
    @SerialName("server_name") val serverName: String? = null,
    /**
     * A client toolset member's `tool_use`: which toolset it belongs to (`computer` or `browser`).
     *
     * Load-bearing in both directions. Dispatch is on the (`toolset_name`, `name`) PAIR, because the two
     * toolsets share member names such as `screenshot` and a caller's own tool may share one too. And the
     * answering `tool_result` has to echo it back — a result that omits it is rejected.
     */
    @SerialName("toolset_name") val toolsetName: String? = null,
    /**
     * redacted_thinking: the encrypted reasoning payload, complete at content_block_start with no
     * deltas. Must be replayed verbatim — dropping it breaks multi-turn tool use with a 400.
     */
    val data: String? = null,
    /**
     * A COMPLETE thinking block's signature — batch results only. The streaming wire never puts it
     * here (it arrives as `signature_delta`); a batch result is the finished message, so the block
     * carries its own signature and the batch mapper re-synthesizes the delta the stream would have had.
     */
    val signature: String? = null,
    /** A complete text block's citations — batch results only, for the same reason as [signature]. */
    val citations: List<JsonObject>? = null,
)

@Serializable
internal data class AnthropicDelta(
    /**
     * `text_delta` | `input_json_delta` | `thinking_delta` | `signature_delta` on content_block_delta;
     * null on message_delta, which carries stop_reason instead.
     */
    val type: String? = null,
    val text: String? = null,
    @SerialName("partial_json") val partialJson: String? = null,
    val thinking: String? = null,
    val signature: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("stop_sequence") val stopSequence: String? = null,
    /** message_delta: structured stop reason. */
    @SerialName("stop_details") val stopDetails: AnthropicStopDetails? = null,
    /** message_delta: the container again, now with `skills`. Overwrites the message_start value. */
    val container: AnthropicContainer? = null,
)

@Serializable
internal data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int? = null,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int? = null,
    /** Streaming: this arrives only on the FINAL message_delta. */
    @SerialName("output_tokens_details") val outputTokensDetails: AnthropicOutputTokensDetails? = null,
    /** Per-iteration usage of a server-side agentic turn (compaction, fallback, advisor rounds). */
    val iterations: List<AnthropicUsageIteration>? = null,
)

@Serializable
internal data class AnthropicUsageIteration(
    /** `compaction` | `message` | `advisor_message` | `fallback_message`. */
    val type: String? = null,
    val model: String? = null,
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int? = null,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int? = null,
)

/** How many of the billed output tokens were internal reasoning. */
@Serializable
internal data class AnthropicOutputTokensDetails(
    @SerialName("thinking_tokens") val thinkingTokens: Int? = null,
)

@Serializable
internal data class AnthropicError(
    val type: String? = null,
    val message: String? = null,
)

/** The error envelope of a failed request: `{"type":"error","error":{"type":…,"message":…}}`. */
@Serializable
internal data class AnthropicErrorResponse(
    val error: AnthropicError? = null,
)

/** GET /v1/models → `{ data: [{ id, display_name, … }] }`. */
@Serializable
internal data class AnthropicModelsResponse(
    val data: List<AnthropicModel> = emptyList(),
)

@Serializable
internal data class AnthropicModel(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
)
