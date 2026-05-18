package com.swaptr.aide.domain.usecase

import com.swaptr.aide.data.catalog.ModelSpec
import com.swaptr.aide.data.model.ModelGateState
import com.swaptr.aide.data.model.ModelRegistryRepository
import javax.inject.Inject

class ResolveActiveModelUseCase @Inject constructor(
    private val registryRepo: ModelRegistryRepository,
) {
    operator fun invoke(): ModelSpec? =
        when (val g = registryRepo.gateStateFlow.value) {
            is ModelGateState.Ready -> g.spec
            else -> null
        }
}
