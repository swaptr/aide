package com.sabreware.aide.data.connector.mcp

import com.sabreware.aide.core.common.startup.DeferredBootstrap
import com.sabreware.aide.core.domain.mcp.McpServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Reconnects persisted + enabled MCP servers on app launch, so their tools are available in chat without the
 * user re-opening settings. One-shot; started from the shared `startSharedBootstraps()` runner on EVERY
 * platform (Android `AideApp`, desktop `main()`, …). Near-expiry OAuth tokens are refreshed inside
 * [McpConnectionManager.connect] (every connect path, not just this bootstrap), so this is a thin loop.
 * Failures are swallowed — a server that won't connect simply shows as disconnected in the manager UI.
 */
class McpReconnectBootstrap(
    private val repo: McpServerRepository,
    private val manager: McpConnectionManager,
    private val scope: CoroutineScope,
) : DeferredBootstrap {
    private var started = false

    override suspend fun start() {
        // Idempotent: the trigger lives in AppShell's first-frame effect, which re-runs when the
        // activity is recreated — reconnecting again would duplicate MCP sessions.
        if (started) return
        started = true
        scope.launch {
            repo.currentServers()
                .filter { it.enabled }
                .forEach { config -> runCatching { manager.connect(config) } }
        }
    }
}
