package com.sabreware.aide.data.connector.mcp

import com.sabreware.aide.core.domain.connector.oauth.AuthorizationServerMetadata
import com.sabreware.aide.core.domain.connector.oauth.OAuthTokenClient
import com.sabreware.aide.core.domain.connector.oauth.TokenResponse
import com.sabreware.aide.core.domain.mcp.McpAuth
import com.sabreware.aide.core.domain.mcp.McpCallResult
import com.sabreware.aide.core.domain.mcp.McpClient
import com.sabreware.aide.core.domain.mcp.McpServerConfig
import com.sabreware.aide.core.domain.mcp.McpServerRepository
import com.sabreware.aide.core.domain.mcp.McpToolDescriptor
import io.ktor.utils.io.errors.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Locks the live MCP wiring: connect surfaces a server's discovered tools into [McpConnectionManager.currentTools]
 * (the snapshot folded into every chat session), enable/disable + disconnect remove them, a failed connect
 * stays a failure with no tools, and the status flow tracks connected servers.
 */
class McpConnectionManagerTest {

    private class FakeClient(private val byUrl: Map<String, List<String>>) : McpClient {
        override suspend fun connect(config: McpServerConfig): List<McpToolDescriptor> =
            byUrl[config.url].orEmpty().map { name ->
                McpToolDescriptor(config.url, name, "desc", buildJsonObject { put("type", "object") })
            }
        override suspend fun callTool(serverUrl: String, toolName: String, arguments: JsonObject) = McpCallResult.Ok("x")
        override suspend fun disconnect(serverUrl: String) {}
        override suspend fun close() {}
    }

    // These tests use McpAuth.None servers, so the manager's refresh-on-connect short-circuits and never
    // touches the token client or repo — inert fakes suffice.
    private object NoTokenClient : OAuthTokenClient {
        override suspend fun exchangeCode(
            asMetadata: AuthorizationServerMetadata, clientId: String, code: String,
            codeVerifier: String, redirectUri: String, resource: String,
        ): TokenResponse = error("unused")
        override suspend fun refresh(
            tokenEndpoint: String, clientId: String, refreshToken: String, resource: String, scope: String?,
        ): TokenResponse = error("unused")
    }

    private object NoRepo : McpServerRepository {
        override val servers = MutableStateFlow<List<McpServerConfig>?>(null)
        override suspend fun save(config: McpServerConfig) {}
        override suspend fun remove(url: String) {}
        override suspend fun setEnabled(url: String, enabled: Boolean) {}
    }

    private fun mgr(client: McpClient) = McpConnectionManager(client, NoTokenClient, NoRepo)

    @Test
    fun connect_exposesDiscoveredTools() = runTest {
        val mgr = mgr(FakeClient(mapOf("u1" to listOf("a", "b"))))
        mgr.connect(McpServerConfig("u1"))
        assertEquals(setOf("a", "b"), mgr.currentTools().map { it.name }.toSet())
    }

    @Test
    fun disabledServer_contributesNoTools_reEnableRestores() = runTest {
        val mgr = mgr(FakeClient(mapOf("u1" to listOf("a"))))
        mgr.connect(McpServerConfig("u1"))
        mgr.setEnabled("u1", false)
        assertTrue(mgr.currentTools().isEmpty())
        mgr.setEnabled("u1", true)
        assertEquals(listOf("a"), mgr.currentTools().map { it.name })
    }

    @Test
    fun disconnect_removesTools() = runTest {
        val mgr = mgr(FakeClient(mapOf("u1" to listOf("a"))))
        mgr.connect(McpServerConfig("u1"))
        mgr.disconnect("u1")
        assertTrue(mgr.currentTools().isEmpty())
    }

    @Test
    fun connect_failure_isFailure_andNoTools() = runTest {
        val failing = object : McpClient {
            override suspend fun connect(config: McpServerConfig): List<McpToolDescriptor> = throw IOException("boom")
            override suspend fun callTool(serverUrl: String, toolName: String, arguments: JsonObject) = McpCallResult.Ok("")
            override suspend fun disconnect(serverUrl: String) {}
            override suspend fun close() {}
        }
        val mgr = mgr(failing)
        assertTrue(mgr.connect(McpServerConfig("u1")).isFailure)
        assertTrue(mgr.currentTools().isEmpty())
    }

    @Test
    fun status_reflectsConnectedServers() = runTest {
        val mgr = mgr(FakeClient(mapOf("u1" to listOf("a", "b"))))
        mgr.connect(McpServerConfig("u1"))
        val status = mgr.status.value.single()
        assertEquals("u1", status.url)
        assertTrue(status.enabled)
        assertEquals(listOf("a", "b"), status.toolNames)
    }

    // A tool call whose token expired mid-session (client reports authFailure) transparently refreshes the
    // OAuth token, reconnects with it, and retries the call ONCE — persisting the rotated token.
    @Test
    fun callTool_authFailure_refreshesReconnectsAndRetriesOnce() = runTest {
        var connects = 0
        var calls = 0
        val client = object : McpClient {
            override suspend fun connect(config: McpServerConfig): List<McpToolDescriptor> {
                connects++
                return listOf(McpToolDescriptor(config.url, "t", "d", buildJsonObject { put("type", "object") }))
            }
            override suspend fun callTool(serverUrl: String, toolName: String, arguments: JsonObject): McpCallResult {
                calls++
                return if (calls == 1) McpCallResult.Err("HTTP 401", authFailure = true) else McpCallResult.Ok("ok")
            }
            override suspend fun disconnect(serverUrl: String) {}
            override suspend fun close() {}
        }
        var refreshes = 0
        val tokens = object : OAuthTokenClient {
            override suspend fun exchangeCode(
                asMetadata: AuthorizationServerMetadata, clientId: String, code: String,
                codeVerifier: String, redirectUri: String, resource: String,
            ): TokenResponse = error("unused")
            override suspend fun refresh(
                tokenEndpoint: String, clientId: String, refreshToken: String, resource: String, scope: String?,
            ): TokenResponse {
                refreshes++
                return TokenResponse(accessToken = "fresh", refreshToken = "r2", expiresIn = 3600)
            }
        }
        val saved = mutableListOf<McpServerConfig>()
        val repo = object : McpServerRepository {
            override val servers = MutableStateFlow<List<McpServerConfig>?>(null)
            override suspend fun save(config: McpServerConfig) { saved += config }
            override suspend fun remove(url: String) {}
            override suspend fun setEnabled(url: String, enabled: Boolean) {}
        }
        val mgr = McpConnectionManager(client, tokens, repo)
        val oauth = McpAuth.OAuth(
            accessToken = "stale", refreshToken = "r1", expiresAtEpochSec = null,
            tokenEndpoint = "https://as/token", clientId = "cid", resource = "https://s",
        )
        mgr.connect(McpServerConfig("https://s", auth = oauth))

        val result = mgr.callTool("https://s", "t", buildJsonObject {})

        assertEquals(McpCallResult.Ok("ok"), result)
        assertEquals(1, refreshes)                                                   // refreshed exactly once
        assertEquals(2, connects)                                                    // initial connect + reconnect
        assertEquals(2, calls)                                                       // original call + one retry
        assertEquals("fresh", (saved.single().auth as McpAuth.OAuth).accessToken)    // rotation persisted
    }

    // A plain (non-auth) tool error is surfaced as-is — never reconnected or retried.
    @Test
    fun callTool_nonAuthError_notRetried() = runTest {
        var calls = 0
        val client = countingClient { calls++; McpCallResult.Err("boom") }
        val mgr = mgr(client)
        mgr.connect(McpServerConfig("u1"))
        val result = mgr.callTool("u1", "t", buildJsonObject {})
        assertTrue(result is McpCallResult.Err)
        assertEquals(1, calls)
    }

    // An auth failure on a non-OAuth server can't be refreshed → the error stands, no retry.
    @Test
    fun callTool_authFailure_onNonOAuthServer_notRetried() = runTest {
        var calls = 0
        val client = countingClient { calls++; McpCallResult.Err("HTTP 401", authFailure = true) }
        val mgr = mgr(client)
        mgr.connect(McpServerConfig("u1")) // McpAuth.None → nothing to refresh
        val result = mgr.callTool("u1", "t", buildJsonObject {})
        assertTrue(result is McpCallResult.Err)
        assertEquals(1, calls)
    }

    private fun countingClient(onCall: () -> McpCallResult) = object : McpClient {
        override suspend fun connect(config: McpServerConfig) =
            listOf(McpToolDescriptor(config.url, "t", "d", buildJsonObject { put("type", "object") }))
        override suspend fun callTool(serverUrl: String, toolName: String, arguments: JsonObject) = onCall()
        override suspend fun disconnect(serverUrl: String) {}
        override suspend fun close() {}
    }
}
