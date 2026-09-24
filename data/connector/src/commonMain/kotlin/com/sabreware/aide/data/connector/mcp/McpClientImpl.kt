package com.sabreware.aide.data.connector.mcp

import com.sabreware.aide.core.domain.mcp.McpAuth
import com.sabreware.aide.core.domain.mcp.McpCallResult
import com.sabreware.aide.core.domain.mcp.McpClient
import com.sabreware.aide.core.domain.mcp.McpServerConfig
import com.sabreware.aide.core.domain.mcp.McpToolDescriptor
import com.sabreware.aide.data.net.KtorClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP client over the official kotlin-sdk + our Ktor OkHttp engine (StreamableHTTP transport needs SSE).
 * Mirrors Google AI Edge Gallery's connect → `listTools` → `callTool` pattern (`McpManagerViewModel` /
 * `AgentTools`), holding one connected SDK [Client] per server URL. SDK surface is isolated to this file.
 */
class McpClientImpl(
    private val ioDispatcher: CoroutineDispatcher,
) : McpClient {

    private val httpClient: HttpClient = KtorClientFactory.mcp()
    private val clients = mutableMapOf<String, Client>()
    private val mutex = Mutex()

    /**
     * Connects OUTSIDE the lock, under a deadline, then publishes. The lock used to be held across the network
     * handshake with no timeout, so one hanging server blocked every other connect and every tool lookup; a
     * client whose connect threw was never closed. The previous client for the URL keeps serving until its
     * replacement is ready, then is closed.
     */
    override suspend fun connect(config: McpServerConfig): List<McpToolDescriptor> =
        withContext(ioDispatcher) {
            val client = Client(clientInfo = Implementation(name = CLIENT_NAME, version = CLIENT_VERSION))
            val tools = try {
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    client.connect(transportFor(config))
                    client.listTools().tools.orEmpty().map { tool ->
                        McpToolDescriptor(
                            serverUrl = config.url,
                            name = tool.name,
                            description = tool.description ?: "",
                            inputSchema = schemaOf(tool),
                        )
                    }
                } ?: throw IllegalStateException("MCP server did not answer within ${CONNECT_TIMEOUT_MS / 1_000} s")
            } catch (t: Throwable) {
                withContext(NonCancellable) { runCatching { client.close() } }
                throw t
            }
            val replaced = mutex.withLock { clients.put(config.url, client) }
            replaced?.let { runCatching { it.close() } }
            tools
        }

    // The requestBuilder lambda is the sole auth-injection seam (same as Gallery). OAuth sends the bearer
    // token; refresh-before-connect is handled upstream (ConnectCoordinator / McpReconnectBootstrap) so the SDK
    // transport stays OAuth-unaware.
    private fun transportFor(config: McpServerConfig): StreamableHttpClientTransport = when (val auth = config.auth) {
        is McpAuth.Header -> StreamableHttpClientTransport(
            client = httpClient,
            url = config.url,
            requestBuilder = { headers.append(auth.name, auth.value) },
        )
        is McpAuth.OAuth -> StreamableHttpClientTransport(
            client = httpClient,
            url = config.url,
            requestBuilder = { headers.append("Authorization", "Bearer ${auth.accessToken}") },
        )
        McpAuth.None -> StreamableHttpClientTransport(client = httpClient, url = config.url)
    }

    override suspend fun callTool(
        serverUrl: String,
        toolName: String,
        arguments: JsonObject,
    ): McpCallResult = withContext(ioDispatcher) {
        val client = mutex.withLock { clients[serverUrl] }
            ?: return@withContext McpCallResult.Err("MCP server not connected: $serverUrl")
        runCatching {
            val result = client.callTool(name = toolName, arguments = arguments)
            val text = result.content.orEmpty()
                .filterIsInstance<TextContent>()
                .joinToString("\n") { it.text }
            if (result.isError == true) McpCallResult.Err(text.ifBlank { "tool error" }) else McpCallResult.Ok(text)
        }.getOrElse {
            // A Stop is not a tool error: let cancellation through.
            if (it is CancellationException) throw it
            // The StreamableHTTP transport throws StreamableHttpError(code, message) on a non-2xx POST; a 401
            // means the (OAuth) token expired/was revoked mid-session — flag it so the manager can refresh +
            // reconnect + retry once (see McpConnectionManager.callTool).
            McpCallResult.Err(
                message = it.message ?: it::class.simpleName ?: "tool error",
                authFailure = it is StreamableHttpError && it.code == 401,
            )
        }
    }

    override suspend fun disconnect(serverUrl: String) {
        mutex.withLock { clients.remove(serverUrl) }?.let { runCatching { it.close() } }
    }

    override suspend fun close() {
        mutex.withLock {
            clients.values.forEach { runCatching { it.close() } }
            clients.clear()
        }
        runCatching { httpClient.close() }
    }

    // MCP Tool.inputSchema → a self-contained JSON-Schema object (type/properties/required). Mirrors
    // Gallery's manual schema assembly (avoids restricted SDK visibility on the schema object).
    private fun schemaOf(tool: Tool): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", tool.inputSchema.properties ?: JsonObject(emptyMap()))
        put("required", JsonArray(tool.inputSchema.required.orEmpty().map { JsonPrimitive(it) }))
    }

    private companion object {
        const val CLIENT_NAME = "aide"
        const val CLIENT_VERSION = "1.0"
        const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
