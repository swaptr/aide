package com.sabreware.aide.aisdk.providers.zai

import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleCapabilities
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleProvider
import com.sabreware.aide.aisdk.providers.openaicompatible.SamplerParam
import io.ktor.client.HttpClient

/** The provider id, and the namespace Z.AI options and metadata file under. */
public const val ZAI_PROVIDER_ID: String = "zai"

/** Z.AI's documented API base. Public: a host overriding the endpoint needs the default to fall back to. */
public const val ZAI_DEFAULT_BASE_URL: String = "https://api.z.ai/api/paas/v4"

/**
 * Z.AI's GLM models, over their OpenAI-compatible endpoint — with the three departures that make a bare
 * `Vendors` table entry wrong for this vendor:
 *
 * - **Three samplers are rejected outright.** `frequency_penalty`, `presence_penalty` and `seed` are not
 *   in Z.AI's schema; the reference strips them from every request and warns. A table entry would send
 *   them and the caller would get a 400 for knobs that work everywhere else.
 * - **`tool_choice` barely exists.** Only automatic selection is supported: `none` is expressed by
 *   omitting the tools entirely, and `required` or a named tool cannot be expressed at all — those warn
 *   and fall back to automatic, per the reference.
 * - **Its own finish vocabulary.** `sensitive` (content filter), `model_context_window_exceeded`
 *   (length) and `network_error` (error) are Z.AI spellings no shared table maps; left unmapped they
 *   all surface as `Other` and a loop keyed on the finish reason misreads a filtered answer as a
 *   mystery.
 *
 * Everything else — the wire, streaming, the `reasoning_content` channel GLM thinking arrives on — IS
 * the OpenAI-compatible model, so this provider wraps it rather than re-implementing it.
 *
 * Caller options file under `zai` in the reference's own camelCase spellings (`doSample`,
 * `thinking.clearThinking`, `reasoningEffort`, `toolStream`, `requestId`, `userId`) and are translated
 * to Z.AI's snake_case wire fields; see [ZaiLanguageModel].
 */
public class ZaiProvider(
    client: HttpClient,
    apiKey: String,
    baseUrl: String = ZAI_DEFAULT_BASE_URL,
    extraHeaders: Map<String, String> = emptyMap(),
) : Provider {

    override val providerId: String = ZAI_PROVIDER_ID

    private val compat = OpenAICompatibleProvider(
        client = client,
        providerId = ZAI_PROVIDER_ID,
        baseUrl = baseUrl,
        apiKey = apiKey,
        extraHeaders = extraHeaders,
        // Z.AI reports usage on the final chunk unprompted, and `stream_options` is not in its schema —
        // sending it risks a 400 and buys nothing.
        includeUsage = false,
        capabilitiesFor = {
            OpenAICompatibleCapabilities(
                unsupportedSamplers = setOf(
                    SamplerParam.PresencePenalty,
                    SamplerParam.FrequencyPenalty,
                    SamplerParam.Seed,
                ),
            )
        },
    )

    override fun languageModel(modelId: String): LanguageModel =
        ZaiLanguageModel(requireNotNull(compat.languageModel(modelId)))
}
