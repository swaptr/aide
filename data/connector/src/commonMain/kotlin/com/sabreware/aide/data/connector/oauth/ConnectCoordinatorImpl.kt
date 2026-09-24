package com.sabreware.aide.data.connector.oauth

import com.sabreware.aide.core.domain.connector.ConnectCoordinator
import com.sabreware.aide.core.domain.connector.ConnectStep
import com.sabreware.aide.core.domain.connector.Connector
import com.sabreware.aide.core.domain.connector.ConnectorAuthType
import com.sabreware.aide.core.domain.connector.ConnectorCategory
import com.sabreware.aide.core.domain.connector.oauth.ClientRegistrar
import com.sabreware.aide.core.domain.connector.oauth.OAuthDiscovery
import com.sabreware.aide.core.domain.connector.oauth.OAuthTokenClient
import com.sabreware.aide.core.domain.connector.oauth.RedirectReceiverFactory
import com.sabreware.aide.core.domain.mcp.McpAuth
import com.sabreware.aide.core.domain.mcp.McpConnections
import com.sabreware.aide.core.domain.mcp.McpServerConfig
import com.sabreware.aide.core.domain.mcp.McpServerRepository
import com.sabreware.aide.core.domain.util.AideLog
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.datetime.Clock

/**
 * Orchestrates connecting a catalog connector. NONE connects directly; OAUTH runs the full
 * discovery → register → PKCE → browser → token → bearer-connect flow (per MCP auth spec 2025-11-25):
 * `resource` (RFC 8707) on every request, PKCE S256 mandatory, `state` validated. [openBrowser] launches
 * the Custom Tab (UI), then we await the redirect via the strategy-selected receiver.
 */
class ConnectCoordinatorImpl(
    private val discovery: OAuthDiscovery,
    private val registrar: ClientRegistrar,
    private val tokenClient: OAuthTokenClient,
    private val redirectFactory: RedirectReceiverFactory,
    private val manager: McpConnections,
    private val repo: McpServerRepository,
) : ConnectCoordinator {

    private companion object { const val TAG = "ConnOAuth" }

    override suspend fun connect(connector: Connector, openBrowser: suspend (authUrl: String) -> Unit): ConnectStep =
        when (connector.authType) {
            ConnectorAuthType.NONE -> connectWith(connector, McpAuth.None)
            ConnectorAuthType.HEADER ->
                ConnectStep.Failed("Add this connector with a custom server (it needs an API key / header).")
            ConnectorAuthType.OAUTH -> oauth(connector, openBrowser)
            ConnectorAuthType.UNKNOWN -> {
                val anon = connectWith(connector, McpAuth.None)
                if (anon is ConnectStep.Done) anon else oauth(connector, openBrowser)
            }
        }

    override suspend fun connectUrl(serverUrl: String, openBrowser: suspend (authUrl: String) -> Unit): ConnectStep =
        // A pasted URL has no catalog auth hint → treat it as UNKNOWN: connect anonymously, and if that 401s
        // fall through to the same OAuth discovery + sign-in flow a catalog connector uses.
        connect(syntheticConnector(serverUrl), openBrowser)

    /** A minimal, catalog-less [Connector] for an arbitrary pasted URL — UNKNOWN so connect() probes auth. */
    private fun syntheticConnector(serverUrl: String): Connector = Connector(
        id = serverUrl,
        name = serverUrl,
        description = "",
        category = ConnectorCategory.OTHER,
        serverUrl = serverUrl,
        authType = ConnectorAuthType.UNKNOWN,
    )

    private suspend fun oauth(connector: Connector, openBrowser: suspend (String) -> Unit): ConnectStep {
        val resource = canonical(connector.serverUrl)
        // RFC 9728 §5.1: probe the server for its 401 WWW-Authenticate challenge and hand the
        // `resource_metadata` hint to discovery (spec-compliant), falling back to well-known probing.
        val challenge = discovery.resourceMetadataChallenge(connector.serverUrl)
        val prm = discovery.protectedResourceMetadata(connector.serverUrl, challenge)
            ?: return ConnectStep.Failed("Couldn't find this connector's sign-in service.")
        // RFC 9728 confused-deputy guard: the PRM must govern the server we intend to authenticate against,
        // so a (possibly registry-supplied) server can't point sign-in at a resource it doesn't own.
        prm.resource?.takeIf { it.isNotBlank() }?.let { advertised ->
            if (!sameOrigin(advertised, connector.serverUrl)) {
                return ConnectStep.Failed("This connector's sign-in metadata points at a different server; not connecting.")
            }
        }
        val asUrl = prm.authorizationServers.firstOrNull()
            ?: return ConnectStep.Failed("Connector didn't advertise an authorization server.")
        val asMeta = discovery.authServerMetadata(asUrl)
            ?: return ConnectStep.Failed("Couldn't read the authorization server settings.")
        if (asMeta.codeChallengeMethodsSupported.none { it.equals("S256", ignoreCase = true) }) {
            return ConnectStep.Failed("This connector's sign-in doesn't support PKCE; can't connect securely.")
        }

        val receiver = redirectFactory.create()
        try {
            val clientId = registrar.clientId(asMeta, receiver.redirectUri)
                ?: return ConnectStep.Failed("This connector needs manual app registration (no dynamic registration).")
            val pkce = Pkce.generate()
            val state = Pkce.state()
            val scope = (prm.scopesSupported + asMeta.scopesSupported).distinct().joinToString(" ").ifBlank { null }

            AideLog.i(TAG, "discovery+registration ok, redirectUri=${receiver.redirectUri}; calling openBrowser")
            openBrowser(authUrl(asMeta.authorizationEndpoint, clientId, receiver.redirectUri, pkce.challenge, state, scope, resource))

            AideLog.i(TAG, "openBrowser returned; awaiting redirect (this blocks until callback or timeout)")
            val result = receiver.await()
            AideLog.i(TAG, "redirect received: error=${result.error} hasCode=${result.code != null}")
            if (result.error == PendingOAuthFlow.CANCELLED) return ConnectStep.Failed("Sign-in cancelled.")
            if (result.error != null) return ConnectStep.Failed("Sign-in failed: ${result.error}")
            if (result.state != state) return ConnectStep.Failed("Sign-in could not be verified (state mismatch).")
            val code = result.code ?: return ConnectStep.Failed("Sign-in returned no authorization code.")

            val token = runCatching {
                tokenClient.exchangeCode(asMeta, clientId, code, pkce.verifier, receiver.redirectUri, resource)
            }.getOrElse { return ConnectStep.Failed("Token exchange failed: ${it.message}") }

            val oauth = McpAuth.OAuth(
                accessToken = token.accessToken,
                refreshToken = token.refreshToken,
                expiresAtEpochSec = token.expiresIn?.let { nowSec() + it },
                tokenEndpoint = asMeta.tokenEndpoint,
                clientId = clientId,
                scope = token.scope ?: scope,
                resource = resource,
            )
            return connectWith(connector, oauth)
        } finally {
            receiver.cancel()
        }
    }

    override fun cancelPendingSignIn() = PendingOAuthFlow.cancelIfPending()

    private suspend fun connectWith(connector: Connector, auth: McpAuth): ConnectStep {
        val config = McpServerConfig(url = connector.serverUrl, auth = auth, enabled = true)
        val result = manager.connect(config)
        return if (result.isSuccess) {
            repo.save(config)
            ConnectStep.Done
        } else {
            ConnectStep.Failed(result.exceptionOrNull()?.message ?: "Failed to connect")
        }
    }

    private fun authUrl(
        endpoint: String,
        clientId: String,
        redirectUri: String,
        challenge: String,
        state: String,
        scope: String?,
        resource: String,
    ): String {
        val params = buildList {
            add("response_type" to "code")
            add("client_id" to clientId)
            add("redirect_uri" to redirectUri)
            add("code_challenge" to challenge)
            add("code_challenge_method" to "S256")
            add("state" to state)
            if (scope != null) add("scope" to scope)
            add("resource" to resource)
        }
        val query = params.joinToString("&") { (k, v) -> "${k.encodeURLParameter()}=${v.encodeURLParameter()}" }
        return endpoint + (if ('?' in endpoint) "&" else "?") + query
    }

    // RFC 8707 canonical resource URI (shared with the refresh path): lowercase scheme/host, drop default
    // port + fragment + trailing slash.
    private fun canonical(url: String): String = canonicalResource(url)

    private fun sameOrigin(a: String, b: String): Boolean {
        val ua = runCatching { Url(a) }.getOrNull() ?: return false
        val ub = runCatching { Url(b) }.getOrNull() ?: return false
        // Ktor's Url.port already resolves to the protocol default (443/80) when unspecified.
        return ua.protocol.name.equals(ub.protocol.name, ignoreCase = true) &&
            ua.host.equals(ub.host, ignoreCase = true) &&
            ua.port == ub.port
    }

    private fun nowSec(): Long = Clock.System.now().epochSeconds
}
