package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.EmbeddingResult
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.TooManyEmbeddingValuesForCallError
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Gemini embeddings.
 *
 * Two endpoints, chosen by batch size: one value goes to `:embedContent`, several to
 * `:batchEmbedContents`. The split is Google's, not ours — the batch endpoint wraps each value in its
 * own request object carrying the model name AGAIN, and the single endpoint rejects that envelope.
 *
 * The options that decide retrieval quality ride `providerOptions["google"]`:
 *
 * - **`taskType`** tells Gemini whether it is embedding a query or a document; the retrieval models
 *   embed the two differently on purpose, so omitting it costs recall with no error to notice.
 * - **`outputDimensionality`** truncates the vector server-side — the caller trading recall for index
 *   size, per call because it is a property of the index being built.
 * - **`content`** carries per-value multimodal parts (an image, a PDF page) in Google's own wire shape,
 *   index-aligned with the submitted values; entries are passed through verbatim because their shape is
 *   Google's to define, and `null` marks a text-only entry.
 */
internal class GoogleEmbeddingModel(
    override val modelId: String,
    http: ProviderHttp,
    private val baseUrl: String = GOOGLE_DEFAULT_BASE_URL,
    private val headers: suspend () -> Map<String, String> = { emptyMap() },
) : EmbeddingModel {

    override val provider: String = GOOGLE_PROVIDER_ID

    private val http = http.withErrorStructure(GoogleErrorStructure)

    override suspend fun maxEmbeddingsPerCall(): Int = MAX_PER_CALL

    override suspend fun doEmbed(options: EmbeddingCallOptions): EmbeddingResult {
        if (options.values.size > MAX_PER_CALL) {
            // Raised before the request so the caller learns the actual ceiling, rather than reading it
            // out of a 400 that talks about payload sizes.
            throw TooManyEmbeddingValuesForCallError(provider, modelId, MAX_PER_CALL, options.values)
        }

        val vendor = options.providerOptions?.forProvider(GOOGLE_PROVIDER_ID)
        val multimodal = vendor?.optArray("content")
        if (multimodal != null && multimodal.size != options.values.size) {
            throw InvalidArgumentError(
                message = "The number of multimodal content entries (${multimodal.size}) " +
                    "must match the number of values (${options.values.size}).",
                argument = "content",
            )
        }

        val requestHeaders = combineHeaders(headers(), options.headers)
        return if (options.values.size == 1) {
            embedSingle(options, vendor, multimodal, requestHeaders)
        } else {
            embedBatch(options, vendor, multimodal, requestHeaders)
        }
    }

    private suspend fun embedSingle(
        options: EmbeddingCallOptions,
        vendor: JsonObject?,
        multimodal: JsonArray?,
        requestHeaders: Map<String, String>,
    ): EmbeddingResult {
        val body = buildJsonObject {
            put("model", "models/$modelId")
            // No role on the single endpoint; the batch endpoint's per-request content carries one.
            putJsonObject("content") { put("parts", parts(options.values[0], multimodal?.get(0))) }
            putEmbedOptions(vendor)
        }

        val result = http.postJson("$baseUrl/models/$modelId:embedContent", body, requestHeaders)
        val embedding = result.value.jsonObject["embedding"]?.jsonObject.vector()

        return EmbeddingResult(
            embeddings = listOf(embedding),
            response = result.modalityResponse(modelId = modelId),
            request = result.requestInfo(),
        )
    }

    private suspend fun embedBatch(
        options: EmbeddingCallOptions,
        vendor: JsonObject?,
        multimodal: JsonArray?,
        requestHeaders: Map<String, String>,
    ): EmbeddingResult {
        val body = buildJsonObject {
            put(
                "requests",
                buildJsonArray {
                    options.values.forEachIndexed { index, value ->
                        addJsonObject {
                            put("model", "models/$modelId")
                            putJsonObject("content") {
                                put("role", "user")
                                put("parts", parts(value, multimodal?.get(index)))
                            }
                            putEmbedOptions(vendor)
                        }
                    }
                },
            )
        }

        val result = http.postJson("$baseUrl/models/$modelId:batchEmbedContents", body, requestHeaders)

        return EmbeddingResult(
            // Google returns vectors in submission order; there is no index field to re-sort by.
            embeddings = result.value.jsonObject["embeddings"]?.jsonArray.orEmpty()
                .map { (it as? JsonObject).vector() },
            response = result.modalityResponse(modelId = modelId),
            request = result.requestInfo(),
        )
    }

    private fun JsonObjectBuilder.putEmbedOptions(vendor: JsonObject?) {
        vendor?.optInt("outputDimensionality")?.let { put("outputDimensionality", it) }
        vendor?.optString("taskType")?.let { put("taskType", it) }
    }

    /**
     * The parts for one value: its text, then any multimodal parts the caller supplied for that index.
     *
     * A multimodal entry's parts go through verbatim — `inlineData`, `fileData` and `text` parts are
     * Google's own wire shapes, and re-validating them here would reject a shape Google adds tomorrow.
     * With parts present an empty text is skipped rather than sent as `{"text":""}`; without them the
     * text part always goes, empty or not, because a content object must carry at least one part.
     */
    private fun parts(value: String, multimodalEntry: kotlinx.serialization.json.JsonElement?): JsonArray {
        val extra = multimodalEntry as? JsonArray
        return buildJsonArray {
            if (value.isNotEmpty() || extra == null || multimodalEntry is JsonNull) {
                add(buildJsonObject { put("text", value) })
            }
            extra?.forEach { add(it) }
        }
    }

    private companion object {
        /** Google's documented batch ceiling. */
        const val MAX_PER_CALL = 100
    }
}

private fun JsonObject?.vector(): List<Double> =
    this?.get("values")?.jsonArray?.map { it.jsonPrimitive.content.toDouble() }.orEmpty()
