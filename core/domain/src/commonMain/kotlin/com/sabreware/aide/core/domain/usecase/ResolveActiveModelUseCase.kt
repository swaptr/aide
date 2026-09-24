package com.sabreware.aide.core.domain.usecase

import com.sabreware.aide.core.domain.model.ModelGateState
import com.sabreware.aide.core.domain.model.ModelRegistryRepository
import com.sabreware.aide.core.domain.model.ModelSpec

class ResolveActiveModelUseCase(
    private val registryRepo: ModelRegistryRepository,
) {
    operator fun invoke(): ModelSpec? =
        when (val g = registryRepo.gateStateFlow.value) {
            is ModelGateState.Ready -> g.spec
            else -> null
        }
}
