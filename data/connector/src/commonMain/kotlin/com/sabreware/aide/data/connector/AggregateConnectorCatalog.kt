package com.sabreware.aide.data.connector

import com.sabreware.aide.core.domain.connector.Connector
import com.sabreware.aide.core.domain.connector.ConnectorCatalog
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectory
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryId
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectorySelection
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * The app-facing [ConnectorCatalog]: the priority-ordered merge of every *enabled* [ConnectorDirectory].
 *
 * Reactive — re-merges whenever the [ConnectorDirectorySelection] changes or any active directory's `catalog()`
 * re-emits (e.g. after a registry refresh), so there is no manual "poke". Seeded eagerly with the bundled
 * directories' synchronous [ConnectorDirectory.seed]s so [all] and the first frame are never empty while the
 * network slices warm up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AggregateConnectorCatalog(
    private val directories: Set<@JvmSuppressWildcards ConnectorDirectory>,
    private val selection: com.sabreware.aide.core.domain.connector.directory.ConnectorDirectorySelection,
    private val scope: CoroutineScope,
) : ConnectorCatalog {

    private fun activeFor(ids: Set<ConnectorDirectoryId>): List<ConnectorDirectory> =
        directories
            .filter { it.descriptor.id in ids }
            .sortedWith(compareBy({ it.descriptor.priority }, { it.descriptor.id.value }))

    private val merged: StateFlow<List<Connector>> =
        selection.enabledIds
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                val active = activeFor(ids)
                // combine() over an empty list never emits — short-circuit so "all disabled" yields empty.
                if (active.isEmpty()) flowOf(emptyList())
                else combine(active.map { it.catalog() }) { slices -> ConnectorMerge.mergeAll(slices.asList()) }
            }
            .distinctUntilChanged()
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                // Synchronous curated seed (bundled dirs); network dirs contribute [] until their flow emits.
                ConnectorMerge.mergeAll(directories.sortedBy { it.descriptor.priority }.map { it.seed() }),
            )

    override fun observeAll(): StateFlow<List<Connector>> = merged

    override suspend fun all(): List<Connector> = merged.value

    override suspend fun search(query: String): List<Connector> = coroutineScope {
        val active = activeFor(selection.enabledIds.first())
        val perDirectory = active
            .map { dir ->
                async {
                    runCatching { dir.search(query) }
                        .getOrElse { if (it is CancellationException) throw it else emptyList() }
                }
            }
            .awaitAll()
        ConnectorMerge.mergeAll(perDirectory)
    }
}
