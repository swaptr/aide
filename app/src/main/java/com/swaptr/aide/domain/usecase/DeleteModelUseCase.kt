package com.swaptr.aide.domain.usecase

import com.swaptr.aide.data.catalog.ModelSpec
import com.swaptr.aide.data.model.LlmEngineRepository
import com.swaptr.aide.data.model.ModelDownloadRepository
import com.swaptr.aide.data.model.ModelRegistryRepository
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class DeleteModelUseCase @Inject constructor(
    private val engine: LlmEngineRepository,
    private val downloads: ModelDownloadRepository,
    private val prefs: UserPreferencesRepository,
    private val registry: ModelRegistryRepository,
) {
    suspend operator fun invoke(spec: ModelSpec) {
        if (engine.loadedModelId == spec.id) engine.unload()
        downloads.cancel(spec)
        downloads.delete(spec)

        if (prefs.defaultModelIdFlow.first() == spec.id) {
            val rows = registry.observeModels().first()
            val fallback = rows.firstOrNull { row ->
                row.spec.id != spec.id &&
                    (!row.spec.requiresDownload || downloads.isDownloaded(row.spec))
            }?.spec
            if (fallback != null) registry.setDefaultModelId(fallback.id)
            else registry.clearDefaultModelId()
        }

        registry.clearLastUsedIfMatches(spec.id)
        // Drop per-tier last-used slot so the deleted model leaves the "In Use" tab immediately.
        registry.clearUsed(spec)
    }
}
