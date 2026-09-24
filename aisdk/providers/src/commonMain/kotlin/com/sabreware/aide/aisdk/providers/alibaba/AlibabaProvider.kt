package com.sabreware.aide.aisdk.providers.alibaba

import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.EmbeddingResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.TooManyEmbeddingValuesForCallError
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleCapabilities
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleProvider
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The provider id, and the namespace Alibaba options and metadata file under. */
public const val ALIBABA_PROVIDER_ID: String = "alibaba"

/**
 * Alibaba Cloud (DashScope): chat, text embeddings, and video, across the two hosts DashScope splits
 * them over.
 *
 * Chat has an OpenAI-compatible mode and is wrapped rather than re-implemented — the `Vendors.alibaba`
 * row this replaces is gone, because the wrap adds what a table row could not: `cache_creation_input_tokens`
 * accounting (DashScope counts cache reads AND writes inside `prompt_tokens`, so the no-cache share is a
 * two-way subtraction), the per-message `cache_control` breakpoints with their four-marker ceiling,
 * `enable_thinking`, and `top_k` — which DashScope accepts and the shared wire's default gates off.
 *
 * The embedding endpoint has no compatible mode at all: a different host path, a nested `input.texts`
 * body, a `parameters` block, and results under `output.embeddings`. Pointing an OpenAI-compatible
 * embedding model at it produces a 404, not a degraded result.
 *
 * Two things the wire gets right that the neutral contract cannot express, and one it cannot:
 *
 * - `text_type` is Alibaba's `input_type`: query and document are embedded differently for asymmetric
 *   retrieval, and the default is `document`.
 * - `output_type: "dense&sparse"` returns a sparse vector alongside the dense one, for hybrid search.
 *   The sparse half rides in [ProviderMetadata] because a sparse vector is a list of (index, value)
 *   pairs, not the dense `List<Double>` an embedding is.
 * - `output_type: "sparse"` returns NO dense vector, so there is nothing to put in
 *   [EmbeddingResult.embeddings]. It is refused before the request rather than returning empty lists
 *   that a caller would index and then never retrieve anything from.
 *
 * The embedding endpoint answers errors flat — `{"code": …, "message": …}` — where the chat endpoint
 * wraps them in `error`. `defaultErrorMessage` reads both.
 */
public class AlibabaProvider(
    client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_EMBEDDING_BASE_URL,
    private val extraHeaders: Map<String, String> = emptyMap(),
    /**
     * Where the CHAT models live, which is a different service on the same domain.
     *
     * Embeddings and video speak DashScope's native API; chat speaks the OpenAI wire under
     * `compatible-mode`. One provider serves both so a caller needs one object per vendor rather than
     * two, but the two halves cannot share a base URL.
     */
    private val chatBaseUrl: String = DEFAULT_CHAT_BASE_URL,
) : Provider {

    override val providerId: String = ALIBABA_PROVIDER_ID

    private val http = ProviderHttp(client)

    private val compat = OpenAICompatibleProvider(
        client = client,
        providerId = ALIBABA_PROVIDER_ID,
        baseUrl = chatBaseUrl,
        apiKey = apiKey,
        extraHeaders = extraHeaders,
        // Model Studio documents top_k as a supported chat parameter; the shared default gates it off
        // because OpenAI answers 400 to an unrecognized field.
        capabilitiesFor = { OpenAICompatibleCapabilities(supportsTopK = true) },
        convertUsage = ::alibabaUsage,
    )

    /**
     * Qwen chat, over Model Studio's OpenAI-compatible endpoint — see [AlibabaLanguageModel] for the
     * four places it departs from the shared wire.
     */
    override fun languageModel(modelId: String): LanguageModel =
        AlibabaLanguageModel(requireNotNull(compat.languageModel(modelId)))

    override fun embeddingModel(modelId: String): EmbeddingModel = AlibabaEmbeddingModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = mapOf("Authorization" to "Bearer $apiKey") + extraHeaders,
    )

    /** Wan video, on the same native DashScope root the embeddings use — see [AlibabaVideoModel]. */
    override fun videoModel(modelId: String): VideoModel = AlibabaVideoModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = mapOf("Authorization" to "Bearer $apiKey") + extraHeaders,
    )

    public companion object {
        /**
         * DashScope's native API root — NOT the `compatible-mode/v1` host the chat models use.
         *
         * The two are different services on the same domain, and the international endpoint is the
         * default because the mainland one (`dashscope.aliyuncs.com`) rejects keys issued for it.
         */
        public const val DEFAULT_EMBEDDING_BASE_URL: String = "https://dashscope-intl.aliyuncs.com/api/v1"

        /**
         * Model Studio's OpenAI-compatible chat root — the `compatible-mode` service, not the native
         * one the other two modalities use.
         */
        public const val DEFAULT_CHAT_BASE_URL: String =
            "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
    }
}

internal class AlibabaEmbeddingModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : EmbeddingModel {

    override val provider: String = ALIBABA_PROVIDER_ID

    override suspend fun maxEmbeddingsPerCall(): Int = MAX_PER_CALL

    override suspend fun supportsParallelCalls(): Boolean = false

    override suspend fun doEmbed(options: EmbeddingCallOptions): EmbeddingResult {
        val alibaba = options.providerOptions?.get(ALIBABA_PROVIDER_ID)
        val outputType = alibaba?.string("outputType")

        if (outputType == SPARSE_ONLY) {
            throw UnsupportedFunctionalityError(
                functionality = "Alibaba embedding outputType 'sparse'",
                message = "Alibaba embedding outputType 'sparse' returns no dense vectors, which an " +
                    "embedding result cannot represent. Use 'dense' or 'dense&sparse'.",
            )
        }
        if (options.values.size > MAX_PER_CALL) {
            throw TooManyEmbeddingValuesForCallError(provider, modelId, MAX_PER_CALL, options.values)
        }

        val body = buildJsonObject {
            put("model", modelId)
            putJsonObject("input") {
                putJsonArray("texts") { options.values.forEach { add(JsonPrimitive(it)) } }
            }
            // Always present, even when empty: DashScope treats an absent `parameters` and an empty one
            // the same, and sending it unconditionally keeps one body shape to reason about.
            putJsonObject("parameters") {
                alibaba?.string("textType")?.let { put("text_type", it) }
                alibaba?.int("dimension")?.let { put("dimension", it) }
                outputType?.let { put("output_type", it) }
            }
        }

        val result = http.postJson(
            url = "$baseUrl/services/embeddings/text-embedding/text-embedding",
            body = body,
            headers = combineHeaders(headers, options.headers),
        )
        val response = result.value.jsonObject

        val entries = response["output"]?.jsonObject?.get("embeddings")?.jsonArray.orEmpty()
            .map { it.jsonObject }
            // DashScope returns the batch out of order under load, and a vector matched to the wrong
            // input is undetectable downstream — it just retrieves the wrong document forever.
            .sortedBy { it.int("text_index") ?: 0 }

        return EmbeddingResult(
            embeddings = entries.map { entry ->
                entry["embedding"]?.jsonArray?.map { it.jsonPrimitive.content.toDouble() }.orEmpty()
            },
            usage = response["usage"]?.jsonObject?.get("total_tokens")?.jsonPrimitive?.intOrNull,
            providerMetadata = entries.sparseMetadata(),
            response = result.modalityResponse(modelId = modelId),
        )
    }

    private companion object {
        const val SPARSE_ONLY = "sparse"

        /** DashScope's documented batch ceiling. */
        const val MAX_PER_CALL = 10
    }
}

/**
 * The sparse half of a hybrid embedding, or null when the model returned none.
 *
 * Carried verbatim rather than reshaped: the entries are `{index, value, token}`, and `token` is the
 * piece a caller needs to explain WHY a document matched — the thing dense retrieval cannot tell them.
 */
private fun List<JsonObject>.sparseMetadata(): ProviderMetadata? {
    val sparse = mapNotNull { entry ->
        val vector = entry["sparse_embedding"]?.jsonArray ?: return@mapNotNull null
        buildJsonObject {
            put("textIndex", entry.int("text_index") ?: 0)
            put("sparseEmbedding", vector)
        }
    }
    if (sparse.isEmpty()) return null
    return mapOf(
        ALIBABA_PROVIDER_ID to buildJsonObject {
            put("sparseEmbeddings", buildJsonArray { sparse.forEach { add(it) } })
        },
    )
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
