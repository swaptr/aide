package com.swaptr.aide.domain.model

import com.swaptr.aide.data.catalog.Accelerator
import com.swaptr.aide.data.catalog.ModelSpec
import com.swaptr.aide.data.catalog.ProviderId
import com.swaptr.aide.data.download.DownloadStatus

data class ModelSummary(
    val spec: ModelSpec,
    val downloadStatus: DownloadStatus,
    val isLoadedInEngine: Boolean,
    val isDefault: Boolean,
    val isInUse: Boolean = false,
    val loadedAccelerator: Accelerator? = null,
) {
    val provider: ProviderId get() = spec.provider
    val requiresDownload: Boolean get() = spec.requiresDownload

    val isDownloaded: Boolean
        get() = downloadStatus is DownloadStatus.Completed
}
