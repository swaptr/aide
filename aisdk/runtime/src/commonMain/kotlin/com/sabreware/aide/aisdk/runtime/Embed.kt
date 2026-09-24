package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.Embedding
import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.ModalityResponse
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.ProviderOptions
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.RetryPolicy
import com.sabreware.aide.aisdk.util.withRetry
import kotlin.math.sqrt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** One value and the vector it embedded to. */
public data class EmbedResult(
    /** The text that was embedded, echoed back so the pair travels together. */
    val value: String,
    /** The vector. */
    val embedding: Embedding,
    /** Tokens, when the vendor reports them. */
    val usage: Int? = null,
    /** What the model ignored or changed about the call. */
    val warnings: List<Warning> = emptyList(),
    /** The vendor's own response payload, keyed by provider id. */
    val providerMetadata: ProviderMetadata? = null,
    /** Response identity — id, model, timestamp, headers — for logs and support tickets. */
    val response: ModalityResponse? = null,
)

/**
 * Many values, their vectors, and one entry in [responses] per call that was actually made.
 *
 * [responses] is a list rather than a single response because a batch that crossed the model's ceiling
 * is several requests, and collapsing them to "the response" picks one arbitrarily — which is the
 * field a caller reaches for when a vendor rate-limits half a batch and it needs to know which half.
 */
public data class EmbedManyResult(
    /** The submitted texts, in the caller's order. */
    val values: List<String>,
    /** One vector per value, index-aligned with [values]. */
    val embeddings: List<Embedding>,
    /** Tokens across every call, or null when no call reported a count. */
    val usage: Int? = null,
    /** Every distinct warning any call produced. */
    val warnings: List<Warning> = emptyList(),
    /** The calls' vendor payloads, merged per provider id. */
    val providerMetadata: ProviderMetadata? = null,
    /** One entry per call actually made — see the class doc for why this is a list. */
    val responses: List<ModalityResponse?> = emptyList(),
)

/**
 * Embeds a single value.
 *
 * ```kotlin
 * val model = VoyageProvider(client, apiKey).embeddingModel("voyage-3.5")
 * val result = embed(model, "How do I cancel my subscription?")
 * println(result.embedding.size)
 * ```
 *
 * @param model the embedding model to call.
 * @param value the text to embed.
 * @param providerOptions vendor-namespaced options, passed through opaquely.
 * @param headers extra request headers for this call.
 * @param retry retry policy; [RetryPolicy.None] because providers already retry inside their transport.
 */
public suspend fun embed(
    model: EmbeddingModel,
    value: String,
    providerOptions: ProviderOptions? = null,
    headers: Map<String, String>? = null,
    retry: RetryPolicy = RetryPolicy.None,
): EmbedResult {
    val result = withRetry(retry) {
        model.doEmbed(EmbeddingCallOptions(listOf(value), providerOptions, headers))
    }
    // A 200 with no vector in it is a malformed response, not an empty answer: the reference's error
    // and message, so a caller matching on either sees the same failure from every SDK.
    val embedding = result.embeddings.firstOrNull()
        ?: throw InvalidResponseDataError("No embedding generated.", data = result.embeddings.toJson())
    return EmbedResult(
        value = value,
        embedding = embedding,
        usage = result.usage,
        warnings = result.warnings,
        providerMetadata = result.providerMetadata,
        response = result.response,
    )
}

/**
 * Embeds many values, splitting the batch to fit what the model accepts in one call.
 *
 * [EmbeddingModel.maxEmbeddingsPerCall], [EmbeddingModel.maxInputBytesPerCall] and
 * [EmbeddingModel.supportsParallelCalls] are declared on the contract and, until this existed, read by
 * nothing: every caller either chunked a batch by hand — and every caller's chunker is a different one —
 * or handed a vendor more values than its endpoint takes and got a 400 naming a limit the model could
 * have been asked for. All three are read once, here, which also makes
 * `TooManyEmbeddingValuesForCallError` structurally unreachable: no chunk can exceed a limit the
 * chunking derived from.
 *
 * The split honours BOTH ceilings — the value count and the summed UTF-8 byte budget — because they
 * fail independently: three long documents are under a 32-value cap and over a byte cap, and chunking
 * by count alone hands that batch to the vendor as a 400. A single value larger than the whole byte
 * budget still goes out alone rather than being split or dropped — a value is the unit of embedding,
 * and the vendor's own error for it names the real problem.
 *
 * Chunks are dispatched concurrently only when the model says its credentials tolerate it — several
 * vendors serialize per key and answer a fan-out with 429s — and the fan-out is bounded even then,
 * because a thousand values against a 32-value ceiling is 32 simultaneous requests.
 *
 * ```kotlin
 * val model = VoyageProvider(client, apiKey).embeddingModel("voyage-3.5")
 * val result = embedMany(model, EmbeddingCallOptions(values = documents))
 * val indexed = documents.zip(result.embeddings)
 * ```
 *
 * @param model the embedding model to call; its ceilings and parallelism declaration drive the chunking.
 * @param options the values to embed, plus provider options and headers forwarded to every chunk's call.
 * @param retry retry policy, applied per chunk; [RetryPolicy.None] because providers already retry
 *   inside their transport.
 */
public suspend fun embedMany(
    model: EmbeddingModel,
    options: EmbeddingCallOptions,
    retry: RetryPolicy = RetryPolicy.None,
): EmbedManyResult {
    val values = options.values
    if (values.isEmpty()) return EmbedManyResult(values = emptyList(), embeddings = emptyList())

    val chunks = splitByEmbeddingLimits(values, model.maxEmbeddingsPerCall(), model.maxInputBytesPerCall())
    val width = if (model.supportsParallelCalls()) MAX_PARALLEL_CALLS else 1

    val results = chunks.chunked(width).flatMap { wave ->
        coroutineScope {
            wave.map { chunk ->
                async {
                    withRetry(retry) { model.doEmbed(options.copy(values = chunk)) }
                        .also { checkEmbeddingCount(it.embeddings, chunk) }
                }
            }.awaitAll()
        }
    }

    // Chunk order is call order, and a vendor returns vectors in the order it was given values, so
    // concatenating in wave order restores the caller's own ordering. A caller that cannot pair a
    // vector back to its value has nothing.
    return EmbedManyResult(
        values = values,
        embeddings = results.flatMap { it.embeddings },
        usage = results.mapNotNull { it.usage }.takeIf { it.isNotEmpty() }?.sum(),
        warnings = results.flatMap { it.warnings }.distinct(),
        providerMetadata = results.map { it.providerMetadata }.mergeProviderMetadata(),
        responses = results.map { it.response },
    )
}

/**
 * Cosine similarity between two vectors — the standard relevance score over embeddings.
 *
 * A zero vector has no direction, so its similarity to anything is undefined rather than zero; it is
 * returned as 0.0 because the alternative is a NaN that propagates silently through a ranking and
 * sorts wherever the comparator happens to put it.
 *
 * ```kotlin
 * val query = embed(model, "refund policy").embedding
 * val ranked = documents.zip(vectors)
 *     .sortedByDescending { (_, vector) -> cosineSimilarity(query, vector) }
 * ```
 *
 * @param first one vector.
 * @param second the other; must have the same length, or [InvalidArgumentError] is thrown.
 */
public fun cosineSimilarity(first: Embedding, second: Embedding): Double {
    if (first.size != second.size) {
        throw InvalidArgumentError(
            "Vectors must have the same length: ${first.size} vs ${second.size}.",
            "second",
        )
    }
    var dot = 0.0
    var firstMagnitude = 0.0
    var secondMagnitude = 0.0
    for (index in first.indices) {
        dot += first[index] * second[index]
        firstMagnitude += first[index] * first[index]
        secondMagnitude += second[index] * second[index]
    }
    val magnitude = sqrt(firstMagnitude) * sqrt(secondMagnitude)
    return if (magnitude == 0.0) 0.0 else dot / magnitude
}

/**
 * Greedy split under both ceilings, preserving order.
 *
 * A chunk closes when adding the next value would cross either limit; a value bigger than the whole
 * byte budget still becomes its own chunk — see [embedMany]. No ceiling at all means the model
 * documents none, so the whole batch is one call — not a guess at a safe chunk size, which would turn
 * one request into many for every provider that has no limit.
 */
private fun splitByEmbeddingLimits(
    values: List<String>,
    maxPerCall: Int?,
    maxBytesPerCall: Int?,
): List<List<String>> {
    if (maxPerCall != null && maxPerCall <= 0) {
        throw InvalidArgumentError("maxEmbeddingsPerCall must be greater than 0.", "maxEmbeddingsPerCall")
    }
    if (maxBytesPerCall != null && maxBytesPerCall <= 0) {
        throw InvalidArgumentError("maxInputBytesPerCall must be greater than 0.", "maxInputBytesPerCall")
    }
    if (maxPerCall == null && maxBytesPerCall == null) return listOf(values)

    val chunks = mutableListOf<List<String>>()
    var chunk = mutableListOf<String>()
    var chunkBytes = 0

    for (value in values) {
        val valueBytes = value.encodeToByteArray().size
        val crossesCount = maxPerCall != null && chunk.size >= maxPerCall
        val crossesBytes = maxBytesPerCall != null && chunkBytes + valueBytes > maxBytesPerCall
        if (chunk.isNotEmpty() && (crossesCount || crossesBytes)) {
            chunks += chunk
            chunk = mutableListOf()
            chunkBytes = 0
        }
        chunk += value
        chunkBytes += valueBytes
    }
    chunks += chunk
    return chunks
}

/**
 * One vector per value, or the response is rejected — checked per call, before the chunks are joined.
 *
 * A vendor that answers a 20-value chunk with 19 vectors does not fail; it shifts every vector after the
 * gap onto the wrong value, and the caller's index-aligned pairing is silently wrong from there on.
 */
private fun checkEmbeddingCount(embeddings: List<Embedding>, values: List<String>) {
    if (embeddings.size != values.size) {
        throw InvalidResponseDataError(
            "Expected ${values.size} embeddings, but received ${embeddings.size}.",
            data = embeddings.toJson(),
        )
    }
}

/** The offending vectors, verbatim, as the error's `data`. */
private fun List<Embedding>.toJson(): JsonArray = JsonArray(map { vector -> JsonArray(vector.map(::JsonPrimitive)) })

/**
 * Enough concurrency to make a large batch fast, low enough that a fan-out is not itself the reason a
 * vendor rate-limits the run.
 */
private const val MAX_PARALLEL_CALLS = 8
