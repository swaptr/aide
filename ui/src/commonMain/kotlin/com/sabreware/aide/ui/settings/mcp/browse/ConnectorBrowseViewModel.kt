package com.sabreware.aide.ui.settings.mcp.browse

import com.sabreware.aide.core.designsystem.state.stateInUi
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import com.sabreware.aide.core.designsystem.state.UiState
import com.sabreware.aide.core.domain.connector.ConnectCoordinator
import com.sabreware.aide.core.domain.connector.ConnectStep
import com.sabreware.aide.core.domain.connector.Connector
import com.sabreware.aide.core.domain.connector.ConnectorAuthType
import com.sabreware.aide.core.domain.connector.ConnectorCatalog
import com.sabreware.aide.core.domain.mcp.McpConnections
import com.sabreware.aide.core.domain.mcp.McpServerConfig
import com.sabreware.aide.core.domain.mcp.McpServerRepository
import com.sabreware.aide.core.domain.mcp.McpServerStatus
import com.sabreware.aide.core.domain.util.AideLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Browse-connectors catalog. Combines the merged [ConnectorCatalog] with the installed configs
 * ([McpServerRepository]) + live status ([McpConnections]) so each catalog row shows Connect / Connecting /
 * Connected. Installed *custom* servers (URLs not in the catalog) surface in their own section so they stay
 * manageable. NONE connectors connect directly; HEADER opens the custom form (handled in the screen);
 * OAUTH/UNKNOWN are wired in Phase 5.
 */
class ConnectorBrowseViewModel(
    private val catalog: ConnectorCatalog,
    private val repo: McpServerRepository,
    private val manager: McpConnections,
    private val coordinator: ConnectCoordinator,
    val iconLoader: ImageLoader,
) : ViewModel() {

    @Immutable
    data class ConnectorRow(val connector: Connector, val installed: Boolean, val connected: Boolean)

    @Immutable
    data class InstalledRow(
        val url: String,
        val name: String,
        val enabled: Boolean,
        val connected: Boolean,
        val toolCount: Int,
    )

    /**
     * Named for the screen, not `UiState` — that name belongs to the design-system kit
     * ([com.sabreware.aide.core.designsystem.state.UiState]), and a nested class shadowing it in the one
     * ViewModel with a network-backed source is exactly how the kit gets bypassed.
     */
    data class BrowseState(
        /** True until [combine] produces its first value (one of the source flows — e.g. the Room-backed
         *  installed configs — is still warming up). The screen shows a skeleton, not the empty state, so
         *  "No connectors" never flashes before the catalog has actually loaded. */
        val loading: Boolean = true,
        val query: String = "",
        val connectors: List<ConnectorRow> = emptyList(),
        val installedCustom: List<InstalledRow> = emptyList(),
        val busyUrl: String? = null,
        val message: String? = null,
    )

    private data class Transient(val busyUrl: String? = null, val message: String? = null)

    private val query = MutableStateFlow("")
    private val transient = MutableStateFlow(Transient())

    private val _launchBrowser = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Authorization URLs to open in a Custom Tab; the screen collects these and launches the browser. */
    val launchBrowser: SharedFlow<String> = _launchBrowser.asSharedFlow()

    /** Screen returned to the foreground. If a sign-in is mid-flight and the user dismissed the browser,
     *  cancel it (no-op once the redirect has arrived). Both redirect strategies resolve as cancelled. */
    fun onReturnedToForeground() {
        if (uiState.value.busyUrl != null) coordinator.cancelPendingSignIn()
    }

    val uiState: StateFlow<BrowseState> =
        combine(catalog.observeAll(), repo.servers.filterNotNull(), manager.status, query, transient, ::browseState)
            // The one screen whose source really is the network. A failed catalog fetch used to cancel the
            // sharing coroutine, leaving `loading = true` — a skeleton that shimmers forever with no error
            // and no retry.
            .stateInUi(viewModelScope, seed()) { error ->
                BrowseState(loading = false, message = error.message ?: "Could not load the connector catalog.")
            }

    // Seeded from the caches, like the Connectors list: the catalog is an eager in-memory state (bundled
    // directories at worst) and the installed list is warmed by the reconnect bootstrap, so an open after
    // that composes with its rows on the first frame. Only a cold installed list still shows the skeleton.
    private fun seed(): BrowseState =
        repo.servers.value?.let { configs ->
            browseState(catalog.observeAll().value, configs, manager.status.value, query.value, transient.value)
        } ?: BrowseState()

    private fun browseState(
        all: List<Connector>,
        configs: List<McpServerConfig>,
        statuses: List<McpServerStatus>,
        q: String,
        t: Transient,
    ): BrowseState {
        val installedUrls = configs.map { it.url }.toSet()
        val connectedUrls = statuses.map { it.url }.toSet()
        val catalogUrls = all.map { it.serverUrl }.toSet()

        val rows = all
            .asSequence()
            .filter { q.isBlank() || it.name.contains(q, true) || it.description.contains(q, true) }
            .map { ConnectorRow(it, it.serverUrl in installedUrls, it.serverUrl in connectedUrls) }
            .toList()

        val custom = configs
            .filter { it.url !in catalogUrls }
            .map { cfg ->
                val status = statuses.firstOrNull { it.url == cfg.url }
                InstalledRow(cfg.url, hostLabel(cfg.url), cfg.enabled, status != null, status?.toolNames?.size ?: 0)
            }

        return BrowseState(
            loading = false, query = q, connectors = rows, installedCustom = custom, busyUrl = t.busyUrl,
            message = t.message,
        )
    }

    fun setQuery(value: String) { query.value = value }

    /**
     * The search source for the catalog page header ([rememberSearchResults]). Delegates to [ConnectorCatalog.search], which fans
     * out across every enabled directory (local filter and/or remote `?search=` endpoints) and merges the
     * results — so search reaches the full registry, not just the cached slice. A one-shot suspend snapshot
     * (not the reactive [uiState] query); installed/connected flags are reconciled live by the search screen.
     */
    suspend fun searchConnectors(query: String): List<ConnectorRow> {
        val results = catalog.search(query)
        val installedUrls = repo.currentServers().map { it.url }.toSet()
        val connectedUrls = manager.status.first().map { it.url }.toSet()
        return results.map { ConnectorRow(it, it.serverUrl in installedUrls, it.serverUrl in connectedUrls) }
    }

    fun clearMessage() = transient.update { it.copy(message = null) }

    /**
     * The shared "Connect" decision for every surface (catalog screen, search screen, chat sheet): HEADER-auth
     * connectors need the custom-server form to collect the header, so route them there via [onNeedsHeaderForm];
     * NONE/OAUTH/UNKNOWN connect directly through [connect]. Returns true when it connected directly (a sheet
     * caller can then pop back) and false when it deferred to the form.
     */
    fun connectOrPromptHeader(connector: Connector, onNeedsHeaderForm: (serverUrl: String) -> Unit): Boolean =
        if (connector.authType == ConnectorAuthType.HEADER) {
            onNeedsHeaderForm(connector.serverUrl)
            false
        } else {
            connect(connector)
            true
        }

    /**
     * Connects via the coordinator. NONE/UNKNOWN connect directly; OAUTH runs the sign-in flow, emitting the
     * authorization URL on [launchBrowser] for the screen to open in a Custom Tab. HEADER is routed to the
     * custom-server form by [connectOrPromptHeader] before this is called.
     */
    fun connect(connector: Connector) {
        AideLog.i("ConnOAuth", "connect() start name=${connector.name} authType=${connector.authType} url=${connector.serverUrl}")
        transient.update { it.copy(busyUrl = connector.serverUrl, message = null) }
        viewModelScope.launch {
            val step = coordinator.connect(connector) { url ->
                AideLog.i("ConnOAuth", "coordinator emitting authUrl, subscribers=${_launchBrowser.subscriptionCount.value}")
                _launchBrowser.emit(url)
            }
            AideLog.i("ConnOAuth", "connect() resolved step=$step")
            transient.update {
                when (step) {
                    is ConnectStep.Done -> Transient()
                    is ConnectStep.Failed -> Transient(message = step.reason)
                }
            }
        }
    }

    fun disconnect(url: String) {
        viewModelScope.launch {
            repo.remove(url)
            manager.disconnect(url)
        }
    }

    fun setEnabled(url: String, enabled: Boolean) {
        viewModelScope.launch {
            repo.setEnabled(url, enabled)
            manager.setEnabled(url, enabled)
        }
    }

    fun reconnect(url: String) {
        transient.update { it.copy(busyUrl = url, message = null) }
        viewModelScope.launch {
            val config = repo.currentServers().find { it.url == url }
                ?: run { transient.update { Transient() }; return@launch }
            val result = manager.connect(config)
            transient.update {
                if (result.isSuccess) Transient() else Transient(message = result.exceptionOrNull()?.message ?: "Failed to connect")
            }
        }
    }

    private fun hostLabel(url: String): String =
        url.substringAfter("://", "")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore(':')
            .takeIf { it.isNotBlank() }
            ?.removePrefix("www.")
            ?: url
}
