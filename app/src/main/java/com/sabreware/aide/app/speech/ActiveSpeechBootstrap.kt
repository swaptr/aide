package com.sabreware.aide.app.speech
import com.sabreware.aide.core.common.startup.DeferredBootstrap
import com.sabreware.aide.core.domain.speech.SpeechAssetSummary
import com.sabreware.aide.data.speech.SpeechAssetCatalog
import com.sabreware.aide.data.speech.SpeechAssetStorage
import com.sabreware.aide.core.domain.model.activeModelFor
import com.sabreware.aide.core.domain.model.setActiveModelFor
import com.sabreware.aide.core.domain.speech.SpeechAssetRepository

import android.util.Log
import com.sabreware.aide.core.domain.download.AssetKind
import com.sabreware.aide.core.domain.download.DownloadScheduler
import com.sabreware.aide.core.domain.download.DownloadStatus
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ModelSelectionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

// Keeps the active STT/TTS picks in sync with disk (auto-promote on first download, fall through
// on delete, auto-fetch Silero VAD for offline STT). Must run on appScope; no owning VM.
//
// A cloud pick is left alone: the active slot is per MODALITY and may name a vendor's model rather than a
// downloadable asset, and "not downloaded" is not a state a cloud model can be in. Reconciling it against
// the asset list would reset an OpenAI transcription pick to the first Sherpa bundle on the next emission.
class ActiveSpeechBootstrap(
    private val registry: SpeechAssetRepository,
    private val selection: ModelSelectionStore,
    private val storage: SpeechAssetStorage,
    private val downloads: DownloadScheduler,
    private val appScope: CoroutineScope,
) : DeferredBootstrap {

    private var started = false

    override suspend fun start() {
        // Idempotent. It was called straight from Application.onCreate with no guard at all, so every
        // process that re-ran it — and every future caller from the shell's re-running first-frame effect —
        // would have left a second permanent collector on the app scope behind.
        if (started) return
        started = true
        registry.assets
            .filterNotNull()
            .onEach { rows -> reconcile(rows) }
            .launchIn(appScope)
    }

    private suspend fun reconcile(rows: List<com.sabreware.aide.core.domain.speech.SpeechAssetSummary>) {
        val sttDownloaded = rows.filter { it.spec.modality == Modality.Asr && it.isDownloaded }
        val ttsDownloaded = rows.filter { it.spec.modality == Modality.Tts && it.isDownloaded }

        val currentStt = selection.activeModelFor(Modality.Asr).first()
        val sttStillValid = sttDownloaded.any { it.spec.id == currentStt }
        if (currentStt.isCloudModel()) {
            // Nothing on disk to reconcile a cloud model against.
        } else if (currentStt == null && sttDownloaded.isNotEmpty()) {
            val pick = sttDownloaded.first().spec.id
            Log.i(TAG, "promote active STT → $pick (first download)")
            selection.setActiveModelFor(Modality.Asr, pick)
        } else if (currentStt != null && !sttStillValid) {
            val fallback = sttDownloaded.firstOrNull()?.spec?.id
            Log.i(TAG, "active STT $currentStt no longer downloaded → $fallback")
            selection.setActiveModelFor(Modality.Asr, fallback)
        }

        val currentTts = selection.activeModelFor(Modality.Tts).first()
        val ttsStillValid = ttsDownloaded.any { it.spec.id == currentTts }
        if (currentTts.isCloudModel()) {
            // As above.
        } else if (currentTts == null && ttsDownloaded.isNotEmpty()) {
            val pick = ttsDownloaded.first().spec.id
            Log.i(TAG, "promote active TTS → $pick (first download)")
            selection.setActiveModelFor(Modality.Tts, pick)
        } else if (currentTts != null && !ttsStillValid) {
            val fallback = ttsDownloaded.firstOrNull()?.spec?.id
            Log.i(TAG, "active TTS $currentTts no longer downloaded → $fallback")
            selection.setActiveModelFor(Modality.Tts, fallback)
        }

        val activeSttSpec = selection.activeModelFor(Modality.Asr).first()
            ?.let(SpeechAssetCatalog::findById)
        if (activeSttSpec != null && !activeSttSpec.family.streaming) {
            val silero = SpeechAssetCatalog.sileroVad
            if (!storage.hasFile(silero)) {
                val sileroStatus = rows.firstOrNull { it.spec.id == silero.id }?.downloadStatus
                val enqueued = sileroStatus is DownloadStatus.Queued ||
                    sileroStatus is DownloadStatus.InProgress
                if (!enqueued) {
                    Log.i(TAG, "active STT ${activeSttSpec.id} is offline; auto-fetching Silero VAD")
                    appScope.launch { downloads.enqueue(AssetKind.SPEECH, silero.id) }
                }
            }
        }
    }

    /** An active id the asset catalog does not know is a cloud model's — the catalog is the only other holder. */
    private fun String?.isCloudModel(): Boolean = this != null && SpeechAssetCatalog.findById(this) == null

    companion object {
        private const val TAG = "ActiveSpeech"
    }
}
