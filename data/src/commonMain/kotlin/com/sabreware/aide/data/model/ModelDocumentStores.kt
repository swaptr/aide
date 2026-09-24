package com.sabreware.aide.data.model

import com.sabreware.aide.core.common.persist.DocState
import com.sabreware.aide.core.common.persist.DocumentStore
import com.sabreware.aide.core.domain.model.ImportedModels
import com.sabreware.aide.core.domain.model.ImportedModelsStore
import com.sabreware.aide.core.domain.model.ModelSelection
import com.sabreware.aide.core.domain.model.ModelSelectionStore
import com.sabreware.aide.core.domain.model.SamplerOverrides
import com.sabreware.aide.core.domain.model.SamplerOverridesStore
import kotlinx.coroutines.flow.StateFlow

/** [ModelSelectionStore] over the document file. Every write prunes cards nothing references any more. */
class DocumentModelSelectionStore(private val store: DocumentStore<ModelSelection>) : ModelSelectionStore {
    override val state: StateFlow<DocState<ModelSelection>> get() = store.state

    override suspend fun current(): ModelSelection = store.awaitReady()

    override suspend fun update(transform: (ModelSelection) -> ModelSelection) {
        store.update { current ->
            val next = transform(current).pruned()
            // Same value → hand DataStore the same instance, which it recognises and does not rewrite.
            if (next == current) current else next
        }
    }
}

/** [SamplerOverridesStore] over the document file. */
class DocumentSamplerOverridesStore(private val store: DocumentStore<SamplerOverrides>) : SamplerOverridesStore {
    override val state: StateFlow<DocState<SamplerOverrides>> get() = store.state

    override suspend fun current(): SamplerOverrides = store.awaitReady()

    override suspend fun update(transform: (SamplerOverrides) -> SamplerOverrides): SamplerOverrides? =
        store.update { current -> transform(current).takeUnless { it == current } ?: current }
}

/** [ImportedModelsStore] over the document file. */
class DocumentImportedModelsStore(private val store: DocumentStore<ImportedModels>) : ImportedModelsStore {
    override val state: StateFlow<DocState<ImportedModels>> get() = store.state

    override suspend fun current(): ImportedModels = store.awaitReady()

    override suspend fun update(transform: (ImportedModels) -> ImportedModels): ImportedModels? =
        store.update { current -> transform(current).takeUnless { it == current } ?: current }
}
