package com.swaptr.aide.domain.speech

import com.swaptr.aide.data.download.DownloadStatus
import com.swaptr.aide.data.speech.SpeechAssetSpec

data class SpeechAssetSummary(
    val spec: SpeechAssetSpec,
    val downloadStatus: DownloadStatus,
    val isActive: Boolean = false,
    val isInUse: Boolean = false,
) {
    val isDownloaded: Boolean get() = downloadStatus is DownloadStatus.Completed
}
