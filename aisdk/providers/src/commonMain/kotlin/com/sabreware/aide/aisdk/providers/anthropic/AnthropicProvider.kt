package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.BatchLanguageModel
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.ProviderFiles
import com.sabreware.aide.aisdk.ProviderSkills
import com.sabreware.aide.aisdk.util.ProviderHttp
import io.ktor.client.HttpClient

/**
 * The Anthropic vendor.
 *
 * Offers language models only — every other modality resolves to null rather than a stub that throws,
 * so a caller can ask "does Anthropic do images?" without catching an exception to find out.
 *
 * The [HttpClient] is supplied by the host. Authentication is `x-api-key` plus the required
 * `anthropic-version` header, not a bearer token, which is the detail most OpenAI-shaped clients get
 * wrong on their first Anthropic call.
 */
/** Anthropic's API base. Public: a host overriding the endpoint needs the default to fall back to. */
public const val ANTHROPIC_DEFAULT_BASE_URL: String = "https://api.anthropic.com/v1"

public class AnthropicProvider(
    client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = ANTHROPIC_DEFAULT_BASE_URL,
    private val apiVersion: String = DEFAULT_API_VERSION,
    private val extraHeaders: Map<String, String> = emptyMap(),
    /** Forwarded to every model: a second options namespace for a vendor rehosting this wire. */
    private val optionsNamespace: String? = null,
) : Provider {

    override val providerId: String = ANTHROPIC_PROVIDER_ID

    private val http = ProviderHttp(client)

    override fun languageModel(modelId: String): LanguageModel = AnthropicLanguageModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = defaultHeaders(),
        optionsNamespace = optionsNamespace,
    )

    /**
     * The same model, on the Message Batches API — many requests submitted once, collected later, at
     * half price. See [BatchLanguageModel] for the lifecycle.
     */
    public fun batchLanguageModel(modelId: String): BatchLanguageModel = AnthropicMessagesBatchModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = defaultHeaders(),
        optionsNamespace = optionsNamespace,
    )

    /** The Files API — uploads that mint the ids a [com.sabreware.aide.aisdk.FileData.Reference] carries. */
    public fun files(): ProviderFiles = AnthropicFiles(
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = defaultHeaders(),
    )

    /** The Skills API — uploads a skill directory the code-execution container can load. */
    public fun skills(): ProviderSkills = AnthropicSkills(
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = defaultHeaders(),
    )

    private fun defaultHeaders(): Map<String, String> = buildMap {
        put("x-api-key", apiKey)
        put("anthropic-version", apiVersion)
        putAll(extraHeaders)
    }

    public companion object {
        public const val DEFAULT_API_VERSION: String = "2023-06-01"
    }
}
