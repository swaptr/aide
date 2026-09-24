package com.sabreware.aide.core.domain.embedding

/**
 * Turns text into vectors.
 *
 * **Batching is the engine's job, not the caller's.** Every vendor caps how many values one request may
 * carry, the cap differs per vendor and per model, and a caller that has to know it either hard-codes a
 * number that goes stale or discovers the limit as a rejected request. So [embed] takes as many values as
 * the caller has and the implementation splits them — the returned list is one vector per input, in the
 * order given, whatever the vendor's ceiling turned out to be.
 *
 * Transport-free: no HTTP types, no vendor fields.
 */
interface EmbeddingEngine {
    suspend fun embed(
        modelName: String,
        values: List<String>,
        options: EmbeddingOptions = EmbeddingOptions(),
    ): List<Embedding>
}

/** One vector. */
typealias Embedding = List<Double>

/**
 * [purpose] is the one knob worth naming rather than leaving to provider options.
 *
 * Several retrieval vendors embed a search query and a stored document differently, and passing the wrong
 * one costs retrieval quality with no error to notice — the call succeeds and the results are quietly
 * worse. A vendor that makes no distinction ignores it.
 */
data class EmbeddingOptions(val purpose: EmbeddingPurpose? = null)

enum class EmbeddingPurpose { Query, Document }
