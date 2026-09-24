package com.sabreware.aide.data.connector.directory

import com.sabreware.aide.core.domain.connector.Connector
import com.sabreware.aide.core.domain.connector.ConnectorPrefs
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectory
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryDescriptor
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryId
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryKind
import com.sabreware.aide.core.domain.connector.directory.ConnectorSearchKind
import com.sabreware.aide.core.domain.fakes.FakePreferenceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

class ConnectorDirectorySelectionTest {

    private fun dir(id: String): ConnectorDirectory = object : ConnectorDirectory {
        override val descriptor = ConnectorDirectoryDescriptor(
            ConnectorDirectoryId(id), id, "", ConnectorDirectoryKind.BUNDLED, ConnectorSearchKind.LOCAL, 10,
        )
        override fun catalog(): Flow<List<Connector>> = flowOf(emptyList())
        override suspend fun refresh() = Unit
        override suspend fun search(query: String): List<Connector> = emptyList()
    }

    @Test
    fun enabled_isAllIdsMinusDisabled() = runTest {
        val sel = DefaultConnectorDirectorySelection(
            FakePreferenceStore(ConnectorPrefs.DisabledDirectories to setOf("b")),
            setOf(dir("a"), dir("b"), dir("c")),
        )
        assertEquals(
            setOf(ConnectorDirectoryId("a"), ConnectorDirectoryId("c")),
            sel.enabledIds.first(),
        )
    }

    @Test
    fun emptyDisabled_allEnabled() = runTest {
        val sel = DefaultConnectorDirectorySelection(FakePreferenceStore(), setOf(dir("a"), dir("b")))
        assertEquals(setOf(ConnectorDirectoryId("a"), ConnectorDirectoryId("b")), sel.enabledIds.first())
    }

    @Test
    fun disabledUnknownId_isIgnored() = runTest {
        val sel = DefaultConnectorDirectorySelection(
            FakePreferenceStore(ConnectorPrefs.DisabledDirectories to setOf("ghost")),
            setOf(dir("a")),
        )
        assertEquals(setOf(ConnectorDirectoryId("a")), sel.enabledIds.first())
    }
}
