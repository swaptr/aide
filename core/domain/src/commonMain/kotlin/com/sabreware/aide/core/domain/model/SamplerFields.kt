package com.sabreware.aide.core.domain.model

import kotlinx.serialization.Serializable

/**
 * The user-tunable sampling knobs, each nullable = "leave at the lower layer's value". Both the
 * allowlist [ModelDefaultConfig] and a user [SamplerOverride] expose them, so one resolver
 * (`ChatGenerationConfig.applyingSampler`) layers them: hardcoded base ← model default ← user override.
 */
interface SamplerFields {
    val topK: Int?
    val topP: Float?
    val temperature: Float?
    val maxTokens: Int?
}

/** A user's per-model sampling override; persisted in the [ModelDocuments.SamplerOverrides] document. */
@Serializable
data class SamplerOverride(
    override val topK: Int? = null,
    override val topP: Float? = null,
    override val temperature: Float? = null,
    override val maxTokens: Int? = null,
) : SamplerFields {
    /** True when nothing is set — callers drop empty overrides rather than persist a no-op. */
    fun isEmpty(): Boolean = topK == null && topP == null && temperature == null && maxTokens == null
}
