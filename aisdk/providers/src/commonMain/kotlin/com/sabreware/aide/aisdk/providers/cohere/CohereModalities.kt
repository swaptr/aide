package com.sabreware.aide.aisdk.providers.cohere

import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.EmbeddingResult
import com.sabreware.aide.aisdk.RerankingCallOptions
import com.sabreware.aide.aisdk.RerankingModel
import com.sabreware.aide.aisdk.RerankingResult
import com.sabreware.aide.aisdk.TooManyEmbeddingValuesForCallError
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Cohere embeddings, over `/embed`.
 *
 * Not the OpenAI shape at any point: the inputs are `texts` rather than `input`, and the vectors come
 * back under `embeddings.float` rather than as a `data[]` of objects carrying their own index. The
 * `embedding_types` field is what selects that key, and it is pinned rather than exposed — the contract
 * carries `List<Double>`, so asking for `int8` or `binary` returns a payload this type cannot hold.
 *
 * `input_type` is required by every v3 model and has no server-side default. It defaults to
 * `search_query` here because that is the asymmetric side a caller reaches for first; a corpus being
 * indexed passes `search_document`, and getting the pair backwards silently degrades every retrieval
 * score without failing anything.
 */
internal class CohereEmbeddingModel(
    override val modelId: String,
    private val http: ProviderHttp,
    /** The complete `/embed` endpoint. */
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
) : EmbeddingModel {

    override val provider: String = COHERE_PROVIDER_ID

    override suspend fun maxEmbeddingsPerCall(): Int = MAX_PER_CALL

    override suspend fun doEmbed(options: EmbeddingCallOptions): EmbeddingResult {
        if (options.values.size > MAX_PER_CALL) {
            // Caught here so the caller learns the ceiling, rather than from a 400 about token counts.
            throw TooManyEmbeddingValuesForCallError(provider, modelId, MAX_PER_CALL, options.values)
        }
        val cohere = options.providerOptions?.get(COHERE_PROVIDER_ID)

        val body = buildJsonObject {
            put("model", modelId)
            put("texts", buildJsonArray { options.values.forEach { add(JsonPrimitive(it)) } })
            // Pinned rather than exposed: `int8`, `binary` and friends come back under their own key,
            // so a response read for `embeddings.float` after asking for one of them is an empty list
            // rather than an error — and the contract carries `List<Double>` regardless.
            put("embedding_types", buildJsonArray { add(JsonPrimitive("float")) })
            put(
                "input_type",
                (cohere?.get("inputType") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: DEFAULT_INPUT_TYPE,
            )
            (cohere?.get("truncate") as? JsonPrimitive)?.takeIf { it.isString }
                ?.let { put("truncate", it.content) }
            // `embed-v4.0` and later can return a shorter vector; earlier models reject the field, so
            // it goes out only when the caller asked for one.
            (cohere?.get("outputDimension") as? JsonPrimitive)?.intOrNull
                ?.let { put("output_dimension", it) }
        }

        val result = http.postJson(url, body, combineHeaders(headers, options.headers))
        val response = result.value.jsonObject

        return EmbeddingResult(
            embeddings = response["embeddings"]?.jsonObject?.get("float")?.jsonArray.orEmpty()
                .map { vector -> vector.jsonArray.map { it.jsonPrimitive.content.toDouble() } },
            usage = response["meta"]?.jsonObject?.get("billed_units")?.jsonObject
                ?.get("input_tokens")?.jsonPrimitive?.intOrNull,
            response = result.modalityResponse(
                modelId = modelId,
                id = response["id"]?.jsonPrimitive?.contentOrNull(),
            ),
        )
    }

    private companion object {
        /** Cohere's documented per-call ceiling. */
        const val MAX_PER_CALL = 96
        const val DEFAULT_INPUT_TYPE = "search_query"
    }
}

/**
 * Cohere reranking, over `/rerank`.
 *
 * The canonical implementation of the modality: `rerank-v3.5` is what a retrieval pipeline is tuned
 * against, so this is the one a caller substitutes the others for rather than the other way round.
 */
internal class CohereRerankingModel(
    override val modelId: String,
    private val http: ProviderHttp,
    /** The complete `/rerank` endpoint. */
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
) : RerankingModel {

    override val provider: String = COHERE_PROVIDER_ID

    override suspend fun doRerank(options: RerankingCallOptions): RerankingResult {
        val warnings = mutableListOf<Warning>()
        val cohere = options.providerOptions?.get(COHERE_PROVIDER_ID)

        val documents = when (val docs = options.documents) {
            is RerankingCallOptions.Documents.Text -> docs.values
            // This wire takes strings only. Serializing the record is the only way to send it, and the
            // punctuation of the JSON gets scored alongside the content — which is a real difference in
            // the ranking, not a formatting detail, so the caller is told.
            is RerankingCallOptions.Documents.Objects -> {
                warnings += Warning.Compatibility(
                    feature = "object documents",
                    details = "Cohere ranks text only; each document was serialized to JSON.",
                )
                docs.values.map { it.toString() }
            }
        }

        val body = buildJsonObject {
            put("model", modelId)
            put("query", options.query)
            put("documents", buildJsonArray { documents.forEach { add(JsonPrimitive(it)) } })
            options.topN?.let { put("top_n", it) }
            (cohere?.get("maxTokensPerDoc") as? JsonPrimitive)?.intOrNull
                ?.let { put("max_tokens_per_doc", it) }
            (cohere?.get("priority") as? JsonPrimitive)?.intOrNull?.let { put("priority", it) }
        }

        val result = http.postJson(url, body, combineHeaders(headers, options.headers))
        val response = result.value.jsonObject

        return RerankingResult(
            // Cohere returns the results already ordered by score, and the index is into the documents
            // the caller submitted — which is the whole answer, so re-sorting here would only risk
            // disagreeing with it.
            ranking = response["results"]?.jsonArray.orEmpty().map { entry ->
                RerankingResult.Rank(
                    index = entry.jsonObject["index"]?.jsonPrimitive?.intOrNull ?: 0,
                    relevanceScore = entry.jsonObject["relevance_score"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                )
            },
            warnings = warnings,
            response = result.modalityResponse(
                modelId = modelId,
                id = response["id"]?.jsonPrimitive?.contentOrNull(),
            ),
        )
    }
}

private fun JsonPrimitive.contentOrNull(): String? = takeIf { it.isString }?.content
