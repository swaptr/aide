package com.sabreware.aide.core.domain.chat

/**
 * Per-assistant-message generation stats (ephemeral, current session — not persisted). Populated from
 * the turn's `ChatStreamEvent.Completed.usage` (token counts) + send-path timing (TTFT, total latency).
 * `tokensPerSec` is measured over the generation window (first token → done), excluding prefill.
 */
data class MessageStats(
    val ttftMs: Long?,
    val totalMs: Long,
    val tokensPerSec: Double?,
    val inputTokens: Int?,
    val outputTokens: Int?,
)
