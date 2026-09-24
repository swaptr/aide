package com.sabreware.aide.core.domain.llm

/**
 * A non-fatal degradation an engine applied to a turn — a config field or tool it could not honor.
 *
 * Rides the terminal [ChatStreamEvent.Completed.warnings] so callers can log or surface the drop
 * instead of losing it to a bare log line. Populated where an adapter silently degrades a request
 * (today: LiteRT drops `responseSchema`/`stopSequences`). Pairs with the capability-gated
 * param-drop step (TODO E7), which will generalize the manual per-field checks into one pass.
 */
sealed interface ModelWarning {
    /** A [ChatGenerationConfig] field the engine ignored, with a short human [reason]. */
    data class UnsupportedSetting(val field: String, val reason: String) : ModelWarning

    /** A tool the engine could not expose (e.g. a ProviderNative tool on a local engine). */
    data class UnsupportedTool(val name: String) : ModelWarning

    /** Anything else worth surfacing that is neither a setting nor a tool. */
    data class Other(val message: String) : ModelWarning
}
