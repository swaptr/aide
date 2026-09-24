package com.sabreware.aide.data.llm.vendor

import com.sabreware.aide.core.domain.connection.Connection
import com.sabreware.aide.core.domain.connection.ConnectionRuntime
import com.sabreware.aide.core.domain.connection.ServiceDescriptor
import com.sabreware.aide.core.domain.connection.Vendor
import com.sabreware.aide.core.domain.connection.VendorDescriptor
import com.sabreware.aide.core.domain.connection.VendorId
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.provider.ConnectionTestResult
import com.sabreware.aide.core.domain.provider.ProviderConfig
import com.sabreware.aide.core.domain.speech.resolveRemoteName
import com.sabreware.aide.data.catalog.RemoteCatalog
import com.sabreware.aide.data.catalog.RemoteModelMetadata
import com.sabreware.aide.data.image.ImageModelTemplates
import com.sabreware.aide.data.llm.aisdk.AiSdkImageEngine
import com.sabreware.aide.data.llm.aisdk.AiSdkLlmEngine
import com.sabreware.aide.data.llm.aisdk.AiSdkModelCatalog
import com.sabreware.aide.data.llm.aisdk.AiSdkSpeechEngine
import com.sabreware.aide.data.llm.aisdk.AiSdkTranscriptionEngine
import com.sabreware.aide.data.llm.aisdk.CompatVendors
import com.sabreware.aide.data.llm.aisdk.ConfiguredProvider
import com.sabreware.aide.data.llm.aisdk.OpenAiModalityProvider
import com.sabreware.aide.data.llm.ollama.OllamaMetadataProbe
import com.sabreware.aide.data.llm.openai.OpenAiCatalog
import com.sabreware.aide.data.llm.remote.RemoteChatProvider
import com.sabreware.aide.data.llm.remote.RemoteProviderManagement
import com.sabreware.aide.data.speech.cloud.CloudSpeechTemplates
import com.sabreware.aide.data.speech.cloud.CloudSttEngine
import com.sabreware.aide.data.speech.cloud.CloudTtsEngine
import com.sabreware.aide.data.speech.cloud.CloudTtsFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * OpenAI-compatible: one `/v1/chat/completions` wire serving OpenAI, Ollama (self-hosted or cloud),
 * OpenRouter, Groq, vLLM, LM Studio… A connection is one endpoint + key, and which `:aisdk` vendor ROW backs
 * it is its base URL's decision ([CompatVendors]), so Groq's usage envelope, Ollama's rejected
 * `stream_options` and every other documented quirk apply per connection, and so does the set of modalities:
 * an Ollama connection lists no speech or image models because its endpoint serves none.
 */
class OpenAiCompatibleVendor(private val env: VendorEnvironment) : Vendor {

    private companion object {
        val CHAT = setOf(Modality.Chat)
        val ALL = setOf(Modality.Chat, Modality.Asr, Modality.Tts, Modality.Image)
    }

    override val descriptor = VendorDescriptor(
        id = VendorId.OPENAI_COMPATIBLE,
        displayName = "OpenAI compatible",
        services = listOf(
            service("openai", "OpenAI", "https://api.openai.com/v1", ALL, blurb = "GPT, Whisper, TTS and image models.", helpUrl = "https://platform.openai.com/api-keys"),
            service("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", CHAT, blurb = "Hundreds of models behind one key.", helpUrl = "https://openrouter.ai/keys"),
            service("groq", "Groq", "https://api.groq.com/openai/v1", CHAT, blurb = "Fast open models.", helpUrl = "https://console.groq.com/keys"),
            service("ollama", "Ollama", "http://localhost:11434/v1", CHAT, blurb = "Models on your own machine.", requiresKey = false),
            service("ollama-cloud", "Ollama Cloud", "https://ollama.com/v1", CHAT, blurb = "Ollama's hosted models.", helpUrl = "https://ollama.com/settings/keys"),
            service("together", "Together AI", "https://api.together.xyz/v1", CHAT, blurb = "Open models at scale.", helpUrl = "https://api.together.ai/settings/api-keys"),
            service("fireworks", "Fireworks", "https://api.fireworks.ai/inference/v1", CHAT, blurb = "Fast open-model inference.", helpUrl = "https://fireworks.ai/account/api-keys"),
            service("xai", "xAI", "https://api.x.ai/v1", CHAT, blurb = "Grok models.", helpUrl = "https://console.x.ai"),
            service("deepseek", "DeepSeek", "https://api.deepseek.com", CHAT, blurb = "DeepSeek chat and reasoning.", helpUrl = "https://platform.deepseek.com/api_keys"),
            service("mistral", "Mistral", "https://api.mistral.ai/v1", CHAT, blurb = "Mistral and Codestral models.", helpUrl = "https://console.mistral.ai/api-keys"),
            service("cerebras", "Cerebras", "https://api.cerebras.ai/v1", CHAT, blurb = "Very fast open models.", helpUrl = "https://cloud.cerebras.ai"),
            service("deepinfra", "DeepInfra", "https://api.deepinfra.com/v1/openai", CHAT, blurb = "Pay-per-token open models.", helpUrl = "https://deepinfra.com/dash/api_keys"),
            service("huggingface", "Hugging Face", "https://router.huggingface.co/v1", CHAT, blurb = "Inference Providers through one token.", helpUrl = "https://huggingface.co/settings/tokens"),
            service("lmstudio", "LM Studio", "http://localhost:1234/v1", CHAT, blurb = "Models served by LM Studio on your machine.", requiresKey = false),
            service("openai-compatible", "Custom endpoint", "", CHAT, blurb = "Any OpenAI-compatible server: vLLM, LM Studio and more.", requiresKey = false, custom = true),
        ),
    )

    private fun service(
        id: String,
        name: String,
        baseUrl: String,
        modalities: Set<Modality>,
        blurb: String,
        helpUrl: String? = null,
        requiresKey: Boolean = true,
        custom: Boolean = false,
    ) = ServiceDescriptor(id, name, VendorId.OPENAI_COMPATIBLE, baseUrl, modalities, requiresKey, helpUrl, blurb, custom)

    override fun connect(connection: Connection, config: StateFlow<ProviderConfig?>, scope: CoroutineScope): ConnectionRuntime {
        val id = connection.providerId
        val http = env.http
        val listing = AiSdkModelCatalog(http)
        fun baseUrl(): String? = config.value?.baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        fun headers(): Map<String, String> = AiSdkModelCatalog.bearerHeaders(config.value?.apiKey)
        val provider = ConfiguredProvider(config) { cfg ->
            cfg.baseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.let { url -> CompatVendors.resolve(http, url, cfg.apiKey) }
        }
        val chat = RemoteChatProvider(
            id = id,
            chat = AiSdkLlmEngine(provider, logTag = "openai", dispatcher = env.dispatcher, readBytes = env.readBytes),
            management = RemoteProviderManagement(
                providerId = id,
                config = config,
                scope = scope,
                catalog = {
                    RemoteModelMetadata.ensureLoaded(env.assets)
                    val url = baseUrl() ?: error("Not configured (no base URL)")
                    listing.listModels(url, headers())
                        .map { it.id }
                        .filter(OpenAiCatalog::isLikelyChatModel)
                        .sortedBy { it.lowercase() }
                        .map { RemoteCatalog.openAiSpec(id, it, RemoteModelMetadata::capabilitiesFor) }
                },
                connectionTester = {
                    baseUrl()?.let { listing.testConnection(it, headers()) } ?: ConnectionTestResult.Failed("Not configured")
                },
                cache = env.catalogCache,
                // Re-mints a cached entry through the SAME builder the live listing uses, so capabilities come
                // from the bundled registry rather than from anything that was persisted.
                mint = { RemoteModelMetadata.ensureLoaded(env.assets); RemoteCatalog.openAiSpec(id, it.id, RemoteModelMetadata::capabilitiesFor) },
                metadata = { model ->
                    RemoteModelMetadata.ensureLoaded(env.assets)
                    OllamaMetadataProbe.probe(config.value?.baseUrl, config.value?.apiKey, model) ?: RemoteModelMetadata.metadataFor(model)
                },
            ),
        )

        // Which of the curated speech and image rows this endpoint actually serves — decided once, from the
        // endpoint's vendor row (no key needed, no request made). The base URL is fixed for a runtime's life.
        val row = CompatVendors.resolve(http, connection.baseUrl, apiKey = null)
        val speechModels = CloudSpeechTemplates.forConnection(connection.vendorId, id).filter { spec ->
            if (spec.modality == Modality.Asr) row.offers(spec.remoteName) { transcriptionModel(it) }
            else row.offers(spec.remoteName) { speechModel(it) }
        }
        val imageModels = ImageModelTemplates.forConnection(connection.vendorId, id).filter { spec ->
            spec.remoteName?.let { name -> row.offers(name) { imageModel(it) } } == true
        }

        val transcription = AiSdkTranscriptionEngine(provider, vendor = connection.label)
        val synthesis = AiSdkSpeechEngine(provider, vendor = connection.label)
        val modalities = OpenAiModalityProvider(
            id = id,
            image = AiSdkImageEngine(provider, vendor = connection.label),
            stt = CloudSttEngine(
                transcription = transcription,
                modelName = { env.speechCatalog().resolveRemoteName(env.selection, id, Modality.Asr) },
                micActivity = env.micActivity,
            ),
            tts = CloudTtsEngine(
                synthesis = synthesis,
                modelName = { env.speechCatalog().resolveRemoteName(env.selection, id, Modality.Tts) },
                format = CloudTtsFormat.OpenAi,
                // The compat speech model's own default; the shared voice pref names another engine's voice.
                voice = null,
            ),
            speechAvailability = { cloudSpeechAvailability(provider(), connection.vendorId) },
        )
        return ConnectionRuntime(
            connection = connection,
            chat = chat,
            manageable = chat,
            image = modalities.takeIf { imageModels.isNotEmpty() },
            speech = modalities.takeIf { speechModels.isNotEmpty() },
            speechModels = speechModels,
            imageModels = imageModels,
        )
    }
}
