package com.sabreware.aide.aisdk.providers.deepseek

import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.ProviderFiles
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleCapabilities
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleProvider
import com.sabreware.aide.aisdk.providers.openaicompatible.SamplerParam
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.client.HttpClient

/** The provider id, and the namespace DeepSeek options and metadata file under. */
public const val DEEPSEEK_PROVIDER_ID: String = "deepseek"

/** DeepSeek's API base. Public: a host overriding the endpoint needs the default to fall back to. */
public const val DEEPSEEK_DEFAULT_BASE_URL: String = "https://api.deepseek.com"

/**
 * The beta base, which is what unlocks prefix completion and strict tool calls.
 *
 * DeepSeek gates both on the URL rather than on a request field
 * (https://api-docs.deepseek.com/guides/chat_prefix_completion), so choosing the endpoint IS choosing
 * the feature set — a caller that wants either constructs the provider with this.
 */
public const val DEEPSEEK_BETA_BASE_URL: String = "https://api.deepseek.com/beta"

/**
 * DeepSeek, over its OpenAI-compatible endpoint — with the behaviour a bare `Vendors` row cannot carry.
 *
 * The one that costs money silently: DeepSeek reports cache reads as flat `prompt_cache_hit_tokens` and
 * `prompt_cache_miss_tokens` counters rather than OpenAI's nested `prompt_tokens_details.cached_tokens`
 * (https://api-docs.deepseek.com/guides/kv_cache). The shared reader looks for the nested field, does
 * not find it, and reports no cache read at all — so on the vendor whose headline feature is a large
 * hit-versus-miss price difference, every call looked like a full-price miss. See [deepSeekUsage].
 *
 * The rest — sampler voiding under thinking, the three-value effort vocabulary, prefix and strict-tool
 * gating, and the `insufficient_system_resource` finish reason — is in [DeepSeekLanguageModel]. Image
 * input has two DeepSeek-only halves of its own: [files] uploads the picture, and the body transform
 * in `DeepSeekFileParts.kt` spells the returned id as the FLAT `file_id` part DeepSeek reads, where
 * the shared converter writes OpenAI's nested one.
 *
 * This is the only entry point: the `Vendors.deepSeek` row it replaces is gone, matching the precedent
 * set by `ZaiProvider` (there is deliberately no `Vendors.zai` either). A vendor whose divergence a
 * table row cannot express earns a provider class instead of living in both places.
 *
 * @param baseUrl [DEEPSEEK_BETA_BASE_URL] to enable prefix completion and strict tools.
 */
public class DeepSeekProvider(
    client: HttpClient,
    apiKey: String,
    private val baseUrl: String = DEEPSEEK_DEFAULT_BASE_URL,
    extraHeaders: Map<String, String> = emptyMap(),
) : Provider {

    override val providerId: String = DEEPSEEK_PROVIDER_ID

    private val http = ProviderHttp(client).withErrorStructure(DeepSeekErrors)

    private val headers = combineHeaders(mapOf("Authorization" to "Bearer $apiKey"), extraHeaders)

    private val compat = OpenAICompatibleProvider(
        client = client,
        providerId = DEEPSEEK_PROVIDER_ID,
        baseUrl = baseUrl,
        apiKey = apiKey,
        extraHeaders = extraHeaders,
        convertUsage = ::deepSeekUsage,
        transformRequestBody = ::deepSeekRequestBody,
        errorStructure = DeepSeekErrors,
        capabilitiesFor = {
            OpenAICompatibleCapabilities(
                // Not in DeepSeek's request schema at all, unlike the penalties, which are accepted
                // and ignored — those are dropped with a deprecation warning in the wrap instead.
                unsupportedSamplers = setOf(SamplerParam.Seed),
            )
        },
    )

    override fun languageModel(modelId: String): LanguageModel = DeepSeekLanguageModel(
        delegate = requireNotNull(compat.languageModel(modelId)),
        isBeta = baseUrl.trimEnd('/').endsWith("/beta"),
    )

    /**
     * The Files API — image uploads that mint the ids a [com.sabreware.aide.aisdk.FileData.Reference]
     * carries into a `file_id` part. Served under [baseUrl] as the reference does, `/beta` included.
     */
    public fun files(): ProviderFiles = DeepSeekFiles(
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = headers,
    )
}
