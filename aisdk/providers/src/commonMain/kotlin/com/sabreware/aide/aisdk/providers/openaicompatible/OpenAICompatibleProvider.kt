package com.sabreware.aide.aisdk.providers.openaicompatible

import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.Usage
import kotlinx.serialization.json.JsonObject
import io.ktor.client.HttpClient

/**
 * Any server speaking the OpenAI Chat Completions wire.
 *
 * The name matches the reference implementation's `@ai-sdk/openai-compatible` deliberately. It is
 * slightly inaccurate — Ollama and Groq are not OpenAI, they merely speak its wire — but someone arriving
 * from that package's documentation should find the same name here, and every other provider package in
 * this module already matches its counterpart.
 *
 * One class covers OpenAI, Ollama, LM Studio, vLLM, llama.cpp, Groq, Together, Fireworks, DeepSeek,
 * OpenRouter and the rest, because the difference between them is configuration rather than code:
 * a base URL, an id, and the handful of parameters a given model rejects.
 *
 * [providerId] is not cosmetic. It is the key that reasoning metadata is filed under, so a conversation
 * carried out against OpenRouter replays its blocks as OpenRouter's rather than someone else's.
 *
 * [apiKey] is optional: local runtimes are keyless, and sending an empty Authorization header to one is
 * how a working Ollama setup starts returning 401.
 */
public class OpenAICompatibleProvider(
    client: HttpClient,
    override val providerId: String,
    private val baseUrl: String,
    /**
     * Builds the complete endpoint for one modality of one model.
     *
     * Defaults to `{base}/{path}`, which is what every vendor but two uses. It takes the modality path
     * rather than only the chat one because Azure puts the deployment name and an `api-version` into
     * EVERY endpoint: a chat-only override left embeddings, images, speech and transcription resolving
     * to `.../openai/deployments/embeddings`, which 404s.
     */
    private val urlFor: (baseUrl: String, modelId: String, path: String) -> String = ::defaultEndpointUrl,
    private val apiKey: String? = null,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val extractInlineReasoning: Boolean = false,
    /**
     * What a given model of this vendor can and cannot do.
     *
     * A function of the model id rather than a constant, because one vendor serves both reasoning and
     * ordinary models and they reject different parameters.
     */
    private val capabilitiesFor: (modelId: String) -> OpenAICompatibleCapabilities =
        ::defaultCapabilities,
    private val includeUsage: Boolean = true,
    private val toolChoiceDialect: ToolChoiceDialect = ToolChoiceDialect.OpenAI,
    /**
     * Replaces the token arithmetic for this vendor — see [defaultOpenAICompatibleUsage].
     *
     * Null keeps the OpenAI reading. A vendor whose cache or reasoning counters live under names this
     * wire model does not know supplies one; it receives the raw `usage` object.
     */
    private val convertUsage: ((JsonObject) -> Usage)? = null,
    /**
     * Last word on the chat request body — see the model's own parameter for what it reaches.
     *
     * For a vendor that needs a key this engine WRITES rewritten (a different spelling of the token
     * ceiling, a narrower set of reasoning-effort levels). Not for renaming a caller's own options:
     * those are spread verbatim and already arrive correctly.
     */
    private val transformRequestBody: ((JsonObject) -> JsonObject)? = null,
    /** Which of its own server-side tools this vendor extends Chat Completions with, if any. */
    private val providerToolDialect: ProviderToolDialect = ProviderToolDialect.None,
    private val errorStructure: ProviderErrorStructure = ProviderErrorStructure.Default,
    /** How this vendor's image endpoint departs from OpenAI's — ByteDance is the current case. */
    private val imageDialect: ImageRequestDialect = ImageRequestDialect.OpenAI,
    /** See [OpenAICompatibleLanguageModel]: Cerebras' structured-output-with-tool-calls correction. */
    private val jsonToolCallsFinishIsStop: Boolean = false,
    /**
     * Which endpoints this vendor actually serves.
     *
     * Chat-only is the default because that is the one endpoint the wire is named after. Returning a
     * model for every modality regardless made Groq, Cerebras, DeepSeek, Perplexity, Moonshot and Ollama
     * all advertise image generation they answer with a 404 — a capability the caller cannot tell from a
     * working one until it fails at runtime, which is exactly what a null return exists to prevent.
     */
    private val modalities: Set<OpenAICompatibleModality> = setOf(OpenAICompatibleModality.Chat),
    /**
     * A different default language model, for the vendors whose primary chat surface is NOT Chat
     * Completions — Azure and Hugging Face default to the Responses API upstream. The Chat Completions
     * model stays reachable through [chatLanguageModel], the way the reference keeps `provider.chat`
     * beside its Responses default.
     */
    private val languageModelOverride: ((modelId: String) -> LanguageModel)? = null,
) : Provider {

    private val http = ProviderHttp(client).withErrorStructure(errorStructure)

    private fun endpointHeaders(): Map<String, String> = buildMap {
        apiKey?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        putAll(extraHeaders)
    }

    private fun url(modelId: String, path: String): String = urlFor(baseUrl.trimEnd('/'), modelId, path)

    override fun embeddingModel(modelId: String): EmbeddingModel? =
        ifServed(OpenAICompatibleModality.Embedding) {
            OpenAICompatibleEmbeddingModel(
                provider = providerId,
                modelId = modelId,
                http = http,
                url = url(modelId, "embeddings"),
                headers = endpointHeaders(),
            )
        }

    override fun imageModel(modelId: String): ImageModel? = ifServed(OpenAICompatibleModality.Image) {
        OpenAICompatibleImageModel(
            provider = providerId,
            modelId = modelId,
            http = http,
            url = url(modelId, "images/generations"),
            headers = endpointHeaders(),
            dialect = imageDialect,
        )
    }

    override fun speechModel(modelId: String): SpeechModel? = ifServed(OpenAICompatibleModality.Speech) {
        OpenAICompatibleSpeechModel(
            provider = providerId,
            modelId = modelId,
            http = http,
            url = url(modelId, "audio/speech"),
            headers = endpointHeaders(),
        )
    }

    override fun transcriptionModel(modelId: String): TranscriptionModel? =
        ifServed(OpenAICompatibleModality.Transcription) {
            OpenAICompatibleTranscriptionModel(
                provider = providerId,
                modelId = modelId,
                http = http,
                url = url(modelId, "audio/transcriptions"),
                headers = endpointHeaders(),
            )
        }

    override fun languageModel(modelId: String): LanguageModel? = ifServed(OpenAICompatibleModality.Chat) {
        languageModelOverride?.invoke(modelId) ?: buildChatModel(modelId)
    }

    /**
     * The Chat Completions model, whatever [languageModel] defaults to.
     *
     * On most vendors the two are the same model; on one with a Responses default (Azure, Hugging Face)
     * this is the explicit route to the older wire — a gateway that serves only Chat Completions, a
     * deployment pinned to it.
     */
    public fun chatLanguageModel(modelId: String): LanguageModel? =
        ifServed(OpenAICompatibleModality.Chat) { buildChatModel(modelId) }

    /**
     * The legacy Completions endpoint, for the base and instruct models that answer nowhere else.
     *
     * Null wherever the vendor does not list [OpenAICompatibleModality.Completion], exactly like the
     * other modalities. The model reports itself as `<id>.completion` — the reference's convention —
     * while reading options from, and filing its `logprobs` under, this provider's own id.
     */
    public fun completionModel(modelId: String): LanguageModel? =
        ifServed(OpenAICompatibleModality.Completion) {
            OpenAICompatibleCompletionLanguageModel(
                provider = "$providerId.completion",
                modelId = modelId,
                http = http,
                completionsUrl = url(modelId, "completions"),
                headers = endpointHeaders(),
                namespace = providerId,
                convertUsage = convertUsage,
                transformRequestBody = transformRequestBody,
                includeUsage = includeUsage,
                errorStructure = errorStructure,
            )
        }

    private fun buildChatModel(modelId: String): LanguageModel = OpenAICompatibleLanguageModel(
        provider = providerId,
        modelId = modelId,
        http = http,
        chatUrl = url(modelId, "chat/completions"),
        headers = endpointHeaders(),
        extractInlineReasoning = extractInlineReasoning,
        capabilities = capabilitiesFor(modelId),
        toolChoiceDialect = toolChoiceDialect,
        convertUsage = convertUsage,
        transformRequestBody = transformRequestBody,
        providerToolDialect = providerToolDialect,
        includeUsage = includeUsage,
        errorStructure = errorStructure,
        jsonToolCallsFinishIsStop = jsonToolCallsFinishIsStop,
    )

    private inline fun <T> ifServed(modality: OpenAICompatibleModality, build: () -> T): T? =
        if (modality in modalities) build() else null
}

/**
 * One endpoint family on this wire.
 *
 * [Completion] is the legacy `/completions` endpoint — a single prompt string in, one text out — which
 * only five vendors still serve (OpenAI, Azure, Fireworks, Together, DeepInfra, each verified against
 * the reference's provider file). It is listed rather than assumed for the same reason images are: a
 * server that never served it answers 404, and null is the answer a caller can act on.
 */
public enum class OpenAICompatibleModality { Chat, Completion, Embedding, Image, Speech, Transcription }

/** `{base}/{path}`, which is what every vendor but Azure and Perplexity uses. */
public fun defaultEndpointUrl(
    baseUrl: String,
    @Suppress("UNUSED_PARAMETER") modelId: String,
    path: String,
): String = "$baseUrl/$path"

/**
 * Which family a model id belongs to, and therefore what it rejects.
 *
 * The version is PARSED out of the id rather than prefix-matched, which is the part worth copying from
 * the reference exactly. Prefix matching gets three real ids wrong: `gpt-5-chat-latest` is not a
 * reasoning model despite starting with `gpt-5`, `gpt-5.1` accepts samplers that `gpt-5` rejects, and
 * an `o5` that has not shipped yet would be classified as an ordinary model by a hard-coded o1/o3/o4
 * list — silently sending it samplers that 400.
 */
public fun defaultCapabilities(modelId: String): OpenAICompatibleCapabilities {
    val id = modelId.lowercase()
    val oSeries = Regex("""(^|-)o(\d+)(-|$)""").find(id)?.groupValues?.get(2)?.toIntOrNull()
    val gptVersion = Regex("""(^|-)gpt-(\d+)(?:\.(\d+))?""").find(id)
    val gptMajor = gptVersion?.groupValues?.get(2)?.toIntOrNull()
    val gptMinor = gptVersion?.groupValues?.get(3)?.toIntOrNull() ?: 0

    // `gpt-5-chat-latest` is the non-reasoning chat model in the gpt-5 family; the suffix is the only
    // thing distinguishing it, and treating it as a reasoning model strips every sampler the caller set.
    val isChatVariant = "-chat" in id
    val isReasoning = when {
        oSeries != null -> true
        gptMajor != null && gptMajor >= GPT_REASONING_MAJOR && !isChatVariant -> true
        else -> false
    }

    // GPT-6 closed the effort list (no `none`, so no sampler exemption either) and retired
    // `prompt_cache_retention` — the reference's `getOpenAILanguageModelCapabilities` (`17e489e`).
    val isGpt6OrLater = gptMajor != null && gptMajor >= GPT_CLOSED_EFFORT_MAJOR

    return OpenAICompatibleCapabilities(
        isReasoningModel = isReasoning,
        // gpt-5.1 and later accept temperature and top_p when the effort is explicitly `none`.
        supportsNonReasoningParameters = !isGpt6OrLater && isReasoning && gptMajor != null &&
            (gptMajor > GPT_REASONING_MAJOR || gptMinor >= 1),
        supportedReasoningEfforts = if (isGpt6OrLater) GPT6_REASONING_EFFORTS else null,
        // OpenAI's own models implement `response_format: json_schema`; a self-hosted server usually
        // does not, so the vendor table turns it on rather than this default guessing.
        supportsStructuredOutputs = oSeries != null || gptMajor != null,
        supportsTopK = false,
    )
}

/** gpt-5 is the first OpenAI generation whose base models reason by default. */
private const val GPT_REASONING_MAJOR = 5

/** gpt-6 is the first generation that enforces a closed `reasoning_effort` list. */
private const val GPT_CLOSED_EFFORT_MAJOR = 6

/** In the order the reference names them, which is the order the warning lists them. */
private val GPT6_REASONING_EFFORTS = setOf("low", "medium", "high", "xhigh", "max")
