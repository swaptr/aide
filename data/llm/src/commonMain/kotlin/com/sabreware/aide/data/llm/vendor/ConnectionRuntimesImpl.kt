package com.sabreware.aide.data.llm.vendor

import com.sabreware.aide.core.domain.connection.Connection
import com.sabreware.aide.core.domain.connection.ConnectionRepository
import com.sabreware.aide.core.domain.connection.ConnectionRuntime
import com.sabreware.aide.core.domain.connection.ConnectionRuntimes
import com.sabreware.aide.core.domain.connection.VendorRegistry
import com.sabreware.aide.core.domain.connection.connections
import com.sabreware.aide.core.domain.util.AideLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * The live providers of every connection, kept in step with the connections document.
 *
 * ONE stateful collector, shared eagerly: every registry reads this same list, so a connection is built once
 * — one catalog collector, one cached listing read, one set of engines — however many capabilities it serves.
 *
 * A runtime lives as long as its connection and its endpoint. Renaming reuses it (a name is not something a
 * provider holds); moving it to another base URL rebuilds it, because what an endpoint serves (its speech and
 * image rows, its vendor quirks) is decided when the runtime is built; removing it cancels its scope, which
 * stops everything the runtime launched.
 *
 * Each runtime is built from the connection's first REAL config (the suspending `stateIn`), never from an
 * unread seed: a provider handed "no config" at construction would clear its cached catalog as if the user
 * had disconnected it. New connections are built concurrently, so one slow keystore read does not hold the
 * others back.
 */
class ConnectionRuntimesImpl(
    private val repository: ConnectionRepository,
    private val vendors: VendorRegistry,
    private val appScope: CoroutineScope,
) : ConnectionRuntimes {

    private class Live(val connection: Connection, val scope: CoroutineScope, val runtime: ConnectionRuntime)

    override val runtimes: StateFlow<List<ConnectionRuntime>?> = flow {
        val live = LinkedHashMap<String, Live>()
        repository.connections.collect { document ->
            val wanted = document.list.associateBy { it.id }
            live.values.removeAll { current ->
                val next = wanted[current.connection.id]
                val keep = next != null && next.baseUrl == current.connection.baseUrl
                if (!keep) current.scope.cancel()
                !keep
            }
            val added = document.list.filter { it.id !in live }
            coroutineScope { added.map { async { build(it) } }.awaitAll() }
                .filterNotNull()
                .forEach { live[it.connection.id] = it }
            emit(document.list.mapNotNull { live[it.id]?.runtime })
        }
    }.stateIn(appScope, SharingStarted.Eagerly, null)

    private suspend fun build(connection: Connection): Live? {
        val vendor = vendors[connection.vendor] ?: run {
            // A connection whose vendor this build does not contribute (a document from a newer build).
            AideLog.w(TAG, "no vendor '${connection.vendor}' for connection ${connection.id}; skipped")
            return null
        }
        val scope = CoroutineScope(appScope.coroutineContext + SupervisorJob(appScope.coroutineContext[Job]))
        val config = repository.config(connection.id).stateIn(scope)
        return Live(connection, scope, vendor.connect(connection, config, scope))
    }

    private companion object {
        const val TAG = "Connections"
    }
}
