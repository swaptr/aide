package com.sabreware.aide.core.domain.io

/**
 * The neutral output of the middle "reason" stage (B5). An [OutputChannel] consumes THIS, not a
 * concrete orchestration union like `SendChatMessageUseCase.Event` — so the voice/speech layer no
 * longer depends on the chat use case and a non-chat reasoner (a pure read-aloud, a different agent
 * loop) can drive the same output channel without manufacturing chat-only events.
 *
 * Deliberately thin: only what a generic output surface needs. [TextDelta] carries the REAL incremental
 * delta (B6) so consumers never reconstruct it by string-stripping cumulative text (which double-speaks
 * on a non-monotonic update). The chat surface keeps reacting to the full `SendChatMessageUseCase.Event`
 * directly (via its identity output channel) — this neutral form is for the transforming output paths.
 */
sealed interface ReasonEvent {
    /** The model is loading / warming up. */
    data object Warming : ReasonEvent

    /** A real incremental text delta (never cumulative). */
    data class TextDelta(val delta: String) : ReasonEvent

    /** A tool call is starting; [name] is the raw tool name. */
    data class ToolAnnounce(val name: String) : ReasonEvent

    /** Terminal success — [text] is the final assistant text for the turn. */
    data class Done(val text: String) : ReasonEvent

    /** Terminal failure with a user-facing [message]. */
    data class Error(val message: String) : ReasonEvent
}
