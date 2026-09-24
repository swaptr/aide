package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleCapabilities
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleProvider
import com.sabreware.aide.aisdk.providers.options.optString
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The provider id, and the namespace Vertex MaaS options and metadata file under.
 *
 * The reference's own name — `vertex.maas`, not `googleVertex.maas`: the two subproviders were named
 * at different times and the reference never reconciled them. Kept verbatim for the same reason as
 * [VERTEX_XAI_PROVIDER_ID].
 */
public const val VERTEX_MAAS_PROVIDER_ID: String = "vertex.maas"

/**
 * Vertex AI Model-as-a-Service: the open and partner models (DeepSeek, Llama, Qwen, gpt-oss, Kimi,
 * MiniMax) Google serves over its OpenAI-compatible Chat Completions endpoint.
 *
 * Configuration over [OpenAICompatibleProvider], with Vertex's auth applied around it by
 * [VertexBearerCompat] — nothing about the wire is MaaS's own. What is, verified 2026-09-02 against
 * Google's "Call MaaS APIs for open models"
 * (https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/maas/call-open-model-apis):
 *
 * - **The host follows the regional rule** ([vertexHost]): `global` on the bare host, `eu`/`us` on the
 *   `.rep.` subdomain, everything else location-prefixed — Google's page documents the regional and
 *   global forms, and the reference's `getHost` is the same rule. Where Grok is global-only, open
 *   models are served regionally and the host has to say which region.
 * - **Streamed usage arrives unprompted.** Google's own streaming example carries `usage` on the last
 *   chunk with no `stream_options` in the request, and the reference sends none; so none is sent.
 * - **Chat is the only modality.** The reference's provider type inherits `completionModel`,
 *   `embeddingModel` and `imageModel` from the generic compat interface, but Google documents only
 *   `chat/completions` on this surface — a modality nothing serves is null here, not a 404 in waiting.
 *   (E5 embedding models are listed under MaaS; the endpoint they answer on was not verifiable from
 *   the documentation reachable at the time of writing, so nothing is declared for them.)
 * - **Llama 4 is told its own output ceiling.** Left unset, `max_tokens` on the two Llama 4 models
 *   falls to a server default that truncates any real answer, so the reference fills in 8192 — the
 *   models' limit — when the caller set none, and touches no other model and no explicit value
 *   ([vertexMaasRequestBody]).
 * - **DeepSeek R1 inlines its reasoning.** Google's MaaS thinking guide: for R1 0528 "reasoning is
 *   surrounded by `<think></think>` tags in the content field. There is no reasoning_content field."
 *   The compat model's tag splitter is on, so it surfaces as a reasoning part rather than literal
 *   text — which the reference, having no splitter, renders as `<think>` on screen. Every other
 *   thinking model here uses `reasoning_content` (switched on with `chat_template_kwargs.thinking`,
 *   which reaches the body through `providerOptions["vertex.maas"]` as any other key does).
 *
 * Structured outputs stay at the compat default (a schema downgrades to `json_object` with a
 * warning), as the reference leaves them: Google's structured-output page documents JSON mode on the
 * REST endpoint and schema-following only through the Python SDK's `parse`, and the models behind
 * MaaS differ in what they honour.
 *
 * @param location The reference's default is `global`; Google's REST examples use a region.
 * @param baseUrl Replaces the constructed endpoint entirely; blank falls back to it, as in the reference.
 */
public class VertexMaasProvider(
    client: HttpClient,
    projectId: String,
    accessToken: suspend () -> String,
    location: String = DEFAULT_VERTEX_OPENAPI_LOCATION,
    baseUrl: String? = null,
    extraHeaders: Map<String, String> = emptyMap(),
) : Provider {

    override val providerId: String = VERTEX_MAAS_PROVIDER_ID

    private val compat = VertexBearerCompat(accessToken) { bearer ->
        OpenAICompatibleProvider(
            client = client,
            providerId = VERTEX_MAAS_PROVIDER_ID,
            baseUrl = baseUrl?.takeIf { it.isNotBlank() } ?: vertexMaasBaseUrl(projectId, location),
            apiKey = bearer,
            extraHeaders = extraHeaders,
            includeUsage = false,
            extractInlineReasoning = true,
            // The reference's compat model detects no model family, so neither does this: a MaaS id
            // (`openai/gpt-oss-120b-maas`, `deepseek-ai/deepseek-r1-0528-maas`) must not be read as
            // an OpenAI reasoning model and have its samplers and `max_tokens` rewritten.
            capabilitiesFor = { OpenAICompatibleCapabilities() },
            transformRequestBody = ::vertexMaasRequestBody,
        )
    }

    override fun languageModel(modelId: String): LanguageModel = VertexBearerLanguageModel(
        provider = VERTEX_MAAS_PROVIDER_ID,
        modelId = modelId,
        compat = compat,
    )
}

/** The OpenAI-compatible base for open models, on the regional host rule the rest of Vertex uses. */
internal fun vertexMaasBaseUrl(projectId: String, location: String): String =
    "https://${vertexHost(location)}/v1/projects/$projectId/locations/$location/endpoints/openapi"

/** The reference's `maxOutputTokensByModel`: the models whose `max_tokens` is filled in when unset. */
private val MAAS_DEFAULT_MAX_TOKENS: Map<String, Int> = mapOf(
    "meta/llama-4-maverick-17b-128e-instruct-maas" to 8192,
    "meta/llama-4-scout-17b-16e-instruct-maas" to 8192,
)

/** The body with Llama 4's default `max_tokens` filled in — and every other body untouched. */
internal fun vertexMaasRequestBody(body: JsonObject): JsonObject {
    val default = MAAS_DEFAULT_MAX_TOKENS[body.optString("model")] ?: return body
    val explicit = body["max_tokens"]?.takeIf { it !is JsonNull }
    return if (explicit == null) JsonObject(body + ("max_tokens" to JsonPrimitive(default))) else body
}
