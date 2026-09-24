package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.Embedding
import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.EmbeddingResult
import com.sabreware.aide.aisdk.TooManyEmbeddingValuesForCallError
import com.sabreware.aide.aisdk.providers.google.GoogleErrorStructure
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Vertex text embeddings — a DIFFERENT wire from the Gemini API's, not a re-hosted copy of it.
 *
 * The classic models (`text-embedding-005`, gecko and friends) are served by `:predict` with
 * `instances`/`parameters`, and the wire mixes its cases: `task_type` is snake_case inside an instance
 * while `outputDimensionality` is camelCase inside `parameters`. That inconsistency is Google's, and it
 * is ported faithfully — normalising either direction produces a field the endpoint ignores.
 *
 * `gemini-embedding-2` (and its preview) are the exception: they exist only behind `:embedContent`,
 * one value per call, with the options gathered into `embedContentConfig` — so the batch ceiling is a
 * property of the MODEL, which is why [maxEmbeddingsPerCall] switches on the id. A caller that batches
 * against the wrong ceiling gets [TooManyEmbeddingValuesForCallError] here, before a request is spent.
 *
 * Options ride `providerOptions["google-vertex"]` (canonical `google` also read, custom wins):
 * `taskType`, `outputDimensionality`, `title` (RETRIEVAL_DOCUMENT context), `autoTruncate`.
 */
internal class VertexEmbeddingModel(
    override val modelId: String,
    http: ProviderHttp,
    private val baseUrl: String,
    private val headers: suspend () -> Map<String, String>,
) : EmbeddingModel {

    override val provider: String = VERTEX_PROVIDER_ID

    private val http = http.withErrorStructure(GoogleErrorStructure)

    override suspend fun maxEmbeddingsPerCall(): Int = maxPerCall()

    private fun maxPerCall(): Int = if (usesEmbedContent()) 1 else PREDICT_MAX_PER_CALL

    /** The models that exist only behind `:embedContent` — see the class KDoc. */
    private fun usesEmbedContent(): Boolean =
        modelId == "gemini-embedding-2" || modelId == "gemini-embedding-2-preview"

    override suspend fun doEmbed(options: EmbeddingCallOptions): EmbeddingResult {
        val ceiling = maxPerCall()
        if (options.values.size > ceiling) {
            throw TooManyEmbeddingValuesForCallError(provider, modelId, ceiling, options.values)
        }

        val vendor = options.providerOptions.vertexVendorOptions()
        val requestHeaders = combineHeaders(headers(), options.headers)
        return if (usesEmbedContent()) {
            embedContent(options, vendor, requestHeaders)
        } else {
            predict(options, vendor, requestHeaders)
        }
    }

    private suspend fun embedContent(
        options: EmbeddingCallOptions,
        vendor: JsonObject?,
        requestHeaders: Map<String, String>,
    ): EmbeddingResult {
        val config = buildJsonObject {
            vendor?.optInt("outputDimensionality")?.let { put("outputDimensionality", it) }
            vendor?.optString("taskType")?.let { put("taskType", it) }
            vendor?.optString("title")?.let { put("title", it) }
            vendor?.optBoolean("autoTruncate")?.let { put("autoTruncate", it) }
        }
        val body = buildJsonObject {
            putJsonObject("content") {
                putJsonArray("parts") { addJsonObject { put("text", options.values[0]) } }
            }
            // Always present, matching the reference's wire — with no options it is `{}`.
            put("embedContentConfig", config)
        }

        val result = http.postJson("$baseUrl/models/$modelId:embedContent", body, requestHeaders)
        val response = result.value.jsonObject
        val vector = response.optObject("embedding")?.optArray("values").toEmbedding()

        return EmbeddingResult(
            embeddings = listOf(vector),
            usage = response.optObject("usageMetadata")?.optInt("promptTokenCount"),
            response = result.modalityResponse(modelId = modelId),
            request = result.requestInfo(),
        )
    }

    private suspend fun predict(
        options: EmbeddingCallOptions,
        vendor: JsonObject?,
        requestHeaders: Map<String, String>,
    ): EmbeddingResult {
        val body = buildJsonObject {
            put(
                "instances",
                buildJsonArray {
                    options.values.forEach { value ->
                        addJsonObject {
                            put("content", value)
                            // snake_case HERE, camelCase in `parameters` — Google's wire, verbatim.
                            vendor?.optString("taskType")?.let { put("task_type", it) }
                            vendor?.optString("title")?.let { put("title", it) }
                        }
                    }
                },
            )
            putJsonObject("parameters") {
                vendor?.optInt("outputDimensionality")?.let { put("outputDimensionality", it) }
                vendor?.optBoolean("autoTruncate")?.let { put("autoTruncate", it) }
            }
        }

        val result = http.postJson("$baseUrl/models/$modelId:predict", body, requestHeaders)
        val predictions = result.value.jsonObject["predictions"]?.jsonArray.orEmpty()

        // Token counts ride per prediction; the call-level figure is their sum, and a prediction that
        // reports none keeps the sum honest by keeping it null.
        var sawTokens = false
        var tokens = 0
        val embeddings = predictions.map { prediction ->
            val block = prediction.jsonObject.optObject("embeddings")
            block?.optObject("statistics")?.optInt("token_count")?.let {
                sawTokens = true
                tokens += it
            }
            block?.optArray("values").toEmbedding()
        }

        return EmbeddingResult(
            embeddings = embeddings,
            usage = tokens.takeIf { sawTokens },
            response = result.modalityResponse(modelId = modelId),
            request = result.requestInfo(),
        )
    }

    private companion object {
        const val PREDICT_MAX_PER_CALL = 250
    }
}

private fun JsonArray?.toEmbedding(): Embedding =
    this?.map { it.jsonPrimitive.double }.orEmpty()
