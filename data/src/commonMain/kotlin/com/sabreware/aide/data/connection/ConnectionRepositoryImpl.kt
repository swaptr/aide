package com.sabreware.aide.data.connection

import com.sabreware.aide.core.common.persist.DocState
import com.sabreware.aide.core.common.persist.DocumentStore
import com.sabreware.aide.core.domain.connection.Connection
import com.sabreware.aide.core.domain.connection.ConnectionDraft
import com.sabreware.aide.core.domain.connection.ConnectionRepository
import com.sabreware.aide.core.domain.connection.Connections
import com.sabreware.aide.core.domain.connection.CreatedConnection
import com.sabreware.aide.core.domain.label.LabelStore
import com.sabreware.aide.core.domain.label.LabelSubject
import com.sabreware.aide.core.domain.model.ModelSelection
import com.sabreware.aide.core.domain.model.ModelSelectionStore
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.provider.ProviderConfig
import com.sabreware.aide.core.domain.secure.SecureStore
import com.sabreware.aide.data.catalog.RemoteCatalogCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

/**
 * [ConnectionRepository] over the connections document (the list) and the secure store (each key).
 *
 * The two halves are written in an order that never leaves a connection that has a key but no row: a create
 * stores the key first and the row second, a remove drops the row first. A key with no row is invisible and
 * costs nothing; a row whose key has not landed yet would build a provider that answers "add an API key".
 */
class ConnectionRepositoryImpl(
    private val store: DocumentStore<Connections>,
    private val secrets: SecureStore,
    private val catalogCache: RemoteCatalogCache,
    private val labels: LabelStore,
    private val selection: ModelSelectionStore,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ConnectionRepository {

    override val state: StateFlow<DocState<Connections>> get() = store.state

    private val ready: Flow<Connections> = store.state.filterIsInstance<DocState.Ready<Connections>>().map { it.value }

    override fun config(id: String): Flow<ProviderConfig?> = combine(
        ready.map { it[id]?.baseUrl }.distinctUntilChanged(),
        secrets.observe(Connection.secretKey(id)),
    ) { baseUrl, apiKey ->
        baseUrl?.let { ProviderConfig(baseUrl = it.trim(), apiKey = apiKey?.takeIf(String::isNotBlank)) }
    }.distinctUntilChanged()

    override suspend fun create(draft: ConnectionDraft): CreatedConnection {
        val baseUrl = draft.baseUrl.trim()
        val apiKey = draft.apiKey?.trim()?.takeIf { it.isNotEmpty() }
        sameAccount(draft.vendor.value, baseUrl, apiKey)?.let { return CreatedConnection(it, existing = true) }

        val id = Connection.newId(draft.vendor)
        secrets.write(mapOf(Connection.secretKey(id) to apiKey))
        var created: Connection? = null
        store.update { current ->
            val connection = Connection(
                id = id,
                vendor = draft.vendor.value,
                label = current.uniqueLabel(draft.label.trim().ifEmpty { draft.vendor.value }),
                baseUrl = baseUrl,
                createdAt = now(),
            )
            created = connection
            current.upsert(connection)
        }
        return CreatedConnection(created ?: error("Could not save the connection"), existing = false)
    }

    /** The connection that already IS this account — same vendor, endpoint and key — if there is one. */
    private suspend fun sameAccount(vendor: String, baseUrl: String, apiKey: String?): Connection? =
        store.awaitReady().list
            .filter { it.vendor == vendor && it.baseUrl.trimEnd('/') == baseUrl.trimEnd('/') }
            .firstOrNull { secrets.observe(Connection.secretKey(it.id)).first()?.takeIf(String::isNotBlank) == apiKey }

    override suspend fun update(id: String, draft: ConnectionDraft) {
        secrets.write(mapOf(Connection.secretKey(id) to draft.apiKey?.trim()?.takeIf { it.isNotEmpty() }))
        store.update { current ->
            val existing = current[id] ?: return@update current
            val baseUrl = draft.baseUrl.trim()
            if (existing.baseUrl == baseUrl) current else current.upsert(existing.copy(baseUrl = baseUrl))
        }
    }

    override suspend fun remove(id: String) {
        store.update { it.without(id) }
        secrets.write(mapOf(Connection.secretKey(id) to null))
        catalogCache.clear(ProviderId(id))
        labels.update { it.without(LabelSubject.ownedBy(id)) }
        selection.update { it.forgetting(ProviderId(id)) }
    }
}

/**
 * [ModelSelection] with every choice that pointed at [provider] dropped — a removed connection's models are
 * not "missing", they are gone because the user removed them, so nothing should keep offering them back.
 */
internal fun ModelSelection.forgetting(provider: ProviderId): ModelSelection {
    val gone = cards.values.filter { it.provider == provider.value }.mapTo(HashSet()) { it.id }
    val prefix = "${provider.value}:"
    fun ours(id: String?) = id != null && (id in gone || id.startsWith(prefix))
    return copy(
        activeByModality = activeByModality.filterValues { !ours(it) },
        lastUsedByTier = lastUsedByTier.filterValues { !ours(it) },
        lastUsedModelId = lastUsedModelId.takeUnless(::ours),
    )
}
