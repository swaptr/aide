package com.sabreware.aide.aisdk.providers.openaicompatible

import com.sabreware.aide.aisdk.providers.cerebras.cerebrasRequestBody
import com.sabreware.aide.aisdk.providers.deepinfra.deepInfraUsage
import com.sabreware.aide.aisdk.providers.fireworks.fireworksRequestBody
import com.sabreware.aide.aisdk.providers.openai.HuggingFaceResponsesQuirks
import com.sabreware.aide.aisdk.providers.openai.OpenAIResponsesLanguageModel
import com.sabreware.aide.aisdk.providers.openai.ResponsesQuirks
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleModality.Chat
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleModality.Completion
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleModality.Embedding
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleModality.Image
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleModality.Speech
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleModality.Transcription
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.parseJsonElementOrNull
import com.sabreware.aide.aisdk.util.ProviderHttp
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The vendors that speak Chat Completions.
 *
 * Each is a base URL, an id, and the handful of things that vendor gets wrong or does differently — not
 * a class. Writing twenty near-identical provider classes is how a library ends up with twenty places to
 * fix the same bug, and the reference implementation's own per-vendor packages are mostly this same
 * table wearing more files.
 *
 * **The modality set is part of the data, and it is deliberately small.** A vendor lists only the
 * endpoints it serves in the OpenAI SHAPE. xAI runs speech at `/tts` with a `voice_id` and transcription
 * at `/stt`; Mistral's `/audio/speech` answers with `{"audio_data": "<base64>"}` rather than raw bytes.
 * Binding the shared models to those produces a call that compiles, type-checks and fails against the
 * live service, so they are absent here — a caller asking for a speech model gets null and omits the
 * affordance, which is the same answer the module graph gives for a capability a target lacks.
 *
 * A vendor absent from this list is not unsupported: [OpenAICompatibleProvider] takes any base URL. These
 * are the ones whose quirks are known, so a caller does not have to rediscover them.
 *
 * **Perplexity is deliberately absent.** Its Sonar Chat Completions wire retires on 2026-09-27, and the
 * successor — the Agent API, an OpenAI-Responses-shaped endpoint with its own tools and output items —
 * is served by [com.sabreware.aide.aisdk.providers.perplexity.PerplexityProvider]. A row here would be
 * a provider that compiles today and 404s in three weeks.
 */
public object Vendors {

    /** OpenAI itself. Reasoning models reject temperature and top_p — see [defaultCapabilities]. */
    public fun openAI(
        client: HttpClient,
        apiKey: String,
        baseUrl: String = "https://api.openai.com/v1",
    ): OpenAICompatibleProvider = OpenAICompatibleProvider(
        client = client,
        providerId = "openai",
        baseUrl = baseUrl,
        apiKey = apiKey,
        capabilitiesFor = ::openAIServedCapabilities,
        modalities = setOf(Chat, Completion, Embedding, Image, Speech, Transcription),
    )

    /**
     * Azure's OpenAI deployments.
     *
     * The model id is a *deployment* name, the key rides `api-key` rather than a bearer token, and the
     * URL needs an `api-version`. Getting any of the three wrong presents identically as a 404.
     *
     * `languageModel` is the **Responses API** on Azure's v1 surface —
     * `https://{resource}.openai.azure.com/openai/v1/responses?api-version={responsesApiVersion}`, no
     * deployment segment — which is the reference's default and the only Azure chat wire that can carry
     * a reasoning turn across a tool round. Options and metadata file under `azure` (canonical `openai`
     * always read, `azure` winning field by field). The Chat Completions deployment path stays reachable
     * through [OpenAICompatibleProvider.chatLanguageModel].
     *
     * Every OTHER modality goes through the deployment-URL builder. Overriding only the chat one left
     * embeddings, images, speech and transcription resolving to `.../openai/deployments/embeddings` —
     * no deployment segment, no `api-version` — which is a 404 on a provider that reports itself as
     * offering embeddings.
     *
     * [baseUrl] is the other way in, and wins over [resourceName]: a gateway, a complete `/openai/v1`
     * base, a Foundry project or an unversioned Foundry / Cognitive Services host — see
     * [azureRequestUrl] for how each is spelled. There the v1 form carries no deployment segment and
     * `api-version` defaults to `v1`, the value that surface documents; the deployment path keeps its
     * dated default.
     */
    public fun azure(
        client: HttpClient,
        apiKey: String,
        resourceName: String? = null,
        apiVersion: String? = null,
        /** The v1 Responses surface versions independently of the deployment endpoints. */
        // Empty by default: Microsoft's v1 GA surface documents `api-version` as NO LONGER REQUIRED, and
        // every official example calls `/openai/v1/responses` without it. `v1` is not a documented value
        // for a field that otherwise takes a date, so sending it claimed a version that does not exist.
        // A caller pinned to a dated preview passes one and it is appended as before.
        responsesApiVersion: String = "",
        baseUrl: String? = null,
    ): OpenAICompatibleProvider {
        require(resourceName != null || baseUrl != null) { "Vendors.azure needs a resourceName or a baseUrl." }
        return if (baseUrl != null) {
            azureAtBaseUrl(client, apiKey, baseUrl, apiVersion ?: AZURE_V1_API_VERSION, responsesApiVersion)
        } else {
            azureResource(
                client, apiKey, requireNotNull(resourceName), apiVersion ?: AZURE_DEPLOYMENT_API_VERSION,
                responsesApiVersion,
            )
        }
    }

    private fun azureResource(
        client: HttpClient,
        apiKey: String,
        resourceName: String,
        apiVersion: String,
        responsesApiVersion: String,
    ): OpenAICompatibleProvider = OpenAICompatibleProvider(
        client = client,
        providerId = "azure",
        baseUrl = "https://$resourceName.openai.azure.com/openai/deployments",
        extraHeaders = mapOf("api-key" to apiKey),
        urlFor = { base, deployment, path -> "$base/$deployment/$path?api-version=$apiVersion" },
        capabilitiesFor = ::openAIServedCapabilities,
        modalities = setOf(Chat, Completion, Embedding, Image, Speech, Transcription),
        languageModelOverride = { deployment ->
            OpenAIResponsesLanguageModel(
                modelId = deployment,
                http = ProviderHttp(client),
                headers = mapOf("api-key" to apiKey),
                provider = "azure.responses",
                namespace = "azure",
                endpointUrl = "https://$resourceName.openai.azure.com/openai/v1/responses" +
                    responsesApiVersion.takeIf { it.isNotBlank() }?.let { "?api-version=$it" }.orEmpty(),
            )
        },
    )

    /**
     * Azure reached through a caller's own base URL (`993e900`, `85db433`).
     *
     * Every endpoint, the Responses one included, is spelled by [azureRequestUrl]. A Foundry project
     * additionally wants `type: "message"` on every Responses input item ([AzureBaseUrlInfo.explicitMessageItemType]);
     * the Responses model in this tree has no knob for it yet, so the fact is computed here and the
     * quirk lands with the OpenAI lane's `ResponsesQuirks.explicitMessageItemType`.
     */
    private fun azureAtBaseUrl(
        client: HttpClient,
        apiKey: String,
        baseUrl: String,
        apiVersion: String,
        responsesApiVersion: String,
    ): OpenAICompatibleProvider {
        val info = azureBaseUrlInfo(baseUrl)
        val headers = mapOf("api-key" to apiKey)
        return OpenAICompatibleProvider(
            client = client,
            providerId = "azure",
            baseUrl = baseUrl,
            extraHeaders = headers,
            urlFor = { base, _, path -> azureRequestUrl(base, info, path, apiVersion) },
            capabilitiesFor = ::openAIServedCapabilities,
            modalities = setOf(Chat, Completion, Embedding, Image, Speech, Transcription),
            languageModelOverride = { deployment ->
                OpenAIResponsesLanguageModel(
                    modelId = deployment,
                    http = ProviderHttp(client),
                    headers = headers,
                    provider = "azure.responses",
                    namespace = "azure",
                    endpointUrl = azureRequestUrl(baseUrl, info, "responses", responsesApiVersion),
                    // A Foundry project wants `type: "message"` on every Responses input item (`85db433`).
                    quirks = ResponsesQuirks(explicitMessageItemType = info.explicitMessageItemType),
                )
            },
        )
    }

    /**
     * OpenRouter: hundreds of models from every major vendor behind one wire.
     *
     * The only aggregator that solved signed-reasoning replay, via `reasoning_details` — which is why its
     * id matters here. Metadata is filed under `openrouter`, so a conversation held against it replays
     * its blocks as its own rather than as some other provider's.
     *
     * [appUrl] and [appTitle] populate OpenRouter's attribution headers; both are optional.
     */
    public fun openRouter(
        client: HttpClient,
        apiKey: String,
        appUrl: String? = null,
        appTitle: String? = null,
    ): OpenAICompatibleProvider = OpenAICompatibleProvider(
        client = client,
        providerId = "openrouter",
        baseUrl = "https://openrouter.ai/api/v1",
        apiKey = apiKey,
        extraHeaders = buildMap {
            appUrl?.let { put("HTTP-Referer", it) }
            appTitle?.let { put("X-Title", it) }
        },
    )

    /**
     * Vercel's AI Gateway, over its OpenAI-compatible endpoint.
     *
     * Gateway's NATIVE protocol is not this: it posts the AI SDK's own call options as the body with the
     * model in an `ai-language-model-id` header, so its wire is the specification itself serialized in
     * Vercel's private JSON shape. Porting that would mean byte-matching a format no public document
     * pins down, and a mismatch produces a provider that compiles and fails against the live service —
     * see aisdk/DESIGN.md.
     *
     * The compatible endpoint is documented, reachable and testable, so that is what this uses. Model
     * ids are `creator/model`, e.g. `anthropic/claude-sonnet-4`.
     */
    public fun vercelGateway(
        client: HttpClient,
        apiKey: String,
    ): OpenAICompatibleProvider = OpenAICompatibleProvider(
        client = client,
        providerId = "gateway",
        baseUrl = "https://ai-gateway.vercel.sh/v1",
        apiKey = apiKey,
        modalities = setOf(Chat, Embedding, Image),
    )

    /**
     * Groq. Raw-mode deployments embed reasoning inline, so tag extraction is on.
     *
     * `stream_options` is off because Groq does not implement it: it reports its token counts on
     * `x_groq.usage` and nowhere else. Sending the field and reading `usage` produced a streamed turn
     * whose usage was null on every call.
     *
     * `includeUsage` is off, but not because the field is refused: Groq's API reference lists
     * `stream_options` as accepted. It simply does not honour it — usage arrives on the vendor's own
     * `x_groq` envelope instead, which this library reads unconditionally, so the counts are not lost.
     * Accepted-but-ignored, rather than rejected.
     *
     * Two corrections the fixture suite exposed, both checked against Groq's own docs on 2026-09-02:
     * `reasoning_effort` takes `low`/`medium`/`high` (gpt-oss) or `none`/`default`/`medium`/`high`
     * (Qwen 3.x), never `minimal`, so the engine's spelling of `Minimal` is rewritten to `low` as the
     * reference does; and `response_format: json_schema` is served on exactly four listed models, so
     * the row turns structured outputs on for those and downgrades everything else to `json_object`
     * with a warning rather than sending a shape the model rejects.
     *
     * No transcription. Groq's `/audio/transcriptions` is near OpenAI's and not it — its
     * `timestamp_granularities[]` is a repeated bracketed field where a JSON array is accepted and then
     * ignored, so the shared model produced calls that succeeded and returned no timings. It has its own
     * wire at [com.sabreware.aide.aisdk.providers.groq.GroqProvider]; binding it here as well would give
     * one vendor two implementations of one modality, and the table's is the wrong one.
     */
    public fun groq(client: HttpClient, apiKey: String): OpenAICompatibleProvider =
        OpenAICompatibleProvider(
            client = client,
            providerId = "groq",
            baseUrl = "https://api.groq.com/openai/v1",
            apiKey = apiKey,
            extractInlineReasoning = true,
            includeUsage = false,
            modalities = setOf(Chat),
            // `json_schema` is served on four documented models and nowhere else — see the function.
            capabilitiesFor = ::groqCapabilities,
            // `minimal` is not in Groq's effort vocabulary — see the transform.
            transformRequestBody = ::groqRequestBody,
            // Without this, `groqBrowserSearch()` would be a tool the shared path drops with a
            // warning: a provider-defined tool reaches this wire only through a dialect, because
            // `tools` is a reserved key that providerOptions passthrough cannot write.
            providerToolDialect = ProviderToolDialect.Groq,
        )

    /**
     * Together AI: hosted open-weight models, faithful to the wire.
     *
     * Image generation binds because Together's `/images/generations` genuinely answers in the OpenAI
     * shape — where DeepInfra's and Fireworks' image APIs only look like it. No reranking here:
     * Together's rerank endpoint is its own shape, served natively by
     * [com.sabreware.aide.aisdk.providers.togetherai.TogetherAiProvider]. No quirk flags otherwise.
     */
    public fun together(client: HttpClient, apiKey: String): OpenAICompatibleProvider =
        OpenAICompatibleProvider(
            client = client,
            providerId = "togetherai",
            baseUrl = "https://api.together.xyz/v1",
            apiKey = apiKey,
            modalities = setOf(Chat, Completion, Embedding, Image),
        )

    /**
     * Fireworks. Some deployments inline reasoning in `content`.
     *
     * Its `error` is a string on some paths and an object on others; `defaultErrorMessage` reads both,
     * which is why there is no Fireworks-specific error structure here.
     *
     * No image generation HERE: Fireworks' image API is not this wire at all — model-in-the-path
     * `workflows/{model}` URLs, binary responses and an async polling variant. Binding the shared
     * `/images/generations` model to this entry 404'd every call. Images come from
     * `FireworksProvider`, which speaks that URL family natively.
     */
    public fun fireworks(client: HttpClient, apiKey: String): OpenAICompatibleProvider =
        OpenAICompatibleProvider(
            client = client,
            providerId = "fireworks",
            baseUrl = "https://api.fireworks.ai/inference/v1",
            apiKey = apiKey,
            extractInlineReasoning = true,
            modalities = setOf(Chat, Completion, Embedding),
            // `minimal` is the one effort level Fireworks' schema does not list — see the transform.
            transformRequestBody = ::fireworksRequestBody,
        )

    /**
     * DeepInfra.
     *
     * No image generation HERE: DeepInfra's image endpoint is `/v1/inference/{modelId}` answering
     * `images[]` of data URIs — not this wire's `/images/generations` with `data[].b64_json`, so the
     * shared model 404s there. Images come from `DeepInfraProvider`, which posts to that endpoint.
     */
    public fun deepInfra(client: HttpClient, apiKey: String): OpenAICompatibleProvider =
        OpenAICompatibleProvider(
            client = client,
            providerId = "deepinfra",
            baseUrl = "https://api.deepinfra.com/v1/openai",
            apiKey = apiKey,
            modalities = setOf(Chat, Completion, Embedding),
            // Gemini and Gemma deployments here report reasoning tokens BESIDE the completion count
            // rather than inside it; subtracting zeroes the text count. See the converter.
            convertUsage = ::deepInfraUsage,
        )

    /**
     * Cerebras.
     *
     * Its GLM deployments can answer a structured-output request with valid JSON text AND a repeated
     * tool call, finishing with `tool_calls` — which a loop keyed on the finish reason then treats as
     * a paused turn. The flag makes the shared model treat that mixed response as the final answer.
     */
    public fun cerebras(client: HttpClient, apiKey: String): OpenAICompatibleProvider =
        OpenAICompatibleProvider(
            client = client,
            providerId = "cerebras",
            baseUrl = "https://api.cerebras.ai/v1",
            apiKey = apiKey,
            jsonToolCallsFinishIsStop = true,
            // This API has no `max_tokens` at all, and spells an assistant turn's prior reasoning
            // `reasoning`. Both are keys the engine writes, so they are corrected on the body.
            transformRequestBody = ::cerebrasRequestBody,
        )

    /**
     * xAI.
     *
     * Its error body is a three-way union — `{error: {message}}`, `{error: "…"}` and a bare `{code,
     * error}`. All three land on `defaultErrorMessage`, which reads `error` as an object OR as a string;
     * a reader that knew only the first reported "HTTP 400" with no explanation on two of the three.
     *
     * No speech or transcription: xAI runs those at `/tts` and `/stt` with its own request shapes, not
     * OpenAI's.
     */
    public fun xai(client: HttpClient, apiKey: String): OpenAICompatibleProvider =
        OpenAICompatibleProvider(
            client = client,
            providerId = "xai",
            baseUrl = "https://api.x.ai/v1",
            apiKey = apiKey,
            modalities = setOf(Chat, Image),
        )

    /**
     * Hugging Face's Inference Providers router: many downstream hosts behind one endpoint, one token.
     *
     * Model ids are hub ids — `moonshotai/Kimi-K2-Instruct` — and the router picks which host serves
     * each call.
     *
     * `languageModel` is the router's **Responses API** (`/v1/responses`), the reference's default,
     * reporting itself as `huggingface.responses` with options under `huggingface`. Its dialect is the
     * SMALL one: plain finish strings, function tools only (a provider-defined tool is refused with a
     * warning), and a named tool choice nests as `{"type":"function","function":{"name":…}}`. The Chat
     * Completions path stays reachable through [OpenAICompatibleProvider.chatLanguageModel]. No other
     * quirk flags, deliberately: whichever downstream host answers is the one whose behaviour shows
     * through, so nothing host-specific can be encoded on the entry.
     */
    public fun huggingFace(client: HttpClient, apiKey: String): OpenAICompatibleProvider =
        OpenAICompatibleProvider(
            client = client,
            providerId = "huggingface",
            baseUrl = "https://router.huggingface.co/v1",
            apiKey = apiKey,
            languageModelOverride = { modelId ->
                OpenAIResponsesLanguageModel(
                    modelId = modelId,
                    http = ProviderHttp(client),
                    headers = mapOf("Authorization" to "Bearer $apiKey"),
                    provider = "huggingface.responses",
                    namespace = "huggingface",
                    endpointUrl = "https://router.huggingface.co/v1/responses",
                    quirks = HuggingFaceResponsesQuirks,
                )
            },
        )

    /**
     * ByteDance's Ark, for IMAGE generation.
     *
     * Its image endpoint is `/images/generations` returning `data[].b64_json` under a bearer token —
     * the OpenAI URL and response shape — but the REQUEST body departs enough to need its own dialect:
     * image-to-image inputs, nested option objects, and no `n` field. Video is not this wire: it is a
     * separate async task API, served by `BytedanceProvider`.
     */
    public fun byteDance(
        client: HttpClient,
        apiKey: String,
        baseUrl: String = "https://ark.ap-southeast.bytepluses.com/api/v3",
    ): OpenAICompatibleProvider = OpenAICompatibleProvider(
        client = client,
        providerId = "bytedance",
        baseUrl = baseUrl,
        apiKey = apiKey,
        modalities = setOf(Chat, Image),
        // Ark's body departs from OpenAI's: image inputs, nested option objects, no `n`.
        imageDialect = ImageRequestDialect.ByteDance,
    )

    /**
     * GMI Cloud.
     *
     * Belongs in this table rather than in its own package: the reference's own implementation simply
     * extends its OpenAI-compatible chat model, so a separate wire model here would be a copy of one.
     *
     * Its errors carry the actionable half in `error.details` and leave `error.message` generic, so the
     * message is folded rather than read from one field.
     */
    public fun gmiCloud(client: HttpClient, apiKey: String): OpenAICompatibleProvider =
        OpenAICompatibleProvider(
            client = client,
            providerId = "gmicloud",
            baseUrl = "https://api.gmi-serving.com/v1",
            apiKey = apiKey,
            errorStructure = GMI_CLOUD_ERRORS,
        )

    /**
     * Baseten's model APIs — its dedicated deployments live on per-model URLs and want [custom].
     *
     * No dedicated error structure despite its `error` being a string on some paths and an object on
     * others: `defaultErrorMessage` already reads both, which is the reason that reader handles the
     * union at all.
     */
    public fun baseten(client: HttpClient, apiKey: String): OpenAICompatibleProvider =
        OpenAICompatibleProvider(
            client = client,
            providerId = "baseten",
            baseUrl = "https://inference.baseten.co/v1",
            apiKey = apiKey,
            modalities = setOf(Chat, Embedding),
        )

    /**
     * Ollama, running locally.
     *
     * Keyless by design, and `stream_options` is rejected by older builds — losing token counts is a far
     * better outcome than every request failing, so it is off.
     */
    public fun ollama(
        client: HttpClient,
        baseUrl: String = "http://localhost:11434/v1",
    ): OpenAICompatibleProvider = OpenAICompatibleProvider(
        client = client,
        providerId = "ollama",
        baseUrl = baseUrl,
        // Local distills of R1 and friends commonly inline their reasoning.
        extractInlineReasoning = true,
        modalities = setOf(Chat, Embedding),
    )

    /**
     * LM Studio's local server. Keyless, same inline-reasoning caveat as Ollama.
     *
     * `includeUsage` stays off here, unlike Ollama and llama.cpp: LM Studio's OpenAI-compatibility page
     * documents the endpoints it serves but says nothing either way about `stream_options`, and an
     * unverified flag that can fail a request is not worth a token count.
     */
    public fun lmStudio(
        client: HttpClient,
        baseUrl: String = "http://localhost:1234/v1",
    ): OpenAICompatibleProvider = OpenAICompatibleProvider(
        client = client,
        providerId = "lmstudio",
        baseUrl = baseUrl,
        includeUsage = false,
        extractInlineReasoning = true,
        modalities = setOf(Chat, Embedding),
    )

    /** vLLM, self-hosted. Keyless unless the operator configured one. */
    public fun vllm(
        client: HttpClient,
        baseUrl: String,
        apiKey: String? = null,
    ): OpenAICompatibleProvider = OpenAICompatibleProvider(
        client = client,
        providerId = "vllm",
        baseUrl = baseUrl,
        apiKey = apiKey,
        extractInlineReasoning = true,
        modalities = setOf(Chat, Embedding),
    )

    /**
     * llama.cpp's own `llama-server`. Keyless.
     *
     * `stream_options.include_usage` IS implemented — the claim that it is not was true of an older
     * build and cost a null token count on every streamed turn. llama-server puts the usage on the final
     * chunk beside a `finish_reason` and an empty delta, rather than in the separate empty-choices chunk
     * OpenAI specifies; this library reads usage off whichever chunk carries it, so that shape needs
     * nothing special.
     */
    public fun llamaCpp(
        client: HttpClient,
        baseUrl: String = "http://localhost:8080/v1",
    ): OpenAICompatibleProvider = OpenAICompatibleProvider(
        client = client,
        providerId = "llamacpp",
        baseUrl = baseUrl,
        extractInlineReasoning = true,
        modalities = setOf(Chat, Embedding),
    )

    /**
     * Anything else that speaks the wire.
     *
     * The escape hatch that keeps the table above from being a gate: a vendor nobody has added yet works
     * today, it simply arrives without its quirks pre-filled. [modalities] defaults to chat alone —
     * claiming an endpoint on a server nobody has checked is how a caller learns about a 404 from a user.
     */
    public fun custom(
        client: HttpClient,
        providerId: String,
        baseUrl: String,
        apiKey: String? = null,
        extractInlineReasoning: Boolean = false,
        includeUsage: Boolean = true,
        capabilities: ((modelId: String) -> OpenAICompatibleCapabilities)? = null,
        modalities: Set<OpenAICompatibleModality> = setOf(Chat),
    ): OpenAICompatibleProvider = OpenAICompatibleProvider(
        client = client,
        providerId = providerId,
        baseUrl = baseUrl,
        apiKey = apiKey,
        extractInlineReasoning = extractInlineReasoning,
        includeUsage = includeUsage,
        capabilitiesFor = capabilities ?: ::defaultCapabilities,
        modalities = modalities,
    )
}

/**
 * GMI Cloud's error shape.
 *
 * `error.message` is the generic family ("Invalid request"), and the sentence naming the actual problem
 * is in `error.details` — which is not prose but a JSON DOCUMENT encoded as a string, with the real
 * message at its own `error.message`. So the inner sentence REPLACES the outer family rather than being
 * appended to it: concatenating produced `Invalid request: {"error":{"message":"…"}}`, which shows the
 * caller a raw payload where a sentence belongs.
 *
 * Every way the unwrap can fail — absent, empty, unparseable, or carrying no string message — falls back
 * to the outer family, because a generic sentence beats none.
 */
private val GMI_CLOUD_ERRORS = ProviderErrorStructure(
    extractMessage = { body ->
        val error = (body as? JsonObject)?.get("error") as? JsonObject
        val message = error?.get("message")?.stringOrNull()
        error?.get("details")?.stringOrNull()?.let { unwrapGmiCloudDetails(it) } ?: message
    },
)

/** The sentence inside GMI Cloud's JSON-encoded `details`, or null if it does not hold one. */
private fun unwrapGmiCloudDetails(details: String): String? {
    val parsed = parseJsonElementOrNull(details) as? JsonObject ?: return null
    val inner = (parsed["error"] as? JsonObject)?.get("message")?.stringOrNull()
    return inner?.takeIf { it.isNotBlank() }
}

private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Which Groq models implement `response_format: json_schema`.
 *
 * Groq's structured-outputs page (checked 2026-09-02) lists exactly these — `openai/gpt-oss-20b`,
 * `openai/gpt-oss-120b` and `qwen/qwen3.8-27b` with `strict: true`, plus `openai/gpt-oss-safeguard-20b`
 * best-effort — and says every other model gets "JSON Object Mode ... though it may not match your
 * schema". The reference turns `json_schema` on for every model by default, which sends a shape the
 * other models do not implement; the vendor's own list wins. An allow-list rather than a pattern, like
 * `GROQ_BROWSER_SEARCH_MODELS`: a model outside it is downgraded to `json_object` with a warning, which
 * is a request that works and says what it lost.
 */
private val GROQ_JSON_SCHEMA_MODELS = setOf(
    "openai/gpt-oss-20b",
    "openai/gpt-oss-120b",
    "openai/gpt-oss-safeguard-20b",
    "qwen/qwen3.8-27b",
)

private fun groqCapabilities(modelId: String): OpenAICompatibleCapabilities =
    defaultCapabilities(modelId).copy(supportsStructuredOutputs = modelId in GROQ_JSON_SCHEMA_MODELS)

/**
 * Groq's reasoning vocabulary has no `minimal`.
 *
 * Its reasoning page documents `low`/`medium`/`high` for gpt-oss and `none`/`default`/`medium`/`high`
 * for Qwen 3.x; the engine spells `ReasoningEffort.Minimal` as OpenAI's `minimal`, which Groq rejects.
 * `low` is the nearest level that exists and is what the reference sends, so this is the Fireworks
 * transform for a second vendor — a key the ENGINE wrote, corrected on the body. `xhigh` never reaches
 * here: the engine already clamps it to `high`.
 */
private fun groqRequestBody(body: JsonObject): JsonObject {
    if ((body["reasoning_effort"] as? JsonPrimitive)?.content != "minimal") return body
    return JsonObject(body + ("reasoning_effort" to JsonPrimitive("low")))
}

/** The deployment path's dated default; the v1 surface documents `v1` instead. */
private const val AZURE_DEPLOYMENT_API_VERSION = "2024-10-21"
private const val AZURE_V1_API_VERSION = "v1"

/** OpenAI's own restrictions apply on the rows OpenAI serves: Azure and api.openai.com. */
private fun openAIServedCapabilities(modelId: String): OpenAICompatibleCapabilities =
    defaultCapabilities(modelId).copy(normalizesJsonSchema = true)
