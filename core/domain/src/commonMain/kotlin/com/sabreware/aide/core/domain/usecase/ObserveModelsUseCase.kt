package com.sabreware.aide.core.domain.usecase

import com.sabreware.aide.core.domain.model.ModelRegistryRepository
import com.sabreware.aide.core.domain.model.ModelSummary
import kotlinx.coroutines.flow.StateFlow

class ObserveModelsUseCase(
    private val registry: ModelRegistryRepository,
) {
    /** The app-wide cached snapshot — null until the session's first resolution (see [ModelRegistryRepository.models]). */
    operator fun invoke(): StateFlow<List<ModelSummary>?> = registry.models
}
