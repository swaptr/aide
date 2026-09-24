package com.sabreware.aide.core.domain.connector

/** Terminal outcome of a connect attempt. */
sealed interface ConnectStep {
    data object Done : ConnectStep
    data class Failed(val reason: String) : ConnectStep
}

/**
 * Orchestrates connecting a catalog [Connector]:
 * - NONE/HEADER → builds an `McpServerConfig` and connects directly.
 * - OAUTH/UNKNOWN → runs discovery → client registration → PKCE, then invokes [openBrowser] with the
 *   authorization URL (the UI launches a Chrome Custom Tab) and awaits the redirect internally, exchanges
 *   the code for a token, stores it (encrypted), and connects with `Authorization: Bearer`.
 *
 * [openBrowser] is a plain `(String) -> Unit` so this port stays Android-free — :core:domain has no
 * Android dependency to leak one through (see docs/module-graph.md).
 */
interface ConnectCoordinator {
    suspend fun connect(
        connector: Connector,
        openBrowser: suspend (authUrl: String) -> Unit,
    ): ConnectStep

    /**
     * Connect a user-pasted MCP server URL that has no catalog entry (the "add server" form). Tries an
     * unauthenticated connect and, if the server demands OAuth (401 → RFC 9728 discovery), runs the same
     * sign-in flow as a catalog connector — so an OAuth-protected pasted URL can complete a sign-in instead
     * of just failing with a raw 401. Mirrors the UNKNOWN catalog auth path. [openBrowser] launches the
     * authorization page.
     */
    suspend fun connectUrl(
        serverUrl: String,
        openBrowser: suspend (authUrl: String) -> Unit,
    ): ConnectStep

    /** User dismissed the sign-in browser without finishing → resolve any in-flight OAuth as cancelled.
     *  No-op once the redirect has already arrived (token exchange underway). */
    fun cancelPendingSignIn()
}
