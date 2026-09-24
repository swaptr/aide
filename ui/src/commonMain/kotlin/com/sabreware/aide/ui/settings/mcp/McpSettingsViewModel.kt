package com.sabreware.aide.ui.settings.mcp

import com.sabreware.aide.core.designsystem.state.stateInUi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabreware.aide.core.designsystem.state.UiState as Async
import com.sabreware.aide.core.designsystem.state.asUiState
import com.sabreware.aide.core.designsystem.state.map
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.prefs.selectState
import com.sabreware.aide.core.domain.connector.ConnectCoordinator
import com.sabreware.aide.core.domain.connector.ConnectorPrefs
import com.sabreware.aide.core.domain.connector.ConnectStep
import com.sabreware.aide.core.domain.mcp.McpAuth
import com.sabreware.aide.core.domain.mcp.McpConnections
import com.sabreware.aide.core.domain.mcp.McpServerConfig
import com.sabreware.aide.core.domain.mcp.McpServerRepository
import com.sabreware.aide.core.domain.mcp.McpServerStatus
import com.sabreware.aide.core.domain.prefs.OAuthRedirectStrategy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the MCP management UI. Combines the persisted server configs ([McpServerRepository]) with the
 * live connection status ([McpConnectionManager]) so each row shows whether it's connected + its tools.
 * Add/remove/toggle write the repo AND the manager; the launch bootstrap reconnects on cold start.
 */
class McpSettingsViewModel(
    private val repo: McpServerRepository,
    private val manager: McpConnections,
    private val coordinator: ConnectCoordinator,
    private val prefs: PreferenceStore,
) : ViewModel() {

    /** How a connector's sign-in returns to the app — the Sign-in sheet on the Connectors page. */
    val redirectStrategy: StateFlow<OAuthRedirectStrategy> = prefs.selectState(viewModelScope) { it[ConnectorPrefs.OAuthRedirect] }

    fun setRedirectStrategy(strategy: OAuthRedirectStrategy) {
        viewModelScope.launch { prefs.set(ConnectorPrefs.OAuthRedirect, strategy) }
    }

    data class ServerRow(
        val url: String,
        val enabled: Boolean,
        val connected: Boolean,
        val toolNames: List<String>,
    )

    data class UiState(
        // Async so consumers can render a real loading state instead of flashing "no connectors"
        // while the encrypted store's first read is in flight.
        val servers: Async<List<ServerRow>> = Async.Loading,
        val busy: Boolean = false,
        val error: String? = null,
    )

    private data class Transient(val busy: Boolean = false, val error: String? = null)

    private val transient = MutableStateFlow(Transient())

    private val _launchBrowser = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** OAuth authorization URLs for a pasted OAuth-protected server; [AddConnectorPage] opens them in the
     *  system browser (Custom Tab on Android). Buffered so the async discovery→emit never drops the URL. */
    val launchBrowser: SharedFlow<String> = _launchBrowser.asSharedFlow()

    /** Add-server page returned to the foreground. If a sign-in is mid-flight and the user dismissed the
     *  browser, cancel it (no-op once the redirect has already arrived). */
    fun onReturnedToForeground() {
        if (transient.value.busy) coordinator.cancelPendingSignIn()
    }

    private fun rowsOf(configs: List<McpServerConfig>, statuses: List<McpServerStatus>): List<ServerRow> {
        val byUrl = statuses.associateBy { it.url }
        return configs.map { cfg ->
            val status = byUrl[cfg.url]
            ServerRow(
                url = cfg.url,
                enabled = cfg.enabled,
                connected = status != null,
                toolNames = status?.toolNames.orEmpty(),
            )
        }
    }

    val uiState: StateFlow<UiState> =
        combine(repo.servers.filterNotNull().asUiState(), manager.status, transient) { configs, statuses, t ->
            UiState(
                servers = configs.map { list -> rowsOf(list, statuses) },
                busy = t.busy,
                error = t.error,
            )
            // The state class already carries an error; use it, rather than letting the throw cancel the
            // sharing coroutine and leave the screen on Loading forever.
        }.stateInUi(
            viewModelScope,
            // Seeded from the repo's cache: a re-opened Connectors sheet composes with its rows on the
            // first frame; only the session's very first open shows the skeleton.
            UiState(
                servers = repo.servers.value?.let { Async.Ready(rowsOf(it, manager.status.value)) }
                    ?: Async.Loading,
            ),
        ) { error ->
            UiState(error = error.message ?: "Could not load connectors.")
        }

    fun clearError() = transient.update { it.copy(error = null) }

    /** Connect first; persist only on success so a bad URL/key doesn't leave a dead config behind. */
    fun addServer(url: String, headerName: String, headerValue: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        val header = headerName.trim().ifBlank { null }
        // Set busy synchronously (before the launch) so the form's pop-on-success effect, which keys off
        // busy true→false, never fires before the connect has actually started.
        transient.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            if (header != null) {
                // Static request-header auth → connect + persist directly (an OAuth server can't be reached
                // with a fixed header anyway).
                val config = McpServerConfig(url = trimmed, auth = McpAuth.Header(header, headerValue.trim()), enabled = true)
                val result = manager.connect(config)
                if (result.isSuccess) {
                    repo.save(config)
                    transient.update { Transient() }
                } else {
                    transient.update { it.copy(busy = false, error = result.exceptionOrNull()?.message ?: "Failed to connect") }
                }
            } else {
                // No header → route through the coordinator: it connects anonymously and, if the server
                // demands OAuth (401 → RFC 9728 discovery), runs the sign-in flow (emitting the auth URL on
                // [launchBrowser]) and persists the config on success — so a pasted OAuth URL can sign in.
                val step = coordinator.connectUrl(trimmed) { u -> _launchBrowser.emit(u) }
                transient.update {
                    when (step) {
                        is ConnectStep.Done -> Transient()
                        is ConnectStep.Failed -> it.copy(busy = false, error = step.reason)
                    }
                }
            }
        }
    }

    fun removeServer(url: String) {
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

    /** Retry a server that failed to connect (or hasn't yet). */
    fun reconnect(url: String) {
        transient.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val config = repo.currentServers().find { it.url == url }
                ?: run { transient.update { it.copy(busy = false) }; return@launch }
            val result = manager.connect(config)
            transient.update {
                if (result.isSuccess) Transient()
                else it.copy(busy = false, error = result.exceptionOrNull()?.message ?: "Failed to connect")
            }
        }
    }
}
