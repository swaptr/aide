package com.sabreware.aide.aisdk.providers.moonshotai

import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleCapabilities
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleProvider
import io.ktor.client.HttpClient

/** The provider id, and the namespace Moonshot options and metadata file under. */
public const val MOONSHOT_PROVIDER_ID: String = "moonshotai"

/** Moonshot's API base. Public: a host overriding the endpoint needs the default to fall back to. */
public const val MOONSHOT_DEFAULT_BASE_URL: String = "https://api.moonshot.ai/v1"

/**
 * Moonshot AI's Kimi models, over their OpenAI-compatible endpoint.
 *
 * A `Vendors` table row cannot serve this vendor, for two reasons that both fail silently rather than
 * loudly:
 *
 * - **Its schemas are not JSON Schema.** Moonshot validates tool parameters and structured-output
 *   schemas against MFJS, a narrower dialect, and rejects several perfectly ordinary keywords. Every
 *   schema is rewritten on the way out — see [normalizeJsonSchemaForMfjs], which follows Moonshot's
 *   published specification and deliberately departs from the vendored reference on the tuple rule.
 * - **`cached_tokens` is top-level.** The shared OpenAI reading looks under `prompt_tokens_details`
 *   and finds nothing, so a cached call reported zero cache reads — a plausible number, which is what
 *   made it invisible. See [moonshotUsage].
 *
 * Thinking is then gated per family, because K3, K2.7, K2.6, K2.5 and `moonshot-v1` each accept a
 * different subset and reject the rest. That lives in [MoonshotLanguageModel].
 *
 * Everything else — the wire, streaming, the `reasoning_content` channel Kimi thinking arrives on — IS
 * the OpenAI-compatible model, so this provider wraps it rather than re-implementing it.
 */
public class MoonshotProvider(
    client: HttpClient,
    apiKey: String,
    baseUrl: String = MOONSHOT_DEFAULT_BASE_URL,
    extraHeaders: Map<String, String> = emptyMap(),
) : Provider {

    override val providerId: String = MOONSHOT_PROVIDER_ID

    private val compat = OpenAICompatibleProvider(
        client = client,
        providerId = MOONSHOT_PROVIDER_ID,
        baseUrl = baseUrl,
        apiKey = apiKey,
        extraHeaders = extraHeaders,
        convertUsage = ::moonshotUsage,
        capabilitiesFor = { modelId ->
            OpenAICompatibleCapabilities(supportsStructuredOutputs = supportsStructuredOutputs(modelId))
        },
    )

    override fun languageModel(modelId: String): LanguageModel =
        MoonshotLanguageModel(requireNotNull(compat.languageModel(modelId)), moonshotFamily(modelId))
}

/**
 * Which models implement `response_format: json_schema`.
 *
 * An allowlist rather than a default, matching the reference: a model outside it is downgraded to
 * `json_object` with a warning by the shared path, where claiming support it does not have is a 400 on
 * a request that looked well-formed.
 */
internal fun supportsStructuredOutputs(modelId: String): Boolean =
    modelId.startsWith("kimi-k") || modelId in STRUCTURED_OUTPUT_V1_MODELS

private val STRUCTURED_OUTPUT_V1_MODELS = setOf(
    "moonshot-v1-8k",
    "moonshot-v1-32k",
    "moonshot-v1-128k",
    "moonshot-v1-auto",
    "moonshot-v1-8k-vision-preview",
    "moonshot-v1-32k-vision-preview",
    "moonshot-v1-128k-vision-preview",
)
