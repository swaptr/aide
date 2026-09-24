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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    override suspend fun connect(config: McpServerConfig): List<McpToolDescriptor> =
        withContext(ioDispatcher) {
            mutex.withLock {
                clients.remove(config.url)?.let { runCatching { it.close() } }
                val client = Client(clientInfo = Implementation(name = CLIENT_NAME, version = CLIENT_VERSION))
                // The requestBuilder lambda is the sole auth-injection seam (same as Gallery). OAuth
                // sends the bearer token; refresh-before-connect is handled upstream (ConnectCoordinator /
                // McpReconnectBootstrap) so the SDK transport stays OAuth-unaware.
                val transport = when (val auth = config.auth) {
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
                client.connect(transport)
                val tools = client.listTools().tools.orEmpty().map { tool ->
                    McpToolDescriptor(
                        serverUrl = config.url,
                        name = tool.name,
                        description = tool.description ?: "",
                        inputSchema = schemaOf(tool),
                    )
                }
                clients[config.url] = client
                tools
            }
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
    }
}
