package com.sabreware.aide.aisdk.providers.perplexity

import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.EmbeddingResult
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.TooManyEmbeddingValuesForCallError
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The provider id, and the namespace Perplexity options and metadata file under. */
public const val PERPLEXITY_PROVIDER_ID: String = "perplexity"

/**
 * Perplexity: the Agent API for language models, and quantized embeddings.
 *
 * **Dated warning (checked 2026-09-02).** Perplexity's Sonar Chat Completions wire is deprecated and
 * "will be supported until September 27, 2026". This provider does NOT serve it, and the
 * `Vendors.perplexity` row that did is gone: the successor is the **Agent API**, `POST /v1/agent` —
 * a different endpoint with a different request shape (OpenAI's Responses wire plus Perplexity's own
 * tools and output items), which is a migration rather than a flag. A `sonar-*` id sent to
 * [languageModel] reaches the Agent API as a model id it does not know; the documented replacements
 * are presets, and [PerplexityPresets.forSonarModel] names the one the migration guide pairs each
 * Sonar model with — deliberately a helper a caller invokes, never an automatic rewrite, because a
 * preset picks a different model, step budget and tool set.
 *
 * [languageModel] takes either a `provider/model` id (`openai/gpt-5.6-sol`,
 * `anthropic/claude-sonnet-4-6`) or a preset name (`fast`, `low`, `medium`, `high`, `xhigh`), which
 * goes out as `preset` in place of `model` — the two never collide, because model ids carry a slash.
 * Options and metadata file under `perplexity` (canonical `openai` read underneath, custom key
 * winning field by field, the Responses-family rule): `providerOptions.perplexity.max_steps`,
 * `.preset` beside a model id (the documented override), `.instructions`, `.previousResponseId`,
 * `.store`, and anything else the API reference lists, spread verbatim. A `max_output_tokens` is
 * "required when using anthropic models" (the API reference's wording, minus the wildcard that would nest
 * this comment); the server, not this class, reports its absence.
 *
 * The embedding model is the base64-quantized wire described on [PerplexityEmbeddingModel], and is the
 * reason this package existed before the Agent API did.
 */
public class PerplexityProvider(
    client: HttpClient,
    apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    extraHeaders: Map<String, String> = emptyMap(),
) : Provider {

    override val providerId: String = PERPLEXITY_PROVIDER_ID

    private val http = ProviderHttp(client)

    private val endpoint: String = baseUrl.trimEnd('/')

    private val headers: Map<String, String> = mapOf("Authorization" to "Bearer $apiKey") + extraHeaders

    override fun languageModel(modelId: String): LanguageModel = PerplexityLanguageModel(
        modelId = modelId,
        http = http,
        url = "$endpoint/v1/agent",
        headers = headers,
    )

    override fun embeddingModel(modelId: String): EmbeddingModel = PerplexityEmbeddingModel(
        modelId = modelId,
        http = http,
        baseUrl = endpoint,
        headers = headers,
    )

    public companion object {
        /** Without a version segment: every endpoint carries its own `/v1`. */
        public const val DEFAULT_BASE_URL: String = "https://api.perplexity.ai"
    }
}

/**
 * Perplexity embeddings.
 *
 * Perplexity does **not** return floating-point vectors. Every embedding comes back as a base64 string
 * of quantized values, so an OpenAI-compatible reader — which expects `embedding` to be an array of
 * numbers — does not merely lose precision here, it fails to parse the response at all.
 *
 * Two quantizations, and the difference decides how a caller must compare the vectors:
 *
 * - `base64_int8` (the default): signed bytes, compared by cosine similarity.
 * - `base64_binary`: packed bits, one byte holding eight dimensions, compared by Hamming distance.
 *
 * Decoding to `Double` keeps the neutral [com.sabreware.aide.aisdk.Embedding] type honest; the caller
 * still needs to know which metric applies, which is why the format is a call option rather than a
 * hidden default.
 *
 * Errors are `{"error": {"message": …}}`, which `defaultErrorMessage` already reads.
 */
internal class PerplexityEmbeddingModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : EmbeddingModel {

    override val provider: String = PERPLEXITY_PROVIDER_ID

    override suspend fun maxEmbeddingsPerCall(): Int = MAX_PER_CALL

    override suspend fun supportsParallelCalls(): Boolean = true

    override suspend fun doEmbed(options: EmbeddingCallOptions): EmbeddingResult {
        if (options.values.size > MAX_PER_CALL) {
            throw TooManyEmbeddingValuesForCallError(provider, modelId, MAX_PER_CALL, options.values)
        }
        val perplexity = options.providerOptions?.get(PERPLEXITY_PROVIDER_ID)
        // Sent explicitly rather than left to the server: the decode below has to know which of the two
        // quantizations it is reading, and there is no marker in the response that says.
        val encodingFormat = perplexity?.string("encodingFormat") ?: INT8

        val body = buildJsonObject {
            put("model", modelId)
            put("input", buildJsonArray { options.values.forEach { add(JsonPrimitive(it)) } })
            perplexity?.int("dimensions")?.let { put("dimensions", it) }
            put("encoding_format", encodingFormat)
        }

        val result = http.postJson(
            url = "$baseUrl/v1/embeddings",
            body = body,
            headers = combineHeaders(headers, options.headers),
        )
        val response = result.value.jsonObject
        val usage = response["usage"]?.jsonObject

        return EmbeddingResult(
            embeddings = response["data"]?.jsonArray.orEmpty().map { entry ->
                decodeEmbedding(
                    entry.jsonObject["embedding"]?.jsonPrimitive?.content.orEmpty(),
                    encodingFormat,
                )
            },
            usage = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull,
            // Perplexity is the only vendor here that prices a call in the response. Surfacing it is the
            // difference between a caller that can budget an indexing run and one that finds out monthly.
            providerMetadata = usage?.get("cost")?.jsonObject?.toMetadata(),
            response = result.modalityResponse(modelId = modelId),
        )
    }

    /**
     * Turns one quantized string into a vector.
     *
     * Kotlin's `Byte` is already signed, so `base64_int8` needs no reinterpretation — the reference has
     * to build an `Int8Array` view only because JavaScript's `Uint8Array` is not. `base64_binary` is the
     * case that does need masking: those bytes are packed bits, and reading them signed would turn every
     * dimension above 127 negative.
     */
    private fun decodeEmbedding(value: String, encodingFormat: String): List<Double> {
        if (value.isEmpty()) return emptyList()
        val bytes = decodeBase64(value)
        return when (encodingFormat) {
            BINARY -> bytes.map { (it.toInt() and BYTE_MASK).toDouble() }
            else -> bytes.map { it.toDouble() }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64(value: String): ByteArray = Base64.decode(value)

    private companion object {
        const val INT8 = "base64_int8"
        const val BINARY = "base64_binary"
        const val BYTE_MASK = 0xFF

        /** Perplexity's documented per-request ceiling for standard embeddings. */
        const val MAX_PER_CALL = 512
    }
}

/** The cost block, camel-cased into Perplexity's metadata namespace. */
private fun JsonObject.toMetadata(): ProviderMetadata {
    val cost = this
    return mapOf(
        PERPLEXITY_PROVIDER_ID to buildJsonObject {
            putJsonObject("cost") {
                put("inputCost", cost.presentOrNull("input_cost"))
                put("totalCost", cost.presentOrNull("total_cost"))
                put("currency", cost.presentOrNull("currency"))
            }
        },
    )
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/**
 * A value, or an explicit JSON null.
 *
 * The keys are kept even when the vendor omits them so a consumer reading `cost.currency` finds null
 * rather than a missing key it has to distinguish from a malformed payload.
 */
private fun JsonObject.presentOrNull(key: String): JsonElement = this[key] ?: JsonNull
