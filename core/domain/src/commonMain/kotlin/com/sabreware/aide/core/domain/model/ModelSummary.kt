package com.sabreware.aide.core.domain.model

import com.sabreware.aide.core.domain.download.DownloadStatus

data class ModelSummary(
    val spec: ModelSpec,
    val downloadStatus: DownloadStatus,
    val isLoadedInEngine: Boolean,
    val isDefault: Boolean,
    val isInUse: Boolean = false,
    val loadedAccelerator: Accelerator? = null,
    /** A newer pinned commit exists than the one on disk (re-download to update). */
    val updateAvailable: Boolean = false,
    /** The model's own name when the user renamed it ([spec]'s name is then the alias); else null. */
    val originalName: String? = null,
    /** What serves it, as the user named it — a connection's name; null for an on-device model. */
    val sourceName: String? = null,
) {
    val provider: ProviderId get() = spec.provider
    val requiresDownload: Boolean get() = spec.requiresDownload

    val isDownloaded: Boolean
        get() = downloadStatus is DownloadStatus.Completed
}
