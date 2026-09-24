package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleCapabilities
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleProvider
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject

/**
 * The provider id, and the namespace Grok-on-Vertex options and metadata file under.
 *
 * The reference's own name for this subprovider. The dot is its convention for a provider hosted
 * inside another one (`vertex.maas` is the sibling); it is kept verbatim so a conversation's metadata
 * keys read the same on either side of the port.
 */
public const val VERTEX_XAI_PROVIDER_ID: String = "googleVertex.xai"

/**
 * xAI's Grok models on Vertex AI, over Vertex's OpenAI-compatible Chat Completions endpoint.
 *
 * A third kind of Vertex-hosted model. Gemini reuses the google package's model on a Vertex path,
 * Claude is Anthropic's body over Vertex's framing ([VertexAnthropicLanguageModel]), and Grok is the
 * OpenAI Chat Completions wire — so this is [OpenAICompatibleProvider] with Vertex's auth and three
 * departures, verified against Google's Grok documentation
 * (https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/partner-models/grok):
 *
 * - **Reasoning tokens are counted apart from completion tokens**, where OpenAI's wire nests one in the
 *   other. Read with the shared arithmetic the bill is under-reported by the whole reasoning share;
 *   [vertexXaiUsage] is the correction, and the reason the compat provider grew its `convertUsage` seam.
 * - **`reasoning_effort` is refused.** "Grok reasoning models don't support reasoning_effort" — the
 *   reasoning and non-reasoning variants are separate model ids, so the knob has nothing to turn. The
 *   key is stripped from the body, and unlike the reference (which strips silently) the caller is told.
 * - **HTTP image URLs are fetched by the model**, so a URL part is passed through rather than
 *   downloaded and re-uploaded — the compat model, built for self-hosted servers, declares none.
 *
 * Structured outputs (`response_format: json_schema`) are on, per the reference and the Grok model
 * cards (which list structured output as supported), and streamed usage is requested through
 * `stream_options`, as the reference does.
 *
 * Auth is the package's bearer rule — [accessToken] re-resolved per request — applied around an
 * immutable compat provider by [VertexBearerCompat], whose KDoc says why that is a wrapper and not a
 * knob.
 *
 * @param location Grok is served from the global endpoint only, which is also the reference's default;
 *   see [vertexXaiBaseUrl] for what the location does and does not change.
 * @param baseUrl Replaces the constructed endpoint entirely; blank falls back to it, as in the reference.
 */
public class VertexXaiProvider(
    client: HttpClient,
    projectId: String,
    accessToken: suspend () -> String,
    location: String = DEFAULT_VERTEX_OPENAPI_LOCATION,
    baseUrl: String? = null,
    extraHeaders: Map<String, String> = emptyMap(),
) : Provider {

    override val providerId: String = VERTEX_XAI_PROVIDER_ID

    private val compat = VertexBearerCompat(accessToken) { bearer ->
        OpenAICompatibleProvider(
            client = client,
            providerId = VERTEX_XAI_PROVIDER_ID,
            baseUrl = baseUrl?.takeIf { it.isNotBlank() } ?: vertexXaiBaseUrl(projectId, location),
            apiKey = bearer,
            extraHeaders = extraHeaders,
            includeUsage = true,
            capabilitiesFor = { GROK_CAPABILITIES },
            convertUsage = ::vertexXaiUsage,
            transformRequestBody = ::withoutReasoningEffort,
        )
    }

    override fun languageModel(modelId: String): LanguageModel = VertexBearerLanguageModel(
        provider = VERTEX_XAI_PROVIDER_ID,
        modelId = modelId,
        compat = compat,
        urls = mapOf("image/*" to listOf(HTTP_URL)),
        adjust = ::refuseReasoningEffort,
    )

    private companion object {
        /**
         * Grok's ids carry no `gpt-`/`o-` family marker, so the shared detector already reads them as
         * ordinary models; stated outright rather than inferred, because the one thing it must add —
         * `json_schema` support — is a vendor fact, not a model-id fact.
         */
        val GROK_CAPABILITIES = OpenAICompatibleCapabilities(supportsStructuredOutputs = true)

        val HTTP_URL = Regex("^https?://.*$")
    }
}

/** The reference's default for both OpenAI-compatible Vertex subproviders. */
internal const val DEFAULT_VERTEX_OPENAPI_LOCATION: String = "global"

/**
 * The OpenAI-compatible base Vertex serves Grok from — the bare host for EVERY location, deliberately.
 *
 * The one Vertex surface in this package that does not go through [vertexHost]. Grok is available on
 * the global endpoint only — every Grok model card lists "Supported regions: Global", and its quota is a
 * single global figure — so no regional host would answer, and the reference builds the same URL for
 * any location. The location still appears in the path because that is the documented request shape.
 * Verified 2026-09-02 against the model cards under
 * https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/partner-models/grok.
 */
internal fun vertexXaiBaseUrl(projectId: String, location: String): String =
    "https://aiplatform.googleapis.com/v1/projects/$projectId/locations/$location/endpoints/openapi"

/**
 * The body without `reasoning_effort` — the reference's `transformGoogleVertexXaiRequestBody`.
 *
 * Applied to the finished body so it catches both spellings of the request: the key the engine writes
 * for an explicit [CallOptions.reasoning], and one a caller spread in through `providerOptions`.
 */
internal fun withoutReasoningEffort(body: JsonObject): JsonObject =
    if ("reasoning_effort" in body) JsonObject(body - "reasoning_effort") else body

/**
 * Tells the caller the effort they asked for is not a knob here, then clears it.
 *
 * The strip in [withoutReasoningEffort] is what keeps the request valid; this is what keeps it honest.
 * The reference drops the value silently, and a caller that set `high` would otherwise read an ordinary
 * answer as evidence the setting worked — the house precedent (Perplexity's tool drop) is to warn.
 */
internal fun refuseReasoningEffort(options: CallOptions, warnings: MutableList<Warning>): CallOptions {
    if (options.reasoning == ReasoningEffort.ProviderDefault) return options
    warnings += Warning.Unsupported(
        feature = "reasoning",
        details = "Grok on Vertex AI does not support reasoning_effort; the reasoning and " +
            "non-reasoning variants are separate model ids. The value was dropped.",
    )
    return options.copy(reasoning = ReasoningEffort.ProviderDefault)
}
