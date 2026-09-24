package com.sabreware.aide.core.domain.fakes

import com.sabreware.aide.core.common.persist.DocState
import com.sabreware.aide.core.domain.model.SamplerOverrides
import com.sabreware.aide.core.domain.model.SamplerOverridesStore
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [SamplerOverridesStore], Ready with [seed]. */
class FakeSamplerOverridesStore(seed: SamplerOverrides = SamplerOverrides()) : SamplerOverridesStore {
    override val state = MutableStateFlow<DocState<SamplerOverrides>>(DocState.Ready(seed))

    private val value: SamplerOverrides get() = (state.value as DocState.Ready).value

    override suspend fun current(): SamplerOverrides = value

    override suspend fun update(transform: (SamplerOverrides) -> SamplerOverrides): SamplerOverrides =
        transform(value).also { state.value = DocState.Ready(it) }
}
