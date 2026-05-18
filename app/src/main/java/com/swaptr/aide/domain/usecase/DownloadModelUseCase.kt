package com.swaptr.aide.domain.usecase

import com.swaptr.aide.data.catalog.ModelSpec
import com.swaptr.aide.data.model.ModelDownloadRepository
import javax.inject.Inject

class DownloadModelUseCase @Inject constructor(
    private val downloads: ModelDownloadRepository,
) {
    operator fun invoke(spec: ModelSpec, authToken: String? = null) {
        downloads.enqueue(spec, authToken)
    }
}

class PauseDownloadUseCase @Inject constructor(
    private val downloads: ModelDownloadRepository,
) {
    operator fun invoke(spec: ModelSpec) = downloads.pause(spec.id)
}

class CancelDownloadUseCase @Inject constructor(
    private val downloads: ModelDownloadRepository,
) {
    operator fun invoke(spec: ModelSpec) = downloads.cancel(spec)
}
