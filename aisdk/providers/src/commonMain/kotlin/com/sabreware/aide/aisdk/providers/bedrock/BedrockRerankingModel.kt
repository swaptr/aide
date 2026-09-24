package com.sabreware.aide.aisdk.providers.bedrock

import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.RerankingCallOptions
import com.sabreware.aide.aisdk.RerankingModel
import com.sabreware.aide.aisdk.RerankingResult
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optDouble
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.AwsCredentials
import com.sabreware.aide.aisdk.util.ProviderHttp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Bedrock reranking, over the agent runtime's `Rerank` — a different host from every other Bedrock
 * call here (`bedrock-agent-runtime`, not `bedrock-runtime`) in the same signing scope.
 *
 * The wire names the model by ARN rather than id, so the id a caller passes is expanded into the
 * foundation-model ARN for the provider's region. An id that already IS an ARN — an application
 * inference profile — goes through untouched, because prefixing one produces a string no account owns.
 *
 * Both document kinds are native: text goes as `textDocument`, and a structured document as
 * `jsonDocument` VERBATIM, which makes this the one reranker in the module that scores fields rather
 * than a serialization of them. The member names under `rerankingConfiguration` are AWS's own and
 * load-bearing — `bedrockRerankingConfiguration` renamed sends the required member as null, and the
 * request is a 400.
 *
 * `nextToken` rides both ways under `amazon-bedrock`: in as an option, and out — when the service pages
 * — as provider metadata, because a token that exists only inside the raw response body is one the
 * caller has to re-parse the response to use.
 *
 * Verified against the API reference on 2026-09-02:
 * https://docs.aws.amazon.com/bedrock/latest/APIReference/API_agent-runtime_Rerank.html
 */
internal class BedrockRerankingModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val credentials: () -> AwsCredentials,
    private val region: String,
    private val now: () -> Long,
    /** The agent-runtime endpoint; defaults to the region's own, and a host with an override passes it. */
    private val baseUrl: String = bedrockBaseUrl(region, BEDROCK_AGENT_RUNTIME_SERVICE),
) : RerankingModel {

    override val provider: String = BEDROCK_PROVIDER_ID

    override suspend fun doRerank(options: RerankingCallOptions): RerankingResult {
        val vendor = options.providerOptions.bedrockEntry()
        val body = buildJsonObject {
            vendor?.optString("nextToken")?.let { put("nextToken", it) }
            putJsonArray("queries") {
                addJsonObject {
                    putJsonObject("textQuery") { put("text", options.query) }
                    put("type", "TEXT")
                }
            }
            putJsonObject("rerankingConfiguration") {
                putJsonObject("bedrockRerankingConfiguration") {
                    putJsonObject("modelConfiguration") {
                        put("modelArn", modelArn())
                        vendor?.optObject("additionalModelRequestFields")
                            ?.let { put("additionalModelRequestFields", it) }
                    }
                    options.topN?.let { put("numberOfResults", it) }
                }
                put("type", "BEDROCK_RERANKING_MODEL")
            }
            put("sources", JsonArray(sources(options.documents)))
        }

        val result = http.bedrockSignedPost(
            url = "$baseUrl/rerank",
            body = body,
            extraHeaders = options.headers,
            credentials = credentials(),
            region = region,
            timestampMillis = now(),
        )
        val response = result.value

        return RerankingResult(
            // Already ordered by score, and the index is into the documents the caller submitted —
            // re-sorting here could only disagree with the service.
            ranking = response.optArray("results").orEmpty().mapNotNull { entry ->
                val rank = entry as? JsonObject ?: return@mapNotNull null
                val index = rank.optInt("index") ?: return@mapNotNull null
                RerankingResult.Rank(index = index, relevanceScore = rank.optDouble("relevanceScore") ?: 0.0)
            },
            providerMetadata = response.optString("nextToken")?.let { token ->
                mapOf(BEDROCK_PROVIDER_ID to buildJsonObject { put("nextToken", token) })
            },
            response = result.modalityResponse(modelId = modelId, body = response.toString()),
            request = RequestInfo(result.requestBody),
        )
    }

    private fun sources(documents: RerankingCallOptions.Documents): List<JsonElement> = when (documents) {
        is RerankingCallOptions.Documents.Text -> documents.values.map { text ->
            inlineSource {
                put("type", "TEXT")
                putJsonObject("textDocument") { put("text", text) }
            }
        }

        is RerankingCallOptions.Documents.Objects -> documents.values.map { document ->
            inlineSource {
                put("type", "JSON")
                put("jsonDocument", document)
            }
        }
    }

    private fun inlineSource(document: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("type", "INLINE")
        putJsonObject("inlineDocumentSource") { document() }
    }

    private fun modelArn(): String =
        if (modelId.startsWith("arn:")) modelId else "arn:aws:bedrock:$region::foundation-model/$modelId"
}
