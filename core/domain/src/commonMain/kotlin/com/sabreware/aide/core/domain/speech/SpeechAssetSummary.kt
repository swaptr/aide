package com.sabreware.aide.core.domain.speech

import com.sabreware.aide.core.domain.download.DownloadStatus

data class SpeechAssetSummary(
    val spec: SpeechAssetSpec,
    val downloadStatus: DownloadStatus,
    val isActive: Boolean = false,
    val isInUse: Boolean = false,
    /**
     * Bytes of auto-provisioned companions (the VAD) this model still needs. Zero once they are on
     * disk. Surfaced in the row so "42 MB" never turns into a surprise second download.
     */
    val companionBytes: Long = 0L,
) {
    val isDownloaded: Boolean get() = downloadStatus is DownloadStatus.Completed
}
