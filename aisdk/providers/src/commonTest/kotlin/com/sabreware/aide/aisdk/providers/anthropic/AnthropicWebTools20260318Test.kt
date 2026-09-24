package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The 2026-03-18 web tools as the reference now defines them — `use_cache` and `response_inclusion` —
 * and the code-execution tool they provision implicitly.
 *
 * Fixtures are the inline snapshots of `anthropic-prepare-tools.test.ts` ("should correctly prepare
 * web_search_20260318 without a beta header", "... web_fetch_20260318 ...") and the response body of
 * `anthropic-language-model.test.ts` "should use web_search_20260318 with response inclusion and map
 * custom tool names", rendered as the streaming events this provider reads since it never calls the
 * non-streaming endpoint.
 */
class AnthropicWebTools20260318Test {

    private val endTurn =
        "event: message_delta\n" +
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}," +
            "\"usage\":{\"output_tokens\":4}}\n\n"

    private val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi"))))

    private suspend fun sendTools(vararg tools: Tool): Pair<JsonObject, String?> {
        val server = TestServer(TestServer.sse(endTurn))
        AnthropicLanguageModel(modelId = "claude-opus-5", http = server.http())
            .doGenerate(CallOptions(prompt = prompt, tools = tools.toList()))
        val call = server.request()
        return call.bodyJson().arr("tools")!!.single().jsonObject to call.header("anthropic-beta")
    }

    @Test
    fun `web_search_20260318 spells response inclusion and needs no beta`() = runTest {
        val (tool, beta) = sendTools(
            AnthropicTools.webSearch_20260318(
                buildJsonObject {
                    put("maxUses", 10)
                    put("allowedDomains", buildJsonArray { add("google.com") })
                    putJsonObject("userLocation") { put("type", "approximate"); put("city", "New York") }
                    put("responseInclusion", "excluded")
                },
            ),
        )

        assertEquals(parseJsonObject(AnthropicToolsFixtures.WEB_SEARCH_20260318), tool)
        assertNull(beta)
    }

    @Test
    fun `web_fetch_20260318 spells use_cache and response inclusion and needs no beta`() = runTest {
        val (tool, beta) = sendTools(
            AnthropicTools.webFetch_20260318(
                buildJsonObject {
                    put("maxUses", 10)
                    put("allowedDomains", buildJsonArray { add("google.com") })
                    putJsonObject("citations") { put("enabled", true) }
                    put("maxContentTokens", 1000)
                    put("useCache", false)
                    put("responseInclusion", "excluded")
                },
            ),
        )

        assertEquals(parseJsonObject(AnthropicToolsFixtures.WEB_FETCH_20260318), tool)
        assertNull(beta)
    }

    @Test
    fun `a code execution call the search provisions is dynamic and the search keeps its custom name`() = runTest {
        val server = TestServer(
            TestServer.sse(
                "event: content_block_start\n" +
                    "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"server_tool_use\"," +
                    "\"id\":\"srvtoolu_code\",\"name\":\"code_execution\",\"input\":{\"code\":\"search(\\\"AI SDK\\\")\"}," +
                    "\"caller\":{\"type\":\"direct\"}}}\n\n",
                "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
                "event: content_block_start\n" +
                    "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"server_tool_use\"," +
                    "\"id\":\"srvtoolu_search\",\"name\":\"web_search\",\"input\":{\"query\":\"AI SDK\"}," +
                    "\"caller\":{\"type\":\"code_execution_20260120\",\"tool_id\":\"srvtoolu_code\"}}}\n\n",
                "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":1}\n\n",
                endTurn,
            ),
        )
        val model = AnthropicLanguageModel(modelId = "claude-opus-5", http = server.http())

        val result = model.doGenerate(
            CallOptions(
                prompt = prompt,
                tools = listOf(
                    Tool.ProviderDefined(
                        name = "research",
                        id = "anthropic.web_search_20260318",
                        args = buildJsonObject { put("maxUses", 3); put("responseInclusion", "excluded") },
                    ),
                ),
            ),
        )

        val call = server.request()
        assertEquals(
            listOf(parseJsonObject("""{"type":"web_search_20260318","name":"web_search","max_uses":3,"response_inclusion":"excluded"}""")),
            call.bodyJson().arr("tools"),
        )
        assertNull(call.header("anthropic-beta"))

        val calls = result.content.filterIsInstance<Content.ToolCall>()
        assertEquals(listOf("code_execution", "research"), calls.map { it.toolName })
        // No code-execution tool was offered, so the call the search provisioned must bypass validation.
        assertTrue(calls[0].dynamic, calls[0].toString())
        assertFalse(calls[1].dynamic, calls[1].toString())
        assertTrue(calls.all { it.providerExecuted })
    }

    @Test
    fun `a declared code execution tool makes the mark unnecessary`() = runTest {
        val server = TestServer(
            TestServer.sse(
                "event: content_block_start\n" +
                    "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"server_tool_use\"," +
                    "\"id\":\"srvtoolu_code\",\"name\":\"code_execution\",\"input\":{\"code\":\"1+1\"}}}\n\n",
                "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
                endTurn,
            ),
        )
        val model = AnthropicLanguageModel(modelId = "claude-opus-5", http = server.http())

        val result = assembleGenerateResult(
            model.doStream(
                CallOptions(
                    prompt = prompt,
                    tools = listOf(AnthropicTools.webFetch_20260318(), AnthropicTools.codeExecution_20260120()),
                ),
            ).stream,
        )

        assertFalse(result.content.filterIsInstance<Content.ToolCall>().single().dynamic)
    }
}
