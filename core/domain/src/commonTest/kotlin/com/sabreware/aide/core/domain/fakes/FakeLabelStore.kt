package com.sabreware.aide.core.domain.fakes

import com.sabreware.aide.core.common.persist.DocState
import com.sabreware.aide.core.domain.label.LabelStore
import com.sabreware.aide.core.domain.label.Labels
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [LabelStore], Ready with [seed]. */
class FakeLabelStore(seed: Labels = Labels()) : LabelStore {
    override val state = MutableStateFlow<DocState<Labels>>(DocState.Ready(seed))

    val value: Labels get() = (state.value as DocState.Ready).value

    override suspend fun update(transform: (Labels) -> Labels) {
        state.value = DocState.Ready(transform(value))
    }
}
