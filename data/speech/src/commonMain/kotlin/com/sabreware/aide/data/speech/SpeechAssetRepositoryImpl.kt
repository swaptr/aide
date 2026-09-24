package com.sabreware.aide.data.speech
import com.sabreware.aide.core.domain.download.awaitCompletion
import com.sabreware.aide.core.domain.model.setActiveModelFor
import kotlinx.coroutines.launch

import com.sabreware.aide.core.domain.cache.snapshotCache
import com.sabreware.aide.core.domain.download.DownloadStatus
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ModelSelectionStore
import com.sabreware.aide.core.domain.model.activeModelFor
import com.sabreware.aide.core.domain.speech.SpeechAssetRepository
import com.sabreware.aide.core.domain.speech.SpeechAssetSpec
import com.sabreware.aide.core.domain.speech.SpeechAssetSummary
import com.sabreware.aide.core.domain.download.AssetKind
import com.sabreware.aide.core.domain.download.DownloadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart

/**
 * The speech-side mirror of `ModelRegistryRepository`: it names assets by `(kind, id)` on the one
 * [DownloadScheduler] and joins live progress with the active-model selection.
 *
 * One class for both targets, and no seam left in it: unpacking a `.tar.bz2` is the registered
 * `SpeechAssetSource`'s post-process step, which every scheduler runs, so the repository no longer needs to
 * know whether the download pipeline already did it.
 */
class SpeechAssetRepositoryImpl(
    private val scheduler: DownloadScheduler,
    private val storage: SpeechAssetStorage,
    private val selection: ModelSelectionStore,
    private val appScope: CoroutineScope,
) : SpeechAssetRepository {

    override val assets: StateFlow<List<SpeechAssetSummary>?> = assetsFlow()
        // Every emission probes the disk (hasExtracted/hasFile per spec) — that runs here, not on
        // whichever dispatcher happens to collect.
        .flowOn(Dispatchers.Default)
        .snapshotCache(appScope, "speech assets")

    private fun assetsFlow(): Flow<List<SpeechAssetSummary>> {
        val specs = SpeechAssetCatalog.all
        if (specs.isEmpty()) return flowOf(emptyList())
        val statusFlows = specs.map { spec ->
            combine(
                // Idle up front so a scheduler query (WorkManager on Android) never gates the snapshot: disk
                // decides Completed below, and a transfer that is genuinely running repaints its row a beat
                // later — the same trade ModelRegistryRepositoryImpl.observeDownloadStatuses makes.
                scheduler.observe(AssetKind.SPEECH, spec.id).onStart { emit(DownloadStatus.Idle(spec.id)) },
                storage.changes.onStart { emit(Unit) },
            ) { status, _ ->
                // On-disk state is authoritative — a stored file outlives the scheduler's own record of the
                // download (an in-memory flow resets to Idle on restart; nothing in a work queue can revoke
                // a file that is already on disk).
                if (storage.hasExtracted(spec) || storage.hasFile(spec)) DownloadStatus.Completed(spec.id)
                else status
            }
        }
        return combine(
            combine(statusFlows) { it.toList() },
            selection.activeModelFor(Modality.Asr),
            selection.activeModelFor(Modality.Tts),
        ) { statuses, activeStt, activeTts ->
            // The VAD is in `specs` too, so its status is already here — no extra disk probe needed to
            // know whether a model's companion download is still outstanding.
            val completed = specs.indices
                .filter { statuses[it] is DownloadStatus.Completed }
                .mapTo(mutableSetOf()) { specs[it].id }
            specs.mapIndexed { i, spec ->
                val active = when (spec.modality) {
                    Modality.Asr -> activeStt == spec.id
                    Modality.Tts -> activeTts == spec.id
                    else -> false
                }
                SpeechAssetSummary(
                    spec = spec,
                    downloadStatus = statuses[i],
                    isActive = active,
                    companionBytes = SpeechAssetCatalog.companionsFor(spec)
                        .filterNot { it.id in completed }
                        .sumOf { it.sizeBytes ?: 0L },
                )
            }
        }
    }

    override fun download(spec: SpeechAssetSpec) {
        // Companions (the VAD) appear in no picker, so nothing else would ever fetch them. They ride
        // along with the first model that needs one — the user picks a model, not a stack.
        SpeechAssetCatalog.companionsFor(spec)
            .filterNot { storage.hasFile(it) || storage.hasExtracted(it) }
            .forEach(::download)
        if (spec.downloadUrl == null) return
        scheduler.enqueue(AssetKind.SPEECH, spec.id)
        // A voice the user downloaded IS their pick for its role once it lands. Companions are not chosen.
        if (spec.modality == Modality.Asr || spec.modality == Modality.Tts) {
            appScope.launch {
                if (scheduler.awaitCompletion(AssetKind.SPEECH, spec.id)) {
                    selection.setActiveModelFor(spec.modality, spec.id)
                }
            }
        }
    }

    override fun pauseDownload(assetId: String) {
        scheduler.pause(AssetKind.SPEECH, assetId)
    }

    // cancel() wipes the disk through the asset source, so there is nothing to clean up here.
    override fun cancelDownload(spec: SpeechAssetSpec) = scheduler.cancel(AssetKind.SPEECH, spec.id)
}
