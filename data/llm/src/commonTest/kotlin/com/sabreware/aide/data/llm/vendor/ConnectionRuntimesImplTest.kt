package com.sabreware.aide.data.llm.vendor

import com.sabreware.aide.core.common.persist.DocState
import com.sabreware.aide.core.domain.connection.Connection
import com.sabreware.aide.core.domain.connection.ConnectionDraft
import com.sabreware.aide.core.domain.connection.ConnectionRepository
import com.sabreware.aide.core.domain.connection.ConnectionRuntime
import com.sabreware.aide.core.domain.connection.Connections
import com.sabreware.aide.core.domain.connection.CreatedConnection
import com.sabreware.aide.core.domain.connection.Vendor
import com.sabreware.aide.core.domain.connection.VendorDescriptor
import com.sabreware.aide.core.domain.connection.VendorId
import com.sabreware.aide.core.domain.connection.VendorRegistry
import com.sabreware.aide.core.domain.provider.ProviderConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionRuntimesImplTest {

    private class FakeRepository : ConnectionRepository {
        override val state = MutableStateFlow<DocState<Connections>>(DocState.Loading)

        fun publish(vararg connections: Connection) {
            state.value = DocState.Ready(Connections(connections.toList()))
        }

        override fun config(id: String): Flow<ProviderConfig?> = state.map { doc ->
            (doc as? DocState.Ready)?.value?.get(id)?.let { ProviderConfig(it.baseUrl, "key-$id") }
        }

        override suspend fun create(draft: ConnectionDraft): CreatedConnection = error("unused")
        override suspend fun update(id: String, draft: ConnectionDraft) = error("unused")
        override suspend fun remove(id: String) = error("unused")
    }

    /** Records every runtime it builds, and the scope and config each was handed. */
    private class FakeVendor : Vendor {
        val built = mutableListOf<Built>()

        class Built(val connection: Connection, val config: StateFlow<ProviderConfig?>, val scope: CoroutineScope)

        override val descriptor = VendorDescriptor(VendorId("fake"), "Fake", services = emptyList())

        override fun connect(connection: Connection, config: StateFlow<ProviderConfig?>, scope: CoroutineScope): ConnectionRuntime {
            built += Built(connection, config, scope)
            return ConnectionRuntime(connection)
        }
    }

    private val a = Connection(id = "fake-aaaaaa", vendor = "fake", label = "A", baseUrl = "https://a.example/v1")
    private val b = Connection(id = "fake-bbbbbb", vendor = "fake", label = "B", baseUrl = "https://b.example/v1")

    @Test
    fun `runtimes are null until the connections document is read`() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeRepository()
        val runtimes = ConnectionRuntimesImpl(repo, VendorRegistry(listOf(FakeVendor())), backgroundScope).runtimes

        assertNull(runtimes.value, "not read yet must not look like nothing connected")

        repo.publish()
        assertEquals(emptyList(), runtimes.value)
    }

    @Test
    fun `each connection is built once with its real config, in document order`() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeRepository()
        val vendor = FakeVendor()
        val runtimes = ConnectionRuntimesImpl(repo, VendorRegistry(listOf(vendor)), backgroundScope).runtimes

        repo.publish(a, b)

        assertEquals(listOf(a.id, b.id), runtimes.value!!.map { it.id.value })
        assertEquals(2, vendor.built.size)
        assertEquals(ProviderConfig(a.baseUrl, "key-${a.id}"), vendor.built.first { it.connection.id == a.id }.config.value)
    }

    @Test
    fun `a rename reuses the runtime`() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeRepository()
        val vendor = FakeVendor()
        val runtimes = ConnectionRuntimesImpl(repo, VendorRegistry(listOf(vendor)), backgroundScope).runtimes

        repo.publish(a)
        val first = runtimes.value!!.single()

        repo.publish(a.copy(label = "Renamed"))

        assertSame(first, runtimes.value!!.single())
        assertEquals(1, vendor.built.size)
        assertTrue(vendor.built.single().scope.isActive)
    }

    @Test
    fun `a new base url rebuilds the runtime and cancels the old one`() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeRepository()
        val vendor = FakeVendor()
        val runtimes = ConnectionRuntimesImpl(repo, VendorRegistry(listOf(vendor)), backgroundScope).runtimes

        repo.publish(a)
        val first = runtimes.value!!.single()

        val moved = a.copy(baseUrl = "http://localhost:11434/v1")
        repo.publish(moved)

        val second = runtimes.value!!.single()
        assertFalse(first === second)
        assertEquals(moved.baseUrl, second.connection.baseUrl)
        assertEquals(2, vendor.built.size)
        assertFalse(vendor.built[0].scope.isActive, "the old endpoint's runtime is stopped")
        assertTrue(vendor.built[1].scope.isActive)
    }

    @Test
    fun `removing a connection cancels its scope and drops it`() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeRepository()
        val vendor = FakeVendor()
        val runtimes = ConnectionRuntimesImpl(repo, VendorRegistry(listOf(vendor)), backgroundScope).runtimes

        repo.publish(a, b)
        repo.publish(b)

        assertEquals(listOf(b.id), runtimes.value!!.map { it.id.value })
        assertFalse(vendor.built.first { it.connection.id == a.id }.scope.isActive)
        assertTrue(vendor.built.first { it.connection.id == b.id }.scope.isActive)
    }

    @Test
    fun `a connection whose vendor is not contributed is skipped, not fatal`() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeRepository()
        val runtimes = ConnectionRuntimesImpl(repo, VendorRegistry(listOf(FakeVendor())), backgroundScope).runtimes

        repo.publish(a, a.copy(id = "future-cccccc", vendor = "future"))

        assertEquals(listOf(a.id), runtimes.value!!.map { it.id.value })
    }
}
