package com.sabreware.aide.data.catalog
import com.sabreware.aide.core.domain.model.ChatCapabilities
import com.sabreware.aide.core.domain.model.ModelBackend
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.model.RemoteLlmModel

/**
 * Mints remote chat [ModelSpec]s from a provider's `/v1/models` listing. Capabilities are DATA, not code:
 * they come from the models.dev registry (`RemoteModelMetadata`, a bundled Compose resource in commonMain),
 * matched by model id — so adding models or changing their capabilities upstream needs no edits here, and the
 * UI (which gates affordances on [ChatCapabilities]) flexes automatically. Caps are passed in as the [caps]
 * lookup lambda (the provider builders inject `RemoteModelMetadata::capabilitiesFor`) rather than referenced
 * directly, keeping this decoupled from the registry. There are NO per-model markers or per-provider
 * capability guesses. Ids the registry doesn't know (exotic / local / custom tags) fall back to [NEUTRAL].
 */
object RemoteCatalog {

    // Uniform fallback for unmatched ids: plain chat + locally dispatched tools (which no-op if the model
    // ignores them). No per-model heuristics — an unknown model is just a basic chat model until the
    // registry (or a future manual override) says otherwise.
    private val NEUTRAL = ChatCapabilities(toolsLocal = true, maxContext = 8192, maxOutput = 4096)

    private fun resolve(caps: (String) -> ChatCapabilities?, modelId: String): ChatCapabilities =
        caps(modelId) ?: NEUTRAL

    // OpenAI-compatible (OpenAI / Ollama / OpenRouter / Groq / vLLM / LM Studio). Every builder names the model
    // after the CONNECTION that serves it (`<connectionId>:<wireId>`): two accounts listing the same wire model
    // are two models, each routed to its own key. remoteName is the bare wire id. The `:aisdk` compat
    // provider wires every modality, so caps pass through.
    fun openAiSpec(provider: ProviderId, modelId: String, caps: (String) -> ChatCapabilities?): ChatModelSpec = remoteSpec(
        provider = provider,
        modelId = modelId,
        family = modelId.substringBefore('/').substringBefore(':'),
        capabilities = resolve(caps, modelId),
        licenseUrl = "",
    )

    // Gemini via the `:aisdk` native Google provider — also wires every modality, so caps pass through unchanged.
    fun geminiSpec(provider: ProviderId, modelId: String, caps: (String) -> ChatCapabilities?, displayName: String? = null): ChatModelSpec = remoteSpec(
        provider = provider,
        modelId = modelId,
        displayName = displayName,
        family = modelId.substringBefore("-2").substringBefore("-3").ifEmpty { modelId },
        capabilities = resolve(caps, modelId),
        licenseUrl = "https://ai.google.dev/gemini-api/terms",
    )

    // Anthropic (Claude) via the `:aisdk` native Anthropic provider, which sends images, documents,
    // structured output, reasoning and tools — so caps pass through unchanged. (A per-codec ceiling used
    // to strip vision here for a hand-rolled codec that could not send it; that codec is gone.)
    fun anthropicSpec(provider: ProviderId, modelId: String, caps: (String) -> ChatCapabilities?, displayName: String? = null): ChatModelSpec = remoteSpec(
        provider = provider,
        modelId = modelId,
        displayName = displayName,
        family = modelId.substringBefore('-').ifEmpty { modelId },
        capabilities = resolve(caps, modelId),
        licenseUrl = "https://www.anthropic.com/legal/commercial-terms",
    )

    private fun remoteSpec(
        provider: ProviderId,
        modelId: String,
        family: String,
        capabilities: ChatCapabilities,
        licenseUrl: String,
        displayName: String? = null,
    ): ChatModelSpec = RemoteLlmModel(
        id = "${provider.value}:$modelId",
        displayName = displayName?.takeIf { it.isNotBlank() } ?: modelId,
        family = family,
        params = "",
        quantization = "(cloud)",
        remoteName = modelId,
        cloud = true,
        minRamGb = 0,
        recommendedRamGb = 0,
        capabilities = capabilities,
        provider = provider,
        defaultBackend = ModelBackend.GPU,
        licenseName = "(provider terms)",
        licenseUrl = licenseUrl,
        sourceUrl = "",
    )
}
