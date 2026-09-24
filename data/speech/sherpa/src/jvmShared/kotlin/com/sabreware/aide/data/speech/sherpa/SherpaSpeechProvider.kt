package com.sabreware.aide.data.speech.sherpa

import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ModelSelectionStore
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.model.activeModelFor
import com.sabreware.aide.core.domain.speech.SpeechAssetSpec
import com.sabreware.aide.core.domain.speech.SpeechAvailability
import com.sabreware.aide.core.domain.speech.SpeechProvider
import com.sabreware.aide.data.speech.SpeechAssetCatalog
import com.sabreware.aide.data.speech.SpeechAssetStorage
import kotlinx.coroutines.flow.first

class SherpaSpeechProvider(
    private val sttImpl: SherpaSttEngine,
    private val ttsImpl: SherpaTtsEngine,
    private val vadImpl: SherpaVadEngine,
    private val storage: SpeechAssetStorage,
    private val selection: ModelSelectionStore,
) : SpeechProvider {
    override val id = ProviderId.SHERPA
    override val stt = sttImpl
    override val tts = ttsImpl
    override val vad = vadImpl

    override suspend fun availability(): SpeechAvailability {
        val activeStt = selection.activeModelFor(Modality.Asr).first()?.let(SpeechAssetCatalog::findById)
        val activeTts = selection.activeModelFor(Modality.Tts).first()?.let(SpeechAssetCatalog::findById)
        val sttOk = activeStt?.let(storage::hasExtracted) == true || anyExtracted(Modality.Asr)
        val ttsOk = activeTts?.let(storage::hasExtracted) == true || anyExtracted(Modality.Tts)
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

    private fun anyExtracted(kind: Modality): Boolean =
        SpeechAssetCatalog.ofKind(kind).any(storage::hasExtracted)

    @Suppress("unused") // kept for callers wiring per-role pick UI later
    private fun firstOnDisk(kind: Modality): SpeechAssetSpec? =
        SpeechAssetCatalog.ofKind(kind).firstOrNull(storage::hasExtracted)
}
