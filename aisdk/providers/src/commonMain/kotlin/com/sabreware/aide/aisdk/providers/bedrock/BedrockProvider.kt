package com.sabreware.aide.aisdk.providers.bedrock

import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.RerankingModel
import com.sabreware.aide.aisdk.util.AwsCredentials
import com.sabreware.aide.aisdk.util.ProviderHttp
import io.ktor.client.HttpClient

/**
 * Amazon Bedrock.
 *
 * [credentials] is a lambda because STS credentials expire: a provider holding a snapshot works until the
 * session token rotates and then fails as a 403 that looks like a permissions problem.
 *
 * [now] is injected for the same reason SigV4 takes a timestamp — a signature is valid only within a few
 * minutes of it, so the clock is the caller's, and a test can pin it.
 *
 * Two chat paths, routed by model id. Anthropic models keep the native `InvokeModel` path
 * ([BedrockLanguageModel]), whose body is Anthropic's own and whose signature handling is shared with
 * the direct provider — the delicate part this port exists for. Everything else — Nova, Llama, Mistral,
 * Cohere, Titan, DeepSeek, gpt-oss — goes through the Converse API ([BedrockConverseLanguageModel]),
 * Bedrock's one wire for every hosted vendor. Embeddings ride `InvokeModel` with a per-family dialect
 * ([BedrockEmbeddingModel]), as do Nova Canvas images ([BedrockImageModel]); reranking is the agent
 * runtime's own host and API ([BedrockRerankingModel]), signed in the same scope.
 *
 * Mantle — the OpenAI-compatible endpoint for the `gpt-oss` family — is a different host with different
 * auth and the vendor's wire, so it is its own provider, [BedrockMantleProvider], not an id route here.
 *
 * Endpoints are resolved once, from [region] and the AWS partition it belongs to — see
 * [bedrockBaseUrl]. [baseUrl] overrides both services, the reference's `baseURL`; [agentRuntimeBaseUrl]
 * overrides reranking's host alone. The reference reads the same overrides from
 * `AWS_ENDPOINT_URL_BEDROCK_RUNTIME`, `AWS_ENDPOINT_URL_BEDROCK_AGENT_RUNTIME` and `AWS_ENDPOINT_URL`;
 * this module has no environment to read, so a host that honours those variables passes them here.
 */
public class BedrockProvider(
    client: HttpClient,
    private val credentials: () -> AwsCredentials,
    private val region: String,
    private val now: () -> Long,
    baseUrl: String? = null,
    agentRuntimeBaseUrl: String? = null,
) : Provider {

    override val providerId: String = BEDROCK_PROVIDER_ID

    private val http = ProviderHttp(client).withErrorStructure(BedrockErrors)

    private val runtimeBaseUrl: String = bedrockBaseUrl(region, BEDROCK_RUNTIME_SERVICE, baseUrl)

    private val agentRuntimeUrl: String =
        bedrockBaseUrl(region, BEDROCK_AGENT_RUNTIME_SERVICE, agentRuntimeBaseUrl ?: baseUrl)

    override fun languageModel(modelId: String): LanguageModel = languageModel(modelId, modelFamily = null)

    /**
     * A chat model whose family the id does not reveal.
     *
     * An application inference profile ARN names no model, so nothing about it says which request
     * shape to use; [modelFamily] = [BEDROCK_MODEL_FAMILY_ANTHROPIC] tells the Converse path to use
     * Anthropic's — native structured output, Anthropic's `tool_choice` fields. An id that names
     * `anthropic` outright never needs it and keeps the native Messages path regardless.
     */
    public fun languageModel(modelId: String, modelFamily: String?): LanguageModel =
        if (isAnthropicModel(modelId)) {
            BedrockLanguageModel(
                modelId = modelId,
                http = http,
                credentials = credentials,
                region = region,
                now = now,
                baseUrl = runtimeBaseUrl,
            )
        } else {
            BedrockConverseLanguageModel(
                modelId = modelId,
                http = http,
                credentials = credentials,
                region = region,
                now = now,
                modelFamily = modelFamily,
                baseUrl = runtimeBaseUrl,
            )
        }

    override fun embeddingModel(modelId: String): EmbeddingModel = BedrockEmbeddingModel(
        modelId = modelId,
        http = http,
        credentials = credentials,
        region = region,
        now = now,
        baseUrl = runtimeBaseUrl,
    )

    override fun imageModel(modelId: String): ImageModel = BedrockImageModel(
        modelId = modelId,
        http = http,
        credentials = credentials,
        region = region,
        now = now,
        baseUrl = runtimeBaseUrl,
    )

    override fun rerankingModel(modelId: String): RerankingModel = BedrockRerankingModel(
        modelId = modelId,
        http = http,
        credentials = credentials,
        region = region,
        now = now,
        baseUrl = agentRuntimeUrl,
    )

    private fun isAnthropicModel(modelId: String): Boolean =
        // Bedrock ids look like `anthropic.claude-…` or `us.anthropic.claude-…` with a region prefix for
        // cross-region inference profiles.
        modelId.substringBefore('.').let { it == "anthropic" } || ".anthropic." in modelId
}
