package com.swaptr.aide.domain.llm

import kotlinx.serialization.json.JsonObject

// Invariants: text is delta-only (never cumulative); exactly one [Completed] terminates a send.
// Provider-native tools skip [ToolCallCompleted] — output surfaces as subsequent [TextDelta]s.
sealed interface ChatStreamEvent {
    data class TextDelta(val text: String) : ChatStreamEvent

    data class ThinkingDelta(val text: String) : ChatStreamEvent

    data class ToolCallStarted(
        val callId: String,
        val name: String,
        val args: JsonObject,
    ) : ChatStreamEvent

    data class ToolCallCompleted(
        val callId: String,
        val name: String,
        val resultJson: String,
        val error: String? = null,
    ) : ChatStreamEvent

    data class Completed(val stopReason: StopReason, val usage: Usage? = null) : ChatStreamEvent

    enum class StopReason { EndTurn, MaxTokens, StopSequence, ToolUse, Error, Cancelled }

    data class Usage(val inputTokens: Int?, val outputTokens: Int?)
}
