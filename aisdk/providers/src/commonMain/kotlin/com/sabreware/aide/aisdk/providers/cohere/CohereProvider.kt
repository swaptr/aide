package com.sabreware.aide.aisdk.providers.cohere

import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.RerankingModel
import com.sabreware.aide.aisdk.util.ProviderHttp
import io.ktor.client.HttpClient

/** The provider id, and the namespace Cohere payloads file under. */
public const val COHERE_PROVIDER_ID: String = "cohere"

/**
 * Cohere: chat, embeddings and reranking.
 *
 * Reranking is why this provider matters beyond its models. It is the vendor the technique came from,
 * and `rerank-v3.5` is the default a retrieval pipeline is tuned against — so a library that ships
 * reranking without Cohere ships the interface and not the implementation anyone was going to use.
 */
public class CohereProvider(
    client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : Provider {

    override val providerId: String = COHERE_PROVIDER_ID

    private val http = ProviderHttp(client)

    private val endpoint: String get() = baseUrl.trimEnd('/')

    private val authHeaders: Map<String, String> get() = mapOf("Authorization" to "Bearer $apiKey")

    override fun languageModel(modelId: String): LanguageModel = CohereLanguageModel(
        modelId = modelId,
        http = http,
        baseUrl = endpoint,
        headers = authHeaders,
    )

    override fun embeddingModel(modelId: String): EmbeddingModel = CohereEmbeddingModel(
        modelId = modelId,
        http = http,
        url = "$endpoint/embed",
        headers = authHeaders,
    )

    override fun rerankingModel(modelId: String): RerankingModel = CohereRerankingModel(
        modelId = modelId,
        http = http,
        url = "$endpoint/rerank",
        headers = authHeaders,
    )

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.cohere.com/v2"
    }
}
