package com.sabreware.aide.data.llm.vendor

import com.sabreware.aide.aisdk.providers.google.GoogleProvider
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
import com.sabreware.aide.data.llm.aisdk.AiSdkSpeechEngine
import com.sabreware.aide.data.llm.aisdk.AiSdkTranscriptionEngine
import com.sabreware.aide.data.llm.aisdk.ConfiguredProvider
import com.sabreware.aide.data.llm.gemini.GeminiModels
import com.sabreware.aide.data.llm.gemini.GeminiProvider
import com.sabreware.aide.data.llm.remote.RemoteProviderManagement
import com.sabreware.aide.data.speech.cloud.CloudSpeechTemplates
import com.sabreware.aide.data.speech.cloud.CloudSttEngine
import com.sabreware.aide.data.speech.cloud.CloudTtsEngine
import com.sabreware.aide.data.speech.cloud.CloudTtsFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Google Gemini via `:aisdk`'s native Google provider — native rather than the OpenAI-compat path, which
 * drops `thought_signature`. One Google key reaches chat, image, speech and transcription, so one
 * [GeminiProvider] fills every slot of the connection's runtime.
 */
class GeminiVendor(private val env: VendorEnvironment) : Vendor {

    override val descriptor = VendorDescriptor(
        id = VendorId.GEMINI,
        displayName = "Gemini",
        services = listOf(
            ServiceDescriptor(
                id = "gemini",
                name = "Google Gemini",
                vendor = VendorId.GEMINI,
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                modalities = setOf(Modality.Chat, Modality.Asr, Modality.Tts, Modality.Image),
                helpUrl = "https://aistudio.google.com/apikey",
                blurb = "Gemini chat, speech, transcription and images.",
            ),
        ),
    )

    override fun connect(connection: Connection, config: StateFlow<ProviderConfig?>, scope: CoroutineScope): ConnectionRuntime {
        val id = connection.providerId
        fun apiKey(): String? = config.value?.apiKey?.takeIf { it.isNotBlank() }
        val provider = ConfiguredProvider(config) { cfg ->
            cfg.apiKey?.takeIf { it.isNotBlank() }?.let { key ->
                // A stored URL without a version gets the provider's default one.
                GoogleProvider(client = env.http, apiKey = key, baseUrl = GeminiModels.baseUrl(cfg.baseUrl))
            }
        }
        val transcription = AiSdkTranscriptionEngine(provider, vendor = connection.label)
        val synthesis = AiSdkSpeechEngine(provider, vendor = connection.label)
        val gemini = GeminiProvider(
            id = id,
            chat = AiSdkLlmEngine(provider, logTag = "gemini", dispatcher = env.dispatcher, readBytes = env.readBytes),
            image = AiSdkImageEngine(provider, vendor = connection.label),
            stt = CloudSttEngine(
                transcription = transcription,
                modelName = { env.speechCatalog().resolveRemoteName(env.selection, id, Modality.Asr) },
                micActivity = env.micActivity,
            ),
            tts = CloudTtsEngine(
                synthesis = synthesis,
                modelName = { env.speechCatalog().resolveRemoteName(env.selection, id, Modality.Tts) },
                format = CloudTtsFormat.Google,
                // Google's own documented example voice; the provider applies it when none is named.
                voice = null,
            ),
            speechAvailability = { cloudSpeechAvailability(provider(), connection.vendorId) },
            management = RemoteProviderManagement(
                providerId = id,
                config = config,
                scope = scope,
                catalog = { RemoteModelMetadata.ensureLoaded(env.assets); GeminiModels.catalogSpecs(id, RemoteModelMetadata::capabilitiesFor) },
                // Gemini's catalog is a curated static list, so "configured" is the only thing to test.
                connectionTester = {
                    if (apiKey() != null) ConnectionTestResult.Ok(GeminiModels.curated.size)
                    else ConnectionTestResult.Failed("Add an API key")
                },
                cache = env.catalogCache,
                mint = { RemoteModelMetadata.ensureLoaded(env.assets); RemoteCatalog.geminiSpec(id, it.id, RemoteModelMetadata::capabilitiesFor, it.displayName) },
                metadata = { RemoteModelMetadata.ensureLoaded(env.assets); RemoteModelMetadata.metadataFor(it) },
            ),
        )
        return ConnectionRuntime(
            connection = connection,
            chat = gemini,
            manageable = gemini,
            image = gemini,
            speech = gemini,
            speechModels = CloudSpeechTemplates.forConnection(connection.vendorId, id),
            imageModels = ImageModelTemplates.forConnection(connection.vendorId, id),
        )
    }
}
