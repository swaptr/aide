package com.sabreware.aide.data.connector

import com.sabreware.aide.core.domain.connector.Connector
import com.sabreware.aide.core.domain.connector.ConnectorAuthType
import com.sabreware.aide.core.domain.connector.ConnectorCategory
import com.sabreware.aide.core.domain.connector.ConnectorSource
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectory
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryDescriptor
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryId
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryKind
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectorySelection
import com.sabreware.aide.core.domain.connector.directory.ConnectorSearchKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AggregateConnectorCatalogTest {

    private fun con(name: String, domain: String, source: ConnectorSource = ConnectorSource.REGISTRY) =
        Connector("id:$name", name, "d", ConnectorCategory.OTHER, "https://$name", ConnectorAuthType.UNKNOWN, domain, null, null, source)

    private class FakeDir(
        id: String,
        priority: Int,
        private val items: MutableStateFlow<List<Connector>>,
        private val seedItems: List<Connector> = emptyList(),
        private val onSearch: (String) -> List<Connector> = { items.value },
        private val searchThrows: Boolean = false,
        kind: ConnectorDirectoryKind = ConnectorDirectoryKind.REGISTRY,
    ) : ConnectorDirectory {
        override val descriptor = ConnectorDirectoryDescriptor(
            ConnectorDirectoryId(id), id, "", kind, ConnectorSearchKind.LOCAL, priority,
        )
        override fun seed(): List<Connector> = seedItems
        override fun catalog(): Flow<List<Connector>> = items
        override suspend fun refresh() = Unit
        override suspend fun search(query: String): List<Connector> {
            if (searchThrows) throw RuntimeException("boom")
            return onSearch(query)
        }
    }

    private class FakeSelection(initial: Set<String>) : ConnectorDirectorySelection {
        val flow = MutableStateFlow(initial.map { ConnectorDirectoryId(it) }.toSet())
        override val enabledIds: Flow<Set<ConnectorDirectoryId>> = flow
        override val enabledNow: Set<ConnectorDirectoryId> get() = flow.value
        override suspend fun setEnabled(id: ConnectorDirectoryId, enabled: Boolean) {
            flow.value = if (enabled) flow.value + id else flow.value - id
        }
    }

    @Test
    fun all_returnsEagerSeed_beforeFlowsEmit() = runTest {
        // backgroundScope uses StandardTestDispatcher and is NOT advanced here, so the merge collector
        // hasn't run — `all()` must serve the synchronous bundled seed.
        val curated = FakeDir(
            "curated", 10, MutableStateFlow(emptyList()),
            seedItems = listOf(con("Alpha", "alpha.com", ConnectorSource.CURATED)),
            kind = ConnectorDirectoryKind.BUNDLED,
        )
        val registry = FakeDir("reg", 20, MutableStateFlow(emptyList()))
        val catalog = AggregateConnectorCatalog(setOf(curated, registry), FakeSelection(setOf("curated", "reg")), backgroundScope)
        assertEquals(listOf("Alpha"), catalog.all().map { it.name })
    }

    @Test
    fun observeAll_reflectsSelectionToggle() = runTest {
        // Unconfined scope so the eager stateIn collector runs as values arrive (no manual advancing).
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val curated = FakeDir("curated", 10, MutableStateFlow(listOf(con("Alpha", "alpha.com"))), kind = ConnectorDirectoryKind.BUNDLED)
        val registry = FakeDir("reg", 20, MutableStateFlow(listOf(con("Beta", "beta.com"))))
        val sel = FakeSelection(setOf("curated"))
        val catalog = AggregateConnectorCatalog(setOf(curated, registry), sel, scope)

        assertEquals(listOf("Alpha"), catalog.all().map { it.name })

        sel.flow.value = setOf(ConnectorDirectoryId("curated"), ConnectorDirectoryId("reg"))
        assertEquals(listOf("Alpha", "Beta"), catalog.all().map { it.name })

        scope.cancel()
    }

    @Test
    fun observeAll_zeroEnabled_isEmpty() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val registry = FakeDir("reg", 20, MutableStateFlow(listOf(con("Beta", "beta.com"))))
        val catalog = AggregateConnectorCatalog(setOf(registry), FakeSelection(emptySet()), scope)
        assertEquals(emptyList<String>(), catalog.all().map { it.name })
        scope.cancel()
    }

    @Test
    fun search_isResilient_whenOneDirectoryThrows() = runTest {
        val ok = FakeDir(
            "ok", 10, MutableStateFlow(emptyList()),
            onSearch = { listOf(con("Alpha", "alpha.com")) }, kind = ConnectorDirectoryKind.BUNDLED,
        )
        val bad = FakeDir("bad", 20, MutableStateFlow(emptyList()), searchThrows = true)
        val catalog = AggregateConnectorCatalog(setOf(ok, bad), FakeSelection(setOf("ok", "bad")), backgroundScope)
        assertEquals(listOf("Alpha"), catalog.search("a").map { it.name })
    }
}
