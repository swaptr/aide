package com.swaptr.aide.data.speech

import com.swaptr.aide.data.download.DownloadController
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import com.swaptr.aide.domain.speech.SpeechAssetSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

// Speech-side mirror of ModelRegistryRepository; isActive joins prefs.activeXxxModelId.
@Singleton
class SpeechAssetRegistry @Inject constructor(
    private val controller: DownloadController,
    private val prefs: UserPreferencesRepository,
) {
    fun observe(): Flow<List<SpeechAssetSummary>> {
        val specs = SpeechAssetCatalog.all
        if (specs.isEmpty()) return flowOf(emptyList())
        val statusFlows = specs.map { controller.observeSpeech(it) }
        return combine(
            combine(statusFlows) { it.toList() },
            prefs.activeSttModelIdFlow,
            prefs.activeTtsModelIdFlow,
        ) { statuses, activeStt, activeTts ->
            specs.mapIndexed { i, spec ->
                val active = when (spec.kind) {
                    SpeechAssetKind.STT -> activeStt == spec.id
                    SpeechAssetKind.TTS -> activeTts == spec.id
                    SpeechAssetKind.VAD -> false
                }
                SpeechAssetSummary(
                    spec = spec,
                    downloadStatus = statuses[i],
                    isActive = active,
                )
            }
        }
    }
}
