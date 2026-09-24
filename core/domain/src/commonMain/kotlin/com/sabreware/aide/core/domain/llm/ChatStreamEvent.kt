package com.sabreware.aide.core.domain.llm

import com.sabreware.aide.core.domain.chat.ProviderPayload
import kotlinx.serialization.json.JsonObject

// Invariants: text is delta-only (never cumulative); exactly one [Completed] terminates a send.
// Provider-native tools skip [ToolCallCompleted] — output surfaces as subsequent [TextDelta]s.
sealed interface ChatStreamEvent {
    data class TextDelta(val text: String) : ChatStreamEvent

    /**
     * Incremental reasoning, and the payload that closes a block.
     *
     * [providerMetadata] is the opaque, provider-namespaced payload that makes the block replayable, and
     * its presence CLOSES the current block — so a consumer accumulating deltas knows which text the
     * payload signs, and hands the pair straight to
     * [com.sabreware.aide.core.domain.chat.AidePart.Thinking] without ever naming a vendor.
     *
     * A field this event does not have is data the consumer silently drops, and this is the exact spot
     * where it used to: without it every thinking block was persisted unsigned, so the first session
     * rebind replayed a conversation Anthropic degrades and Gemini rejects with a 400.
     */
    data class ThinkingDelta(
        val text: String,
        val providerMetadata: ProviderPayload? = null,
    ) : ChatStreamEvent

    /** [providerMetadata] is Gemini's `thoughtSignature`, which it puts on the call rather than the thought. */
    data class ToolCallStarted(
        val callId: String,
        val name: String,
        val args: JsonObject,
        val providerMetadata: ProviderPayload? = null,
    ) : ChatStreamEvent

    data class ToolCallCompleted(
        val callId: String,
        val name: String,
        val resultJson: String,
        val error: String? = null,
    ) : ChatStreamEvent

    // [rawFinishReason] is the provider's verbatim stop string (Ollama done_reason; null for engines
    // without one) kept beside the normalized [stopReason] for diagnostics. [usage] is populated only
    // when the provider reports token counts — never faked to zero; null means the engine didn't report.
    // [warnings] carries non-fatal degradations the engine applied this turn (dropped config/tools);
    // empty when the request was honored in full.
    data class Completed(
        val stopReason: StopReason,
        val usage: Usage? = null,
        val rawFinishReason: String? = null,
        val warnings: List<ModelWarning> = emptyList(),
    ) : ChatStreamEvent

    /** [Interrupted]: the stream closed without the protocol's end marker — a dropped connection that
     *  Ktor surfaced as a clean close, not a provider saying the turn is over. Distinct from [Error]
     *  because nothing threw; distinct from [EndTurn] because the reply may be truncated. */
    enum class StopReason { EndTurn, MaxTokens, StopSequence, ToolUse, Error, Cancelled, Interrupted }

    data class Usage(val inputTokens: Int?, val outputTokens: Int?)
}
