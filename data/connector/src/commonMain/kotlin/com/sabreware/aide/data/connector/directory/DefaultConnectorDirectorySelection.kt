package com.sabreware.aide.data.connector.directory

import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.prefs.peek
import com.sabreware.aide.core.domain.connector.ConnectorPrefs
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectory
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryId
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectorySelection
import com.sabreware.aide.core.domain.connector.setConnectorDirectoryEnabled
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Prefs-backed [ConnectorDirectorySelection]. The id universe is the multibound directory set (fixed at DI
 * time); the store keeps the *disabled* ids, so enabled = all − disabled and any directory the user never
 * turned off (including newly-shipped ones) is on. `distinctUntilChanged` shields the aggregate from the
 * shared DataStore re-emitting on unrelated preference writes.
 */
class DefaultConnectorDirectorySelection(
    private val prefs: PreferenceStore,
    directories: Set<@JvmSuppressWildcards ConnectorDirectory>,
) : ConnectorDirectorySelection {

    private val allIds: Set<ConnectorDirectoryId> = directories.map { it.descriptor.id }.toSet()

    override val enabledIds: Flow<Set<ConnectorDirectoryId>> =
        prefs.flow(ConnectorPrefs.DisabledDirectories).map(::enabled).distinctUntilChanged()

    override val enabledNow: Set<ConnectorDirectoryId> get() = enabled(prefs.peek(ConnectorPrefs.DisabledDirectories))

    private fun enabled(disabled: Set<String>) = allIds - disabled.map(::ConnectorDirectoryId).toSet()

    override suspend fun setEnabled(id: ConnectorDirectoryId, enabled: Boolean) =
        prefs.setConnectorDirectoryEnabled(id.value, enabled)
}
