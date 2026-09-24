package com.sabreware.aide.aisdk.providers.voyage

import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.EmbeddingResult
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.RerankingCallOptions
import com.sabreware.aide.aisdk.RerankingModel
import com.sabreware.aide.aisdk.RerankingResult
import com.sabreware.aide.aisdk.TooManyEmbeddingValuesForCallError
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The provider id, and the namespace Voyage payloads and options file under. */
public const val VOYAGE_PROVIDER_ID: String = "voyage"

/**
 * Voyage AI: embeddings and reranking, the two halves of a retrieval pipeline.
 *
 * Close to the OpenAI shape but not the same, and the differences are the ones that cost quality
 * silently rather than loudly:
 *
 * - **`input_type`** distinguishes a query from a document. Voyage's retrieval models embed the two
 *   differently on purpose, so omitting it degrades recall with no error to notice. It is a per-CALL
 *   option, not provider configuration: one RAG pipeline embeds its corpus as `document` and every
 *   search as `query` against the same model.
 * - **Reranking spells the cut-off `top_k`**, where every other vendor on this wire spells it `top_n`.
 *
 * Errors arrive as `{"detail": "…"}`, which `defaultErrorMessage` already reads, so this provider needs
 * no [com.sabreware.aide.aisdk.util.ProviderErrorStructure] of its own.
 */
public class VoyageProvider(
    client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : Provider {

    override val providerId: String = VOYAGE_PROVIDER_ID

    private val http = ProviderHttp(client)

    private fun headers(): Map<String, String> =
        mapOf("Authorization" to "Bearer $apiKey") + extraHeaders

    override fun embeddingModel(modelId: String): EmbeddingModel = VoyageEmbeddingModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = headers(),
    )

    override fun rerankingModel(modelId: String): RerankingModel = VoyageRerankingModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = headers(),
    )

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.voyageai.com/v1"
    }
}

internal class VoyageEmbeddingModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : EmbeddingModel {

    override val provider: String = VOYAGE_PROVIDER_ID

    override suspend fun maxEmbeddingsPerCall(): Int = MAX_PER_CALL

    override suspend fun supportsParallelCalls(): Boolean = true

    override suspend fun doEmbed(options: EmbeddingCallOptions): EmbeddingResult {
        if (options.values.size > MAX_PER_CALL) {
            // Raised before the request so the caller learns the actual ceiling, rather than reading it
            // out of a 400 that talks about token counts.
            throw TooManyEmbeddingValuesForCallError(provider, modelId, MAX_PER_CALL, options.values)
        }
        val voyage = options.providerOptions?.get(VOYAGE_PROVIDER_ID)

        val body = buildJsonObject {
            put("model", modelId)
            put("input", buildJsonArray { options.values.forEach { add(JsonPrimitive(it)) } })
            voyage?.string("inputType")?.let { put("input_type", it) }
            voyage?.bool("truncation")?.let { put("truncation", it) }
            // Matryoshka truncation and quantization: a caller storing millions of vectors trades recall
            // for index size here, and both are per-call because both are properties of the index.
            voyage?.int("outputDimension")?.let { put("output_dimension", it) }
            voyage?.string("outputDtype")?.let { put("output_dtype", it) }
        }

        val result = http.postJson(
            url = "$baseUrl/embeddings",
            body = body,
            headers = combineHeaders(headers, options.headers),
        )
        val response = result.value.jsonObject

        return EmbeddingResult(
            embeddings = response["data"]?.jsonArray.orEmpty()
                // Ordering is not promised, and a vector matched to the wrong input is undetectable.
                .sortedBy { it.jsonObject["index"]?.jsonPrimitive?.intOrNull ?: 0 }
                .map { entry -> entry.jsonObject.embedding() },
            usage = response["usage"]?.jsonObject?.get("total_tokens")?.jsonPrimitive?.intOrNull,
            // No raw body: an embedding response is megabytes of floats, and re-serializing it for a
            // diagnostic field costs a second copy of every vector on every call.
            response = result.modalityResponse(modelId = modelId),
        )
    }

    private companion object {
        /** Voyage's documented per-request ceiling. */
        const val MAX_PER_CALL = 128
    }
}

internal class VoyageRerankingModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : RerankingModel {

    override val provider: String = VOYAGE_PROVIDER_ID

    override suspend fun doRerank(options: RerankingCallOptions): RerankingResult {
        val voyage = options.providerOptions?.get(VOYAGE_PROVIDER_ID)
        val warnings = mutableListOf<Warning>()

        val documents = when (val docs = options.documents) {
            is RerankingCallOptions.Documents.Text -> docs.values
            is RerankingCallOptions.Documents.Objects -> {
                // Voyage ranks strings only. Saying so is the point: the caller's field structure is gone
                // by the time the model sees it, and a silent JSON dump ranks the punctuation too.
                warnings += Warning.Compatibility(
                    feature = "object documents",
                    details = "Object documents are converted to strings.",
                )
                docs.values.map { ProviderJson.encodeToString(JsonElement.serializer(), it) }
            }
        }

        val body = buildJsonObject {
            put("query", options.query)
            put("documents", buildJsonArray { documents.forEach { add(JsonPrimitive(it)) } })
            put("model", modelId)
            // `top_k` here, `top_n` on every other reranking wire.
            options.topN?.let { put("top_k", it) }
            voyage?.bool("returnDocuments")?.let { put("return_documents", it) }
            voyage?.bool("truncation")?.let { put("truncation", it) }
        }

        val result = http.postJson(
            url = "$baseUrl/rerank",
            body = body,
            headers = combineHeaders(headers, options.headers),
        )
        val response = result.value.jsonObject

        return RerankingResult(
            // Voyage returns the ranking already ordered; the index is into the submitted documents.
            ranking = response["data"]?.jsonArray.orEmpty().map { entry ->
                RerankingResult.Rank(
                    index = entry.jsonObject["index"]?.jsonPrimitive?.intOrNull ?: 0,
                    relevanceScore = entry.jsonObject["relevance_score"]?.jsonPrimitive?.content
                        ?.toDoubleOrNull() ?: 0.0,
                )
            },
            warnings = warnings,
            response = result.modalityResponse(modelId = modelId),
        )
    }
}

private fun JsonObject.embedding(): List<Double> =
    this["embedding"]?.jsonArray?.map { it.jsonPrimitive.content.toDouble() }.orEmpty()

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
