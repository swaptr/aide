package com.sabreware.aide.core.domain.fakes

import com.sabreware.aide.core.common.persist.DocState
import com.sabreware.aide.core.domain.model.ModelSelection
import com.sabreware.aide.core.domain.model.ModelSelectionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

/**
 * In-memory [ModelSelectionStore]. Ready with [seed] unless [loaded] is false, which models the few ms
 * before the document's first read lands — flip it with [finishLoading].
 *
 *     FakeModelSelectionStore(ModelSelection(activeByModality = mapOf("asr" to "openai:whisper-1")))
 */
class FakeModelSelectionStore(
    seed: ModelSelection = ModelSelection(),
    loaded: Boolean = true,
) : ModelSelectionStore {

    private var value = seed

    override val state = MutableStateFlow<DocState<ModelSelection>>(
        if (loaded) DocState.Ready(seed) else DocState.Loading,
    )

    fun finishLoading() {
        state.value = DocState.Ready(value)
    }

    override suspend fun current(): ModelSelection =
        state.filterIsInstance<DocState.Ready<ModelSelection>>().first().value

    override suspend fun update(transform: (ModelSelection) -> ModelSelection) {
        value = transform(current()).pruned()
        state.value = DocState.Ready(value)
    }
}
