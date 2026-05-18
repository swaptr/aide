package com.swaptr.aide.domain.usecase

import com.swaptr.aide.data.model.ModelRegistryRepository
import com.swaptr.aide.domain.model.ModelSummary
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveModelsUseCase @Inject constructor(
    private val registry: ModelRegistryRepository,
) {
    operator fun invoke(): Flow<List<ModelSummary>> = registry.observeModels()
}
