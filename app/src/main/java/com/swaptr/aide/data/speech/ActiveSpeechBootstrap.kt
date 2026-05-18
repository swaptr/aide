package com.swaptr.aide.data.speech

import android.util.Log
import com.swaptr.aide.data.download.DownloadController
import com.swaptr.aide.data.download.DownloadStatus
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import com.swaptr.aide.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// Keeps active STT/TTS prefs in sync with disk (auto-promote on first download, fall through
// on delete, auto-fetch Silero VAD for offline STT). Must run on appScope; no owning VM.
@Singleton
class ActiveSpeechBootstrap @Inject constructor(
    private val registry: SpeechAssetRegistry,
    private val prefs: UserPreferencesRepository,
    private val storage: SpeechAssetStorage,
    private val downloads: DownloadController,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    fun start() {
        migrateInstallMarkers()
        registry.observe()
            .onEach { rows -> reconcile(rows) }
            .launchIn(appScope)
    }

    // One-shot: back-fill .install_complete marker for older builds' extracted dirs.
    private fun migrateInstallMarkers() {
        appScope.launch {
            for (spec in SpeechAssetCatalog.all) {
                if (spec.kind == SpeechAssetKind.VAD) continue
                if (storage.looksFullyExtractedWithoutMarker(spec)) {
                    Log.i(TAG, "marker-migration writing .install_complete for ${spec.id}")
                    storage.writeInstallMarker(spec)
                }
            }
        }
    }

    private suspend fun reconcile(rows: List<com.swaptr.aide.domain.speech.SpeechAssetSummary>) {
        val sttDownloaded = rows.filter { it.spec.kind == SpeechAssetKind.STT && it.isDownloaded }
        val ttsDownloaded = rows.filter { it.spec.kind == SpeechAssetKind.TTS && it.isDownloaded }

        val currentStt = prefs.activeSttModelIdFlow.first()
        val sttStillValid = sttDownloaded.any { it.spec.id == currentStt }
        if (currentStt == null && sttDownloaded.isNotEmpty()) {
            val pick = sttDownloaded.first().spec.id
            Log.i(TAG, "promote active STT → $pick (first download)")
            prefs.setActiveSttModelId(pick)
        } else if (currentStt != null && !sttStillValid) {
            val fallback = sttDownloaded.firstOrNull()?.spec?.id
            Log.i(TAG, "active STT $currentStt no longer downloaded → $fallback")
            prefs.setActiveSttModelId(fallback)
        }

        val currentTts = prefs.activeTtsModelIdFlow.first()
        val ttsStillValid = ttsDownloaded.any { it.spec.id == currentTts }
        if (currentTts == null && ttsDownloaded.isNotEmpty()) {
            val pick = ttsDownloaded.first().spec.id
            Log.i(TAG, "promote active TTS → $pick (first download)")
            prefs.setActiveTtsModelId(pick)
        } else if (currentTts != null && !ttsStillValid) {
            val fallback = ttsDownloaded.firstOrNull()?.spec?.id
            Log.i(TAG, "active TTS $currentTts no longer downloaded → $fallback")
            prefs.setActiveTtsModelId(fallback)
        }

        val activeSttSpec = prefs.activeSttModelIdFlow.first()
            ?.let(SpeechAssetCatalog::findById)
        if (activeSttSpec != null && !activeSttSpec.family.streaming) {
            val silero = SpeechAssetCatalog.sileroVad
            if (!storage.hasFile(silero)) {
                val sileroStatus = rows.firstOrNull { it.spec.id == silero.id }?.downloadStatus
                val enqueued = sileroStatus is DownloadStatus.Queued ||
                    sileroStatus is DownloadStatus.InProgress
                if (!enqueued) {
                    Log.i(TAG, "active STT ${activeSttSpec.id} is offline; auto-fetching Silero VAD")
                    appScope.launch { downloads.enqueueSpeech(silero) }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ActiveSpeech"
    }
}
