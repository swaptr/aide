package com.sabreware.aide.core.domain.connector.directory

import kotlinx.coroutines.flow.Flow

/**
 * Which connector directories the user has enabled. Backed by user preferences; consumed by the aggregate
 * catalog (to merge only enabled sources) and the launch bootstrap (to refresh only enabled registries).
 *
 * New built-in directories are **enabled by default**: the store keeps the *disabled* id set, so any directory
 * the user has never explicitly turned off is on.
 */
interface ConnectorDirectorySelection {
    val enabledIds: Flow<Set<ConnectorDirectoryId>>

    /** [enabledIds] as of the last prefs read, synchronously — for painting a first frame only. */
    val enabledNow: Set<ConnectorDirectoryId>
    suspend fun setEnabled(id: ConnectorDirectoryId, enabled: Boolean)
}
