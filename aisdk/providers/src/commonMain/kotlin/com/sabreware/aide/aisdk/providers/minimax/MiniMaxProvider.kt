package com.sabreware.aide.aisdk.providers.minimax

import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.providers.anthropic.AnthropicProvider
import com.sabreware.aide.aisdk.util.ProviderHttp
import io.ktor.client.HttpClient

/** The provider id. */
public const val MINIMAX_PROVIDER_ID: String = "minimax"

/**
 * MiniMax, over its Anthropic-compatible endpoint.
 *
 * MiniMax serves an Anthropic-shaped API at `/anthropic/v1`, authenticated with the same `x-api-key`
 * and `anthropic-version` headers Anthropic itself uses. So this is not a new wire — it is
 * [AnthropicProvider] pointed somewhere else, and writing a MiniMax-specific model would have been a
 * copy of one that already exists.
 *
 * The reference reaches the same conclusion: its MiniMax package imports `AnthropicLanguageModel`
 * directly rather than defining its own.
 *
 * A consequence worth stating: signature handling, the thinking-mode table and the tool-choice rules all
 * apply here unchanged, because it is literally the same model class.
 */
public fun miniMaxProvider(
    client: HttpClient,
    apiKey: String,
    baseUrl: String = MINIMAX_ANTHROPIC_BASE_URL,
): AnthropicProvider = AnthropicProvider(
    client = client,
    apiKey = apiKey,
    baseUrl = baseUrl,
    // Options a caller files under `minimax` are read too, merged over the canonical `anthropic`.
    optionsNamespace = MINIMAX_PROVIDER_ID,
)

/** MiniMax's Anthropic-compatible base URL. */
public const val MINIMAX_ANTHROPIC_BASE_URL: String = "https://api.minimax.io/anthropic/v1"

/** The API root the video surface lives under — no `/anthropic/v1`, and no version suffix at all. */
public const val MINIMAX_VIDEO_BASE_URL: String = "https://api.minimax.io"

/**
 * MiniMax, both surfaces: chat over the Anthropic-shaped endpoint, video over its own task API.
 *
 * The two surfaces are one vendor with two spellings of the same key. Chat wants `x-api-key` (plus
 * `anthropic-version`); video wants `Authorization: Bearer`. Each endpoint rejects the other's
 * spelling, which is why this class exists rather than a second base URL on [miniMaxProvider] — the
 * auth scheme has to switch with the surface, not just the host path.
 *
 * [miniMaxProvider] remains the chat-only factory (it IS [AnthropicProvider], with everything that
 * implies about signature handling); this class delegates its language models to exactly that provider
 * and adds the video half.
 */
public class MiniMaxProvider(
    client: HttpClient,
    apiKey: String,
    baseUrl: String = MINIMAX_ANTHROPIC_BASE_URL,
    videoBaseUrl: String = MINIMAX_VIDEO_BASE_URL,
    extraHeaders: Map<String, String> = emptyMap(),
) : Provider {

    override val providerId: String = MINIMAX_PROVIDER_ID

    private val chat = miniMaxProvider(client, apiKey, baseUrl)

    private val videoHttp = ProviderHttp(client).withErrorStructure(MiniMaxVideoErrors)

    private val videoHeaders = mapOf("Authorization" to "Bearer $apiKey") + extraHeaders

    private val videoBase = videoBaseUrl.trimEnd('/')

    override fun languageModel(modelId: String): LanguageModel? = chat.languageModel(modelId)

    override fun videoModel(modelId: String): VideoModel = MiniMaxVideoModel(
        modelId = modelId,
        http = videoHttp,
        baseUrl = videoBase,
        headers = videoHeaders,
    )
}
