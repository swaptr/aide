package com.sabreware.aide.aisdk.providers.bedrock

import com.sabreware.aide.aisdk.Embedding
import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.EmbeddingResult
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.TooManyEmbeddingValuesForCallError
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optElement
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.AwsCredentials
import com.sabreware.aide.aisdk.util.HttpResult
import com.sabreware.aide.aisdk.util.ProviderHttp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Bedrock embeddings, over `InvokeModel`.
 *
 * There is no shared embeddings wire on Bedrock: each family keeps its own request AND response shape,
 * so the model id picks a dialect —
 *
 * - **Titan** takes ONE `inputText` per call and answers `{embedding, inputTextTokenCount}`.
 * - **Cohere** takes up to 96 `texts` and REQUIRES `input_type` — omitting it does not degrade the
 *   call, it makes Bedrock try the other schema branches and reject the request outright. Its v4
 *   models additionally nest the vectors under `embeddings.float`.
 * - **Nova** takes one value inside `singleEmbeddingParams`, with its own purpose/dimension knobs.
 *
 * An application inference profile ARN names no family, so `providerOptions["amazon-bedrock"].
 * modelFamily` overrides the detection — an options field rather than the reference's constructor
 * setting, because our [EmbeddingModel] is built from a bare model id.
 *
 * Token counts come from wherever each family reports them: Titan's body, Nova's body, or — for Cohere,
 * which reports nothing in-body — the `x-amzn-bedrock-input-token-count` response header.
 */
internal class BedrockEmbeddingModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val credentials: () -> AwsCredentials,
    private val region: String,
    private val now: () -> Long,
    /** The runtime endpoint; defaults to the region's own, and a host with an override passes it. */
    private val baseUrl: String = bedrockBaseUrl(region),
) : EmbeddingModel {

    override val provider: String = BEDROCK_PROVIDER_ID

    override suspend fun maxEmbeddingsPerCall(): Int? =
        if (detectFamily(null) == Family.Cohere) COHERE_MAX else 1

    override suspend fun doEmbed(options: EmbeddingCallOptions): EmbeddingResult {
        val vendor = options.providerOptions.bedrockEntry()
        val family = detectFamily(vendor)
        val max = if (family == Family.Cohere) COHERE_MAX else 1
        if (options.values.size > max) {
            throw TooManyEmbeddingValuesForCallError(
                provider = provider,
                modelId = modelId,
                maxEmbeddingsPerCall = max,
                values = options.values,
            )
        }

        val body = when (family) {
            Family.Nova -> novaBody(options.values, vendor)
            Family.Cohere -> cohereBody(options.values, vendor)
            Family.Titan -> titanBody(options.values, vendor)
        }
        val result = invoke(body, options.headers)
        val response = result.value

        return EmbeddingResult(
            embeddings = extractEmbeddings(response),
            usage = response.optInt("inputTextTokenCount")
                ?: response.optInt("inputTokenCount")
                ?: result.headers["x-amzn-bedrock-input-token-count"]?.toIntOrNull(),
            response = result.modalityResponse(modelId = modelId),
            request = RequestInfo(result.requestBody),
        )
    }

    /** Signed single-shot `InvokeModel` call; the response is plain JSON, buffered off the stream. */
    private suspend fun invoke(body: JsonObject, extraHeaders: Map<String, String>?): HttpResult<JsonObject> =
        http.bedrockSignedPost(
            url = bedrockInvokeUrl(baseUrl, modelId),
            body = body,
            extraHeaders = extraHeaders,
            credentials = credentials(),
            region = region,
            timestampMillis = now(),
        )

    private fun titanBody(values: List<String>, vendor: JsonObject?): JsonObject = buildJsonObject {
        put("inputText", values.first())
        vendor?.optInt("dimensions")?.let { put("dimensions", it) }
        vendor?.optElement("normalize")?.let { put("normalize", it) }
    }

    private fun cohereBody(values: List<String>, vendor: JsonObject?): JsonObject = buildJsonObject {
        // Required: without it Bedrock attempts the other schema branches and rejects the request.
        put("input_type", vendor?.optString("inputType") ?: "search_query")
        put("texts", JsonArray(values.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        vendor?.optString("truncate")?.let { put("truncate", it) }
        vendor?.optInt("outputDimension")?.let { put("output_dimension", it) }
    }

    private fun novaBody(values: List<String>, vendor: JsonObject?): JsonObject = buildJsonObject {
        put("taskType", "SINGLE_EMBEDDING")
        putJsonObject("singleEmbeddingParams") {
            put("embeddingPurpose", vendor?.optString("embeddingPurpose") ?: "GENERIC_INDEX")
            put("embeddingDimension", vendor?.optInt("embeddingDimension") ?: NOVA_DEFAULT_DIMENSION)
            putJsonObject("text") {
                put("truncationMode", vendor?.optString("truncate") ?: "END")
                put("value", values.first())
            }
        }
    }

    /** The four response shapes, told apart structurally — the id that picked the request may be an ARN. */
    private fun extractEmbeddings(response: JsonObject): List<Embedding> {
        response.optArray("embedding")?.let { return listOf(it.toVector()) }
        when (val embeddings = response["embeddings"]) {
            is JsonArray -> {
                val first = embeddings.firstOrNull()
                return if (first is JsonObject && "embeddingType" in first) {
                    // Nova wraps the vector in a typed object; one value in, one vector out.
                    listOfNotNull(first.optArray("embedding")?.toVector())
                } else {
                    embeddings.map { it.jsonArray.toVector() }
                }
            }
            is JsonObject -> embeddings.optArray("float")?.let { nested ->
                return nested.map { it.jsonArray.toVector() }
            }
            else -> Unit
        }
        return emptyList()
    }

    private fun JsonArray.toVector(): Embedding = mapNotNull { it.jsonPrimitive.doubleOrNull }

    private enum class Family { Titan, Cohere, Nova }

    private fun detectFamily(vendor: JsonObject?): Family =
        when (vendor?.optString("modelFamily")) {
            "titan" -> Family.Titan
            "cohere" -> Family.Cohere
            "nova" -> Family.Nova
            else -> when {
                // `includes`, not a prefix: cross-region profiles carry `us.`/`global.` in front.
                "amazon.nova-" in modelId && "embed" in modelId -> Family.Nova
                "cohere.embed-" in modelId -> Family.Cohere
                else -> Family.Titan
            }
        }

    private companion object {
        const val COHERE_MAX = 96
        const val NOVA_DEFAULT_DIMENSION = 1024
    }
}
