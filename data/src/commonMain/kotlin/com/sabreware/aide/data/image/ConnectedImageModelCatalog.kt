package com.sabreware.aide.data.image

import com.sabreware.aide.core.domain.connection.ConnectionRuntimes
import com.sabreware.aide.core.domain.image.ImageModelCatalog
import com.sabreware.aide.core.domain.model.ImageModelSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** [ImageModelCatalog] as the union of every connection's image rows, in connection order. */
class ConnectedImageModelCatalog(runtimes: ConnectionRuntimes, scope: CoroutineScope) : ImageModelCatalog {
    override val models: StateFlow<List<ImageModelSpec>?> = runtimes.runtimes
        .map { list -> list?.flatMap { it.imageModels } }
        .stateIn(scope, SharingStarted.Eagerly, runtimes.runtimes.value?.flatMap { it.imageModels })
}
