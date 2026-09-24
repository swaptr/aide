package com.sabreware.aide.core.domain.model

// Engines must silently drop unsupported fields (warn-and-continue) — a degraded reply
// beats an exception in the streaming pipeline.
data class ChatCapabilities(
    val visionIn: Boolean = false,
    val audioIn: Boolean = false,
    /**
     * Model accepts a document (PDF) as a native binary attachment (the `:aisdk` `UserPart.File` with a
     * document media type). Gates ONLY binary docs: anything text-extractable is inlined as text and
     * needs no capability at all.
     */
    val documentIn: Boolean = false,
    /** Model accepts and dispatches locally-executed function tools. */
    val toolsLocal: Boolean = false,
    val structuredOutput: StructuredOutput = StructuredOutput.None,
    val thinking: ThinkingMode = ThinkingMode.None,
    // Catalog-only metadata (C4): captured from the allowlist but NOT consumed at runtime today —
    // embedding specs are filtered out before becoming chat specs, and the window sizes are not used
    // for truncation (sampling maxTokens comes from the model's defaultConfig). Kept (not dropped) so a
    // future embeddings / long-context feature finds the data already threaded.
    val embeddings: Boolean = false,
    val maxContext: Int,
    val maxOutput: Int,
    /**
     * Sampling params this model REJECTS (LobeChat's `disabledParams` / big-AGI's `hotfix-no-temperature`
     * pattern). The request builder strips whatever is listed instead of letting the provider 400: the
     * `:aisdk` chat session omits temperature / topP / topK from `CallOptions` for a listed param, and
     * null there means "omit the field", never "send the default". The penalties are declared honestly
     * for models that reject them and stay advisory until a path sends them.
     */
    val disabledParams: Set<SamplerParam> = emptySet(),
) {
    /** Individually-disableable sampling knobs (e.g. OpenAI o-series accept only temperature=1, no top_p). */
    enum class SamplerParam { Temperature, TopP, TopK, FrequencyPenalty, PresencePenalty }

    enum class StructuredOutput { None, JsonMode, JsonSchema }

    sealed interface ThinkingMode {
        data object None : ThinkingMode
        data object Toggle : ThinkingMode
        data class Levels(val levels: Set<String>) : ThinkingMode
    }
}
