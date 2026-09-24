package com.sabreware.aide.core.domain.speech

import kotlinx.coroutines.flow.StateFlow

/**
 * Speech-side mirror of the model repositories: observes the catalog of STT/TTS/VAD assets (joined
 * with download status + which is active) and drives their downloads. The one implementation
 * ([com.sabreware.aide.data.speech.SpeechAssetRepositoryImpl]) runs over whichever `DownloadScheduler` the
 * target installs; consumers depend only on this interface.
 */
interface SpeechAssetRepository {

    /**
     * The asset list joined with live download status + active picks, cached app-wide: null until the
     * session's first resolution, then the latest list (see [ModelRegistryRepository.models] — same shape,
     * same reason: the join probes the disk, so no surface should rebuild it from scratch on open).
     */
    val assets: StateFlow<List<SpeechAssetSummary>?>

    fun download(spec: SpeechAssetSpec)

    fun pauseDownload(assetId: String)

    fun cancelDownload(spec: SpeechAssetSpec)
}
