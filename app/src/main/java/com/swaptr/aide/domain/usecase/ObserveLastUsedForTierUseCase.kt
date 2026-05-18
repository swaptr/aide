package com.swaptr.aide.domain.usecase

import com.swaptr.aide.data.catalog.ProviderTier
import com.swaptr.aide.data.model.ModelRegistryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ObserveLastUsedForTierUseCase @Inject constructor(
    private val registry: ModelRegistryRepository,
) {
    operator fun invoke(tier: ProviderTier): Flow<String?> =
        registry.lastUsedByTier.map { it[tier] }
}
