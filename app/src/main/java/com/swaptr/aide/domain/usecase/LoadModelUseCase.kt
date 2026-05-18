package com.swaptr.aide.domain.usecase

import com.swaptr.aide.data.catalog.ModelSpec
import com.swaptr.aide.data.catalog.ProviderId
import com.swaptr.aide.data.model.LlmEngineRepository
import com.swaptr.aide.data.model.ModelRegistryRepository
import javax.inject.Inject

class LoadModelUseCase @Inject constructor(
    private val engine: LlmEngineRepository,
    private val registry: ModelRegistryRepository,
) {
    suspend operator fun invoke(spec: ModelSpec) {
        engine.load(spec)
        registry.setDefaultModelId(spec.id)
        registry.recordUsed(spec)
    }
}

class UnloadModelUseCase @Inject constructor(
    private val engine: LlmEngineRepository,
    private val registry: ModelRegistryRepository,
) {
    suspend operator fun invoke(spec: ModelSpec) {
        if (spec.provider == ProviderId.LOCAL &&
            engine.loadedModelId == spec.id
        ) {
            engine.unload()
        }
        registry.clearUsed(spec)
        // Drop global active-model pointer too — chat headers bind to effectiveLastUsedModelIdFlow.
        registry.clearLastUsedIfMatches(spec.id)
        registry.clearDefaultIfMatches(spec.id)
    }
}

class SetDefaultModelUseCase @Inject constructor(
    private val registry: ModelRegistryRepository,
) {
    suspend operator fun invoke(modelId: String): Boolean = registry.setDefaultModelId(modelId)
}
