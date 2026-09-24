package com.sabreware.aide.core.domain.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Locks the MCP-tool → [com.sabreware.aide.core.domain.llm.AideTool.Function] mapping: name/description/schema
 * carry over, and the handler routes to the right server+tool and shapes Ok/Err into the standard
 * tool-result envelope (`result` vs `errorCode`+`message`).
 */
class McpToolFactoryTest {

    private class FakeClient(private val result: McpCallResult) : McpClient {
        var lastCall: Triple<String, String, JsonObject>? = null
        override suspend fun connect(config: McpServerConfig) = emptyList<McpToolDescriptor>()
        override suspend fun callTool(serverUrl: String, toolName: String, arguments: JsonObject): McpCallResult {
            lastCall = Triple(serverUrl, toolName, arguments)
            return result
        }
        override suspend fun disconnect(serverUrl: String) {}
        override suspend fun close() {}
    }

    private val descriptor = McpToolDescriptor(
        serverUrl = "https://mcp.test/mcp",
        name = "get_weather",
        description = "Get the weather",
        inputSchema = buildJsonObject { put("type", "object") },
    )

    @Test
    fun maps_name_description_schema_category() {
        val tool = descriptor.toAideTool(FakeClient(McpCallResult.Ok("x")))
        assertEquals("get_weather", tool.name)
        assertEquals("Get the weather", tool.description)
        assertEquals(MCP_TOOL_CATEGORY, tool.category)
        assertEquals(descriptor.inputSchema, tool.parametersSchema)
    }

    @Test
    fun handler_ok_returnsResult_andRoutesToServerTool() = runTest {
        val fake = FakeClient(McpCallResult.Ok("sunny, 22C"))
        val args = buildJsonObject { put("city", "NYC") }
        val out = descriptor.toAideTool(fake).handler(args)
        assertEquals("sunny, 22C", out["result"]?.jsonPrimitive?.content)
        assertEquals(Triple("https://mcp.test/mcp", "get_weather", args), fake.lastCall)
    }

    @Test
    fun handler_err_returnsErrorEnvelope() = runTest {
        val out = descriptor.toAideTool(FakeClient(McpCallResult.Err("boom"))).handler(buildJsonObject {})
        assertEquals(MCP_ERROR_CODE, out["errorCode"]?.jsonPrimitive?.content)
        assertEquals("boom", out["message"]?.jsonPrimitive?.content)
    }
}
