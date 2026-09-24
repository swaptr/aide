package com.sabreware.aide.ui.models.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabreware.aide.core.designsystem.state.UiState
import com.sabreware.aide.core.designsystem.state.stateInUi
import com.sabreware.aide.core.domain.connection.Connection
import com.sabreware.aide.core.domain.connection.ConnectionDraft
import com.sabreware.aide.core.domain.connection.ConnectionKind
import com.sabreware.aide.core.domain.connection.ConnectionRepository
import com.sabreware.aide.core.domain.connection.ConnectionRuntimes
import com.sabreware.aide.core.domain.connection.CreatedConnection
import com.sabreware.aide.core.domain.connection.ProviderDirectory
import com.sabreware.aide.core.domain.connection.ProviderInfo
import com.sabreware.aide.core.domain.connection.ServiceDescriptor
import com.sabreware.aide.core.domain.connection.VendorRegistry
import com.sabreware.aide.core.domain.error.UserError
import com.sabreware.aide.core.domain.connection.connections
import com.sabreware.aide.core.domain.llm.ManageableRegistry
import com.sabreware.aide.core.domain.llm.RemoteCatalogState
import com.sabreware.aide.core.domain.llm.currentSpecs
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ProviderCatalog
import com.sabreware.aide.core.domain.model.ProviderId
import kotlinx.coroutines.flow.StateFlow
import com.sabreware.aide.core.domain.provider.ConnectionTestResult
import com.sabreware.aide.core.domain.provider.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** How a connection's model list stands, as the Connections page shows it. */
enum class ConnectionStatus(val label: String) {
    Ready("Ready"),
    Refreshing("Refreshing"),
    Failed("Failed"),
    NoKey("Needs a key"),
    Waiting("Loading"),
}

/** One connection as every connections surface draws it. */
data class ConnectionItem(
    val connection: Connection,
    val info: ProviderInfo,
    /** The service it is ("OpenRouter"), from its vendor and endpoint. */
    val service: ServiceDescriptor?,
    val status: ConnectionStatus,
    /** Chat models listed, plus the curated speech and image models its endpoint serves. */
    val modelCount: Int,
    val fetchedAt: Long? = null,
    /** The last refresh's failure, classified for people (see [UserError]). */
    val error: UserError? = null,
    /** What this connection's endpoint actually serves (an Ollama endpoint: chat only). */
    val modalities: Set<Modality> = emptySet(),
) {
    val id: String get() = connection.id
    val providerId: ProviderId get() = connection.providerId
    val name: String get() = info.name
    val kind: ConnectionKind get() = info.kind
    val tags: List<String> get() = info.tags
    /** The endpoint without its scheme — what a user recognises ("openrouter.ai/api/v1"). */
    val host: String get() = connection.baseUrl.substringAfter("://").trimEnd('/')
}

/**
 * The user's connections and every operation on them: add (deduplicated), edit, remove, refresh, test. One
 * ViewModel for the Connections page, a connection's own page, the connect form and the add-model tabs, so
 * they all agree on what a connection is called and how it stands.
 */
class ConnectionsViewModel(
    private val repository: ConnectionRepository,
    private val directory: ProviderDirectory,
    vendorRegistry: VendorRegistry,
    private val manageables: ManageableRegistry,
    runtimes: ConnectionRuntimes,
) : ViewModel() {

    /** Every connectable service, each once — what "Connect" offers, whatever modality the user came for. */
    val services: List<ServiceDescriptor> = vendorRegistry.services
    private val registry = vendorRegistry

    private val catalogs: Flow<Map<String, RemoteCatalogState>> = manageables.flow.flatMapLatest { list ->
        when {
            list.isNullOrEmpty() -> flowOf(emptyMap())
            else -> combine(list.map { p -> p.management.remoteCatalogFlow.map { p.id.value to it } }) { it.toMap() }
        }
    }

    private class Served(val extraModels: Int, val modalities: Set<Modality>)

    private val served: Flow<Map<String, Served>> = runtimes.runtimes.map { list ->
        list.orEmpty().associate { runtime ->
            runtime.id.value to Served(
                extraModels = runtime.speechModels.size + runtime.imageModels.size,
                modalities = buildSet {
                    if (runtime.chat != null) add(Modality.Chat)
                    runtime.speechModels.mapTo(this) { it.modality }
                    if (runtime.imageModels.isNotEmpty()) add(Modality.Image)
                },
            )
        }
    }

    val items: StateFlow<UiState<List<ConnectionItem>>> = combine(
        repository.connections,
        directory.connections,
        catalogs,
        served,
    ) { document, infos, states, extra ->
        document.list.map { connection ->
            val state = states[connection.id]
            val info = infos?.get(connection.id) ?: directory.infoOf(connection.providerId)
            ConnectionItem(
                connection = connection,
                info = info,
                service = registry.serviceOf(connection),
                status = statusOf(state, hasCatalog = state != null),
                modelCount = (state?.currentSpecs?.size ?: 0) + (extra[connection.id]?.extraModels ?: 0),
                modalities = extra[connection.id]?.modalities.orEmpty(),
                fetchedAt = (state as? RemoteCatalogState.Ready)?.fetchedAt,
                error = (state as? RemoteCatalogState.Failed)?.message?.let {
                    UserError.from(it, host = connection.baseUrl.substringAfter("://").substringBefore('/'))
                },
            )
        }
    }.stateInUi(viewModelScope)

    private fun statusOf(state: RemoteCatalogState?, hasCatalog: Boolean): ConnectionStatus = when {
        !hasCatalog -> ConnectionStatus.Ready // a speech-only vendor has no list to fetch
        state is RemoteCatalogState.Refreshing -> ConnectionStatus.Refreshing
        state is RemoteCatalogState.Failed -> ConnectionStatus.Failed
        state is RemoteCatalogState.Ready -> ConnectionStatus.Ready
        state is RemoteCatalogState.Unconfigured -> ConnectionStatus.NoKey
        else -> ConnectionStatus.Waiting
    }

    fun service(id: String): ServiceDescriptor? = registry.service(id)


    /** [id]'s endpoint and key, for the edit form. */
    fun config(id: String): Flow<ProviderConfig?> = repository.config(id)

    fun create(draft: ConnectionDraft, onDone: (CreatedConnection) -> Unit) {
        viewModelScope.launch { onDone(repository.create(draft)) }
    }

    fun update(id: String, draft: ConnectionDraft, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.update(id, draft)
            onDone()
        }
    }

    fun remove(ids: List<String>) {
        viewModelScope.launch { ids.forEach { repository.remove(it) } }
    }

    fun refresh(ids: List<String>) {
        ids.forEach { id ->
            viewModelScope.launch { runCatching { manageables.await(ProviderId(id))?.management?.refreshCatalog() } }
        }
    }

    fun refreshAll() {
        manageables.all.filterNot { ProviderCatalog.isBuiltIn(it.id) }.forEach { provider ->
            viewModelScope.launch { runCatching { provider.management.refreshCatalog() } }
        }
    }

    suspend fun test(id: String): ConnectionTestResult =
        manageables.await(ProviderId(id))?.management?.testConnection()
            ?: ConnectionTestResult.Ok(0)
}
