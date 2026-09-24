package com.sabreware.aide.core.domain.mcp

import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.Surface
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Category tag for tools sourced from MCP servers (vs built-ins). */
const val MCP_TOOL_CATEGORY = "mcp"

/** Error code an MCP tool envelope carries when the server reports a tool error. */
const val MCP_ERROR_CODE = "MCP_ERROR"

// Dispatcher cross-cutting props (added to every tool schema) — stripped before forwarding to the MCP
// server, which may reject unknown args under a strict input schema.
private val CROSS_CUTTING_KEYS = setOf("idempotency_key", "__trace_id")

/**
 * Maps a discovered [McpToolDescriptor] to a first-class [AideTool.Function] whose handler invokes the
 * tool on its MCP server via [caller] (the connection manager, which adds mid-session 401 refresh +
 * reconnect + single retry on top of the raw client). The model sees the tool's real name + JSON-Schema,
 * and the call flows through the standard `ToolDispatcher` like any built-in — so MCP tools work on every
 * provider (local LiteRT + remote OpenAI/Anthropic/Ollama), not just a prompt-injected on-device model.
 */
fun McpToolDescriptor.toAideTool(caller: McpToolCaller): AideTool.Function = AideTool.Function(
    name = name,
    description = description,
    parametersSchema = inputSchema,
    handler = { args ->
        val mcpArgs = JsonObject(args.filterKeys { it !in CROSS_CUTTING_KEYS })
        when (val r = caller.callTool(serverUrl = serverUrl, toolName = name, arguments = mcpArgs)) {
            is McpCallResult.Ok -> buildJsonObject { put("result", r.text) }
            is McpCallResult.Err -> buildJsonObject {
                put("errorCode", MCP_ERROR_CODE)
                put("message", r.message)
            }
        }
    },
    surfaces = setOf(Surface.CHAT),
    category = MCP_TOOL_CATEGORY,
    errorCodes = setOf(MCP_ERROR_CODE),
)
