package com.sabreware.aide.aisdk

import kotlinx.serialization.Serializable

import kotlinx.serialization.json.JsonObject

/**
 * Token accounting for one call.
 *
 * Every count is nullable because "the provider did not report this" and "this was zero" are different
 * facts, and a UI that renders the first as the second is lying about cost. [raw] keeps the vendor's own
 * usage object so a provider-specific counter is never lost to normalization.
 */
@Serializable
public data class Usage(
    /** Prompt-side counts, split by cache participation. */
    val inputTokens: InputTokens = InputTokens(),
    /** Generation-side counts, split into visible text and reasoning. */
    val outputTokens: OutputTokens = OutputTokens(),
    val raw: JsonObject? = null,
) {

    /** Prompt-side token counts. The cache split is the billing story — the three rates differ. */
    @Serializable
    public data class InputTokens(
        /** All input tokens, cached and not. */
        val total: Int? = null,
        /** Input tokens billed at the full rate — neither read from nor written to a cache. */
        val noCache: Int? = null,
        /** Input tokens served from the vendor's prompt cache, billed at the discounted rate. */
        val cacheRead: Int? = null,
        /** Input tokens written into the cache on this call, billed at the premium rate. */
        val cacheWrite: Int? = null,
    )

    /** Generation-side token counts. */
    @Serializable
    public data class OutputTokens(
        /** All output tokens, visible text and reasoning together. */
        val total: Int? = null,
        /** Output tokens in the visible reply. */
        val text: Int? = null,
        /** Output tokens the model spent thinking — billed even where the text is hidden. */
        val reasoning: Int? = null,
    )
}
