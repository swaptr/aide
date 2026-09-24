package com.sabreware.aide.data.connector

import com.sabreware.aide.core.common.startup.DeferredBootstrap
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectory
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryKind
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectorySelection
import com.sabreware.aide.core.domain.util.AideLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Refreshes every enabled network ([ConnectorDirectoryKind.REGISTRY]) directory on launch (background,
 * one-shot). Each [ConnectorDirectory.refresh] is TTL-gated and never throws; the reactive aggregate catalog
 * re-merges automatically when a refreshed directory re-emits, so there is no manual catalog poke. Bundled
 * directories need no refresh. Started from `AideApp`. Replaces the former single-registry `RemoteConnectorBootstrap`.
 */
class ConnectorDirectoryBootstrap(
    private val scope: CoroutineScope,
    private val directories: Set<@JvmSuppressWildcards ConnectorDirectory>,
    private val selection: ConnectorDirectorySelection,
) : DeferredBootstrap {
    private var started = false

    override suspend fun start() {
        // Idempotent: triggered from AppShell's first-frame effect, which re-runs on activity recreation.
        if (started) return
        started = true
        scope.launch {
            val enabled = selection.enabledIds.first()
            directories
                .filter { it.descriptor.kind == ConnectorDirectoryKind.REGISTRY && it.descriptor.id in enabled }
                .forEach { dir ->
                    launch {
                        runCatching { dir.refresh() }
                            .onFailure { AideLog.w(TAG, "refresh ${dir.descriptor.id.value} failed: ${it.message}") }
                    }
                }
        }
    }

    private companion object { const val TAG = "ConnectorDirBootstrap" }
}
