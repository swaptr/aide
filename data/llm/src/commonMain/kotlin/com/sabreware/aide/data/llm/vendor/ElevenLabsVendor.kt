package com.sabreware.aide.data.llm.vendor

import com.sabreware.aide.aisdk.providers.elevenlabs.ElevenLabsProvider
import com.sabreware.aide.core.domain.connection.Connection
import com.sabreware.aide.core.domain.connection.ConnectionRuntime
import com.sabreware.aide.core.domain.connection.ServiceDescriptor
import com.sabreware.aide.core.domain.connection.Vendor
import com.sabreware.aide.core.domain.connection.VendorDescriptor
import com.sabreware.aide.core.domain.connection.VendorId
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.provider.ProviderConfig
import com.sabreware.aide.core.domain.speech.resolveRemoteName
import com.sabreware.aide.data.llm.aisdk.AiSdkSpeechEngine
import com.sabreware.aide.data.llm.aisdk.AiSdkTranscriptionEngine
import com.sabreware.aide.data.llm.aisdk.ConfiguredProvider
import com.sabreware.aide.data.llm.aisdk.ElevenLabsSpeechProvider
import com.sabreware.aide.data.speech.cloud.CloudSpeechTemplates
import com.sabreware.aide.data.speech.cloud.CloudSttEngine
import com.sabreware.aide.data.speech.cloud.CloudTtsEngine
import com.sabreware.aide.data.speech.cloud.CloudTtsFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** ElevenLabs: voices (text-to-speech) and Scribe (speech-to-text). No chat, so no catalog to refresh. */
class ElevenLabsVendor(private val env: VendorEnvironment) : Vendor {

    override val descriptor = VendorDescriptor(
        id = VendorId.ELEVENLABS,
        displayName = "ElevenLabs",
        services = listOf(
            ServiceDescriptor(
                id = "elevenlabs",
                name = "ElevenLabs",
                vendor = VendorId.ELEVENLABS,
                baseUrl = "https://api.elevenlabs.io/v1",
                modalities = setOf(Modality.Asr, Modality.Tts),
                helpUrl = "https://elevenlabs.io/app/settings/api-keys",
                blurb = "Natural voices and Scribe transcription.",
            ),
        ),
    )

    override fun connect(connection: Connection, config: StateFlow<ProviderConfig?>, scope: CoroutineScope): ConnectionRuntime {
        val id = connection.providerId
        val provider = ConfiguredProvider(config) { cfg ->
            cfg.apiKey?.takeIf { it.isNotBlank() }?.let { key ->
                ElevenLabsProvider(client = env.http, apiKey = key, baseUrl = cfg.baseUrl.trimEnd('/'))
            }
        }
        val speech = ElevenLabsSpeechProvider(
            id = id,
            stt = CloudSttEngine(
                transcription = AiSdkTranscriptionEngine(provider, vendor = connection.label),
                modelName = { env.speechCatalog().resolveRemoteName(env.selection, id, Modality.Asr) },
                micActivity = env.micActivity,
            ),
            tts = CloudTtsEngine(
                synthesis = AiSdkSpeechEngine(provider, vendor = connection.label),
                modelName = { env.speechCatalog().resolveRemoteName(env.selection, id, Modality.Tts) },
                format = CloudTtsFormat.ElevenLabs,
                voice = ElevenLabsProvider.DEFAULT_VOICE_ID,
            ),
            speechAvailability = { cloudSpeechAvailability(provider(), connection.vendorId) },
        )
        return ConnectionRuntime(
            connection = connection,
            speech = speech,
            speechModels = CloudSpeechTemplates.forConnection(connection.vendorId, id),
        )
    }
}
