package com.swaptr.aide.domain.speech.sherpa

import com.swaptr.aide.data.prefs.UserPreferencesRepository
import com.swaptr.aide.data.speech.SpeechAssetCatalog
import com.swaptr.aide.data.speech.SpeechAssetKind
import com.swaptr.aide.data.speech.SpeechAssetSpec
import com.swaptr.aide.data.speech.SpeechAssetStorage
import com.swaptr.aide.domain.speech.SpeechAvailability
import com.swaptr.aide.domain.speech.SpeechProvider
import com.swaptr.aide.domain.speech.SpeechProviderId
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SherpaSpeechProvider @Inject constructor(
    private val sttImpl: SherpaSttEngine,
    private val ttsImpl: SherpaTtsEngine,
    private val vadImpl: SherpaVadEngine,
    private val storage: SpeechAssetStorage,
    private val prefs: UserPreferencesRepository,
) : SpeechProvider {
    override val id = SpeechProviderId.SHERPA_ONNX
    override val stt = sttImpl
    override val tts = ttsImpl
    override val vad = vadImpl

    override suspend fun availability(): SpeechAvailability {
        val activeStt = prefs.activeSttModelIdFlow.first()?.let(SpeechAssetCatalog::findById)
        val activeTts = prefs.activeTtsModelIdFlow.first()?.let(SpeechAssetCatalog::findById)
        val sttOk = activeStt?.let(storage::hasExtracted) == true || anyExtracted(SpeechAssetKind.STT)
        val ttsOk = activeTts?.let(storage::hasExtracted) == true || anyExtracted(SpeechAssetKind.TTS)
        val vadOk = storage.hasFile(SpeechAssetCatalog.sileroVad)
        val missing = buildSet {
            if (!sttOk) add(SpeechAssetCatalog.zipformerEnStt.id)
            if (!ttsOk) add(SpeechAssetCatalog.piperEnAmyTts.id)
            if (!vadOk) add(SpeechAssetCatalog.sileroVad.id)
        }
        return SpeechAvailability(
            canStt = sttOk,
            canTts = ttsOk,
            canVad = vadOk,
            missingAssets = missing,
        )
    }

    private fun anyExtracted(kind: SpeechAssetKind): Boolean =
        SpeechAssetCatalog.ofKind(kind).any(storage::hasExtracted)

    @Suppress("unused") // kept for callers wiring per-role pick UI later
    private fun firstOnDisk(kind: SpeechAssetKind): SpeechAssetSpec? =
        SpeechAssetCatalog.ofKind(kind).firstOrNull(storage::hasExtracted)
}
