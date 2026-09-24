package com.sabreware.aide.core.domain.fakes

import com.sabreware.aide.core.common.persist.DocState
import com.sabreware.aide.core.domain.model.ImportedModels
import com.sabreware.aide.core.domain.model.ImportedModelsStore
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [ImportedModelsStore], Ready with [seed]. */
class FakeImportedModelsStore(seed: ImportedModels = ImportedModels()) : ImportedModelsStore {
    override val state = MutableStateFlow<DocState<ImportedModels>>(DocState.Ready(seed))

    private val value: ImportedModels get() = (state.value as DocState.Ready).value

    override suspend fun current(): ImportedModels = value

    override suspend fun update(transform: (ImportedModels) -> ImportedModels): ImportedModels =
        transform(value).also { state.value = DocState.Ready(it) }
}
