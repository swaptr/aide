package com.sabreware.aide.core.domain.download

sealed interface DownloadStatus {
    val modelId: String

    data class Idle(override val modelId: String) : DownloadStatus
    data class Queued(override val modelId: String) : DownloadStatus
    data class InProgress(
        override val modelId: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val bytesPerSec: Long,
    ) : DownloadStatus {
        val progress: Float
            get() = if (totalBytes <= 0) 0f else (downloadedBytes.toDouble() / totalBytes).toFloat()
    }

    data class Paused(
        override val modelId: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : DownloadStatus

    // Between InProgress(100%) and Completed for kinds with postProcess (e.g. tar.bz2 extract).
    data class Finalizing(
        override val modelId: String,
        val stage: Stage,
    ) : DownloadStatus {
        enum class Stage(val label: String) {
            Verifying("Verifying…"),
            Extracting("Extracting…"),
            Installing("Installing…"),
        }
    }

    data class Completed(override val modelId: String) : DownloadStatus
    data class Failed(override val modelId: String, val message: String) : DownloadStatus
    data class Cancelled(override val modelId: String) : DownloadStatus
}
