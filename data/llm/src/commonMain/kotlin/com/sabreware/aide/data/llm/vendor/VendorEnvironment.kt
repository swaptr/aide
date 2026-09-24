package com.sabreware.aide.data.llm.vendor

import com.sabreware.aide.aisdk.Provider as AisdkProvider
import com.sabreware.aide.core.common.media.AttachmentBytesReader
import com.sabreware.aide.core.common.storage.BundledAssetReader
import com.sabreware.aide.core.domain.connection.VendorId
import com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ModelSelectionStore
import com.sabreware.aide.core.domain.speech.CloudSpeechCatalog
import com.sabreware.aide.core.domain.speech.MicActivityMonitor
import com.sabreware.aide.core.domain.speech.SpeechAvailability
import com.sabreware.aide.data.catalog.RemoteCatalogCache
import com.sabreware.aide.data.speech.cloud.CloudSpeechTemplates
import io.ktor.client.HttpClient

/**
 * What every vendor needs to turn a connection into providers — bound once in `:di`, so a vendor's
 * constructor names one dependency instead of nine and a new vendor is its own file plus one binding.
 *
 * [speechCatalog] is read lazily (at call time, when the runtime publishing this connection has long since
 * landed); the catalog itself is built from the runtimes, so it cannot be a constructor-time dependency of
 * the thing that builds them.
 */
class VendorEnvironment(
    val http: HttpClient,
    val assets: BundledAssetReader,
    val dispatcher: ToolDispatcher,
    val readBytes: AttachmentBytesReader,
    val catalogCache: RemoteCatalogCache,
    val selection: ModelSelectionStore,
    val micActivity: MicActivityMonitor,
    val speechCatalog: () -> CloudSpeechCatalog,
)

/**
 * Whether a connection can serve a speech role RIGHT NOW: configured, and the resolved `:aisdk` provider
 * actually offers the modality. The second half is what "key present" cannot say: an Ollama or Groq endpoint
 * on the OpenAI-compatible wire resolves to a row whose `transcriptionModel` / `speechModel` are null — a set
 * lookup, no network. Reporting `canStt` there would put it in the ladder and fail at the first mic tap.
 */
internal fun cloudSpeechAvailability(provider: AisdkProvider?, vendor: VendorId): SpeechAvailability {
    fun serves(modality: Modality, lookup: (AisdkProvider, String) -> Any?): Boolean {
        val remoteName = CloudSpeechTemplates.defaultRemoteName(vendor, modality) ?: return false
        return provider != null && runCatching { lookup(provider, remoteName) }.getOrNull() != null
    }
    return SpeechAvailability(
        canStt = serves(Modality.Asr) { p, model -> p.transcriptionModel(model) },
        canTts = serves(Modality.Tts) { p, model -> p.speechModel(model) },
        canVad = false,
    )
}

/** True when [provider] offers [lookup] for [model] — a set lookup on the vendor row, never a request. */
internal inline fun AisdkProvider.offers(model: String, lookup: AisdkProvider.(String) -> Any?): Boolean =
    runCatching { lookup(model) }.getOrNull() != null
