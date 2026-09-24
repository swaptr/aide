package com.sabreware.aide.core.domain.connection

import com.sabreware.aide.core.common.persist.DocState
import com.sabreware.aide.core.domain.provider.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/**
 * The user's connections: the list (a document) and each one's key (the secure store), managed as one thing.
 *
 * Every mutation is a read-modify-write against the file, never against [state]'s replay value.
 */
interface ConnectionRepository {
    /** [DocState.Loading] until the document's first read lands. */
    val state: StateFlow<DocState<Connections>>

    /** Base URL + key of [id]; null when the connection does not exist (or was removed). */
    fun config(id: String): Flow<ProviderConfig?>

    /**
     * Adds a connection; a blank or taken label is replaced by a unique one.
     *
     * Any number of connections may share a vendor or an endpoint — each key is its own account. The one
     * thing never stored twice is the SAME account: a draft whose vendor, endpoint and key all match an
     * existing connection returns that connection ([CreatedConnection.existing]) instead of a copy that would
     * list every model twice under two names.
     */
    suspend fun create(draft: ConnectionDraft): CreatedConnection

    /** Replaces [id]'s endpoint and key with [draft]'s (its vendor and name never change here). */
    suspend fun update(id: String, draft: ConnectionDraft)

    /**
     * Removes [id] and everything that only meant something through it: its key, its cached catalog, the
     * labels on it and on its models, and any model choice that pointed at it.
     */
    suspend fun remove(id: String)
}

/** What [ConnectionRepository.create] did: stored [connection], or found it already connected. */
data class CreatedConnection(val connection: Connection, val existing: Boolean)

/** The Ready values of [ConnectionRepository.state]; nothing while it is still loading. */
val ConnectionRepository.connections: Flow<Connections>
    get() = state.filterIsInstance<DocState.Ready<Connections>>().map { it.value }

/** The current connections, or null while the document is still loading. For painting only. */
val ConnectionRepository.connectionsNow: Connections?
    get() = (state.value as? DocState.Ready)?.value
