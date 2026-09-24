package com.sabreware.aide.core.domain.usecase

import com.sabreware.aide.core.domain.download.AssetKind
import com.sabreware.aide.core.domain.download.DownloadScheduler
import com.sabreware.aide.core.domain.model.ModelSpec

/**
 * Model downloads go through the one [DownloadScheduler], named by `(kind, id)`. There is no model-specific
 * download port any more: the registered `ModelAssetSource` already knows the URL and the on-disk layout, so
 * these three are the whole surface a caller needs.
 */
class DownloadModelUseCase(
    private val scheduler: DownloadScheduler,
) {
    operator fun invoke(spec: ModelSpec) {
        scheduler.enqueue(AssetKind.MODEL, spec.id)
    }
}

class PauseDownloadUseCase(
    private val scheduler: DownloadScheduler,
) {
    operator fun invoke(spec: ModelSpec) = scheduler.pause(AssetKind.MODEL, spec.id)
}

class CancelDownloadUseCase(
    private val scheduler: DownloadScheduler,
) {
    operator fun invoke(spec: ModelSpec) = scheduler.cancel(AssetKind.MODEL, spec.id)
}
