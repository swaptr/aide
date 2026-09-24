package com.sabreware.aide.data.label

import com.sabreware.aide.core.common.persist.DocState
import com.sabreware.aide.core.common.persist.DocumentStore
import com.sabreware.aide.core.domain.label.LabelStore
import com.sabreware.aide.core.domain.label.Labels
import kotlinx.coroutines.flow.StateFlow

/** [LabelStore] over the labels document. A transform that changes nothing writes nothing. */
class DocumentLabelStore(private val store: DocumentStore<Labels>) : LabelStore {
    override val state: StateFlow<DocState<Labels>> get() = store.state

    override suspend fun update(transform: (Labels) -> Labels) {
        store.update { current -> transform(current).takeUnless { it == current } ?: current }
    }
}
