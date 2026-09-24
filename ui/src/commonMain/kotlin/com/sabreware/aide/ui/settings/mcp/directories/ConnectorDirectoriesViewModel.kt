package com.sabreware.aide.ui.settings.mcp.directories

import com.sabreware.aide.core.designsystem.state.stateInUi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectory
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryDescriptor
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryId
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectorySelection
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Drives the connector-sources screen: every available [ConnectorDirectory] descriptor with its enabled state,
 * ordered by merge priority. Toggling persists via [ConnectorDirectorySelection]; the aggregate catalog (and so
 * the browse + search screens) react live.
 */
class ConnectorDirectoriesViewModel(
    directories: Set<@JvmSuppressWildcards ConnectorDirectory>,
    private val selection: ConnectorDirectorySelection,
) : ViewModel() {

    data class DirectoryRow(val descriptor: ConnectorDirectoryDescriptor, val enabled: Boolean)

    private val descriptors: List<ConnectorDirectoryDescriptor> =
        directories.map { it.descriptor }.sortedWith(compareBy({ it.priority }, { it.title }))

    val rows: StateFlow<List<DirectoryRow>> =
        selection.enabledIds
            .map(::rowsFor)
            // Seeded from the prefs snapshot, so a turned-off directory is off on the first frame; an
            // unreadable preference lands on the default ("everything on") rather than freezing the rows.
            .stateInUi(viewModelScope, rowsFor(selection.enabledNow)) { descriptors.map { DirectoryRow(it, true) } }

    private fun rowsFor(enabled: Set<ConnectorDirectoryId>) = descriptors.map { DirectoryRow(it, it.id in enabled) }

    fun setEnabled(id: ConnectorDirectoryId, enabled: Boolean) {
        viewModelScope.launch { selection.setEnabled(id, enabled) }
    }
}
