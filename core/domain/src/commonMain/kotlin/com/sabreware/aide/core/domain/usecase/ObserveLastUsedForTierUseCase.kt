package com.sabreware.aide.core.domain.usecase

import com.sabreware.aide.core.domain.model.ModelRegistryRepository
import com.sabreware.aide.core.domain.model.ProviderTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ObserveLastUsedForTierUseCase(
    private val registry: ModelRegistryRepository,
) {
    operator fun invoke(tier: ProviderTier): Flow<String?> =
        registry.lastUsedByTier.map { it[tier] }
}
