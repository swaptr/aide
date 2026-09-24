package com.sabreware.aide.aisdk.providers.togetherai

import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.RerankingCallOptions
import com.sabreware.aide.aisdk.RerankingModel
import com.sabreware.aide.aisdk.RerankingResult
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The provider id, and the namespace Together AI options file under. */
public const val TOGETHERAI_PROVIDER_ID: String = "togetherai"

/**
 * Together AI reranking.
 *
 * Together's chat, completion and embedding endpoints speak the OpenAI wire and are served by
 * `Vendors.together`. Reranking is its own endpoint and its own shape, and one property makes it worth
 * having rather than substituting Voyage or Cohere: **it ranks structured documents natively.** Where
 * Voyage takes strings only, Together takes the objects as they are and `rank_fields` names which keys
 * to score — so a caller ranking `{title, body, author}` can rank on title and body without flattening
 * the record into a blob whose punctuation gets scored too.
 *
 * Errors are `{"error": {"message": …}}`, which `defaultErrorMessage` already reads.
 */
public class TogetherAiProvider(
    client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : Provider {

    override val providerId: String = TOGETHERAI_PROVIDER_ID

    private val http = ProviderHttp(client)

    override fun rerankingModel(modelId: String): RerankingModel = TogetherAiRerankingModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = mapOf("Authorization" to "Bearer $apiKey") + extraHeaders,
    )

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.together.xyz/v1"
    }
}

internal class TogetherAiRerankingModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : RerankingModel {

    override val provider: String = TOGETHERAI_PROVIDER_ID

    override suspend fun doRerank(options: RerankingCallOptions): RerankingResult {
        val together = options.providerOptions?.get(TOGETHERAI_PROVIDER_ID)

        val documents = when (val docs = options.documents) {
            // No warning on either arm: this wire accepts both forms, so neither is an approximation.
            is RerankingCallOptions.Documents.Text ->
                buildJsonArray { docs.values.forEach { add(JsonPrimitive(it)) } }

            is RerankingCallOptions.Documents.Objects ->
                buildJsonArray { docs.values.forEach { add(it) } }
        }

        val body = buildJsonObject {
            put("model", modelId)
            put("documents", documents)
            put("query", options.query)
            options.topN?.let { put("top_n", it) }
            (together?.get("rankFields") as? JsonArray)?.let { put("rank_fields", it) }
            // Together echoes every ranked document back by default. The caller already holds them —
            // it submitted them — so the echo is pure response size, and a large corpus makes it the
            // dominant cost of the call.
            put("return_documents", false)
        }

        val result = http.postJson(
            url = "$baseUrl/rerank",
            body = body,
            headers = combineHeaders(headers, options.headers),
        )
        val response = result.value.jsonObject

        return RerankingResult(
            ranking = response["results"]?.jsonArray.orEmpty().map { entry ->
                RerankingResult.Rank(
                    index = entry.jsonObject["index"]?.jsonPrimitive?.intOrNull ?: 0,
                    relevanceScore = entry.jsonObject["relevance_score"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                )
            },
            // Together names the model and the call in the body, which is more specific than the header
            // request id the transport would otherwise fall back to.
            response = result.modalityResponse(
                modelId = response["model"]?.jsonPrimitive?.contentOrNull() ?: modelId,
                id = response["id"]?.jsonPrimitive?.contentOrNull(),
            ),
        )
    }
}

private fun JsonPrimitive.contentOrNull(): String? = takeIf { it.isString }?.content
