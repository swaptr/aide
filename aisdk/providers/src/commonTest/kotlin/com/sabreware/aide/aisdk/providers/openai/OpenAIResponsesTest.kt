package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.bool
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The Responses API's own wire, both halves of it.
 *
 * `doGenerate` reads one JSON document and `doStream` reads an SSE stream of typed items; they are not
 * two encodings of one payload, and a port that implements only the second has no way to serve a caller
 * on a gateway that does not stream. Both are exercised here against the same turn so the two cannot
 * report different content for it.
 */
class OpenAIResponsesTest {

    private val encrypted = "gAAAAABlZW5jcnlwdGVkIHJlYXNvbmluZw"

    private fun model(server: TestServer) =
        OpenAIResponsesLanguageModel(modelId = "gpt-5.1", http = server.http(), generateId = { "src_1" })

    private fun chunks(vararg objects: String) = objects.map { "data: $it\n\n" }.toTypedArray()

    private val call = CallOptions(
        prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi")))),
        reasoning = ReasoningEffort.Medium,
    )

    @Test
    fun `a stateless reasoning call asks for the payload it will have to replay`() = runTest {
        val server = TestServer(TestServer.json("""{"id":"resp_1","output":[]}"""))
        model(server).doGenerate(
            call.copy(providerOptions = mapOf("openai" to buildJsonObject { put("store", false) })),
        )

        val body = server.request(0).bodyJson()
        assertEquals(false, body["store"].bool())
        // Without this the call succeeds and the reasoning comes back with nothing replayable in it,
        // which costs a full re-derivation on every subsequent round and reports no error at all.
        assertEquals(
            listOf("reasoning.encrypted_content"),
            body.arr("include")!!.map { it.string() },
        )
    }

    @Test
    fun `a stored call asks for nothing extra, because OpenAI is holding it`() = runTest {
        val server = TestServer(TestServer.json("""{"id":"resp_1","output":[]}"""))
        model(server).doGenerate(call)

        val body = server.request(0).bodyJson()
        assertNull(body["include"])
        assertNull(body["store"])
    }

    @Test
    fun `the reasoning model rules that are each a 400 are applied`() = runTest {
        val server = TestServer(TestServer.json("""{"id":"resp_1","output":[]}"""))
        val result = model(server).doGenerate(
            call.copy(
                prompt = listOf(ModelMessage.System("be terse")) + call.prompt,
                maxOutputTokens = 500,
                temperature = 0.7,
                seed = 3,
            ),
        )

        val body = server.request(0).bodyJson()
        // The Responses API renamed the field and rejects the old name outright.
        assertEquals("500", body["max_output_tokens"].string())
        assertNull(body["max_tokens"])
        // A reasoning model refuses samplers; sending one anyway is a 400, not a rounding of behaviour.
        assertNull(body["temperature"])
        // A reasoning model takes its instructions under `developer`; `system` is the non-reasoning role.
        assertEquals("developer", body.arr("input")!!.first().jsonObject["role"].string())
        assertTrue(result.warnings.isNotEmpty(), "a dropped parameter the caller set must be reported")
    }

    @Test
    fun `a completed response maps its reasoning, its call and its usage`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"id":"resp_1","model":"gpt-5.1","output":[
                    {"type":"reasoning","id":"rs_1","encrypted_content":"$encrypted",
                     "summary":[{"type":"summary_text","text":"Think."}]},
                    {"type":"function_call","id":"fc_1","call_id":"call_9","name":"lookup",
                     "arguments":"{\"q\":\"x\"}"}
                ],"usage":{"input_tokens":30,"output_tokens":48,
                 "input_tokens_details":{"cached_tokens":10},
                 "output_tokens_details":{"reasoning_tokens":40}}}""",
            ),
        )

        val result = model(server).doGenerate(
            call.copy(tools = listOf(Tool.Function("lookup", buildJsonObject { put("type", "object") }))),
        )

        val reasoning = assertIs<Content.Reasoning>(result.content[0])
        assertEquals("Think.", reasoning.text)
        assertEquals(
            encrypted,
            reasoning.providerMetadata?.get(OPENAI_PROVIDER_ID)?.get(OPENAI_ENCRYPTED_REASONING_KEY).string(),
        )
        assertEquals("rs_1", reasoning.providerMetadata?.get(OPENAI_PROVIDER_ID)?.get(OPENAI_ITEM_ID_KEY).string())

        val toolCall = assertIs<Content.ToolCall>(result.content[1])
        assertEquals("call_9", toolCall.toolCallId)

        // The Responses API reports nothing at all on a normal finish, so a pending call is the only
        // thing that distinguishes "answered" from "waiting on a tool".
        assertEquals(FinishReason.Unified.ToolCalls, result.finishReason.unified)
        assertEquals(10, result.usage.inputTokens.cacheRead)
        assertEquals(20, result.usage.inputTokens.noCache)
        assertEquals(40, result.usage.outputTokens.reasoning)
        assertEquals(8, result.usage.outputTokens.text)
    }

    @Test
    fun `a 200 carrying an error is a failure, not an empty turn`() = runTest {
        val server = TestServer(
            TestServer.json("""{"id":"resp_1","error":{"message":"quota exhausted","code":"insufficient_quota"}}"""),
        )
        val failure = assertFailsWith<APICallError> { model(server).doGenerate(call) }
        assertEquals("quota exhausted", failure.message)
    }

    @Test
    fun `the encrypted payload is picked off the item that closes the reasoning block`() = runTest {
        val server = TestServer(
            TestServer.sse(
                *chunks(
                    """{"type":"response.created","response":{"id":"resp_1","created_at":1770000000,"model":"gpt-5.1"}}""",
                    """{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"rs_1"}}""",
                    """{"type":"response.reasoning_summary_text.delta","item_id":"rs_1","summary_index":0,"delta":"A"}""",
                    """{"type":"response.reasoning_summary_part.added","item_id":"rs_1","summary_index":1}""",
                    """{"type":"response.reasoning_summary_text.delta","item_id":"rs_1","summary_index":1,"delta":"B"}""",
                    """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"rs_1","encrypted_content":"$encrypted"}}""",
                    """{"type":"response.completed","response":{"id":"resp_1","usage":{"input_tokens":5,"output_tokens":7}}}""",
                ),
            ),
        )

        val result = assembleGenerateResult(model(server).doStream(call).stream)
        val reasoning = result.content.filterIsInstance<Content.Reasoning>()

        // Two summary parts are two blocks. Keying them by the item id alone merges the model's own
        // account of two separate lines of thought into one.
        assertEquals(listOf("A", "B"), reasoning.map { it.text })
        assertTrue(
            reasoning.all {
                it.providerMetadata?.get(OPENAI_PROVIDER_ID)
                    ?.get(OPENAI_ENCRYPTED_REASONING_KEY).string() == encrypted
            },
            "every part of a reasoning item must carry the payload that makes the item replayable",
        )
    }

    @Test
    fun `an error frame after the 200 is reported rather than read as a short answer`() = runTest {
        val server = TestServer(
            TestServer.sse(
                *chunks(
                    """{"type":"response.created","response":{"id":"resp_1"}}""",
                    """{"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"msg_1","role":"assistant"}}""",
                    """{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"delta":"Part"}""",
                    """{"type":"error","error":{"message":"the model went away","code":"server_error"}}""",
                ),
            ),
        )

        val parts = model(server).doStream(call).stream.toList()
        val error = parts.filterIsInstance<StreamPart.Error>().single()
        assertEquals("the model went away", error.error.message)
        // A stream that stops without a terminal chunk still owes a Finish, or the caller reads a
        // truncated connection as a successful turn that used no tokens.
        assertTrue(parts.last() is StreamPart.Finish)
    }

    @Test
    fun `a provider-executed search reaches the stream through the same mapper the JSON path uses`() = runTest {
        val server = TestServer(
            TestServer.sse(
                *chunks(
                    """{"type":"response.created","response":{"id":"resp_1"}}""",
                    """{"type":"response.output_item.done","output_index":0,"item":{"type":"web_search_call","id":"ws_1","action":{"type":"search","query":"tuesday"}}}""",
                    """{"type":"response.completed","response":{"id":"resp_1"}}""",
                ),
            ),
        )

        val parts = model(server).doStream(
            call.copy(tools = listOf(Tool.ProviderDefined("openai.web_search", "web_search", buildJsonObject { }))),
        ).stream.toList()

        val toolCall = parts.filterIsInstance<StreamPart.ToolCallPart>().single().toolCall
        assertEquals(true, toolCall.providerExecuted)
        // A result with no call to attribute it to is a result the caller cannot render.
        assertEquals(toolCall.toolCallId, parts.filterIsInstance<StreamPart.ToolResultPart>().single().toolResult.toolCallId)
    }

    // --- context management, compaction, allowed tools ------------------------------------------

    @Test
    fun `contextManagement goes out snake-cased, element by element`() = runTest {
        val server = TestServer(TestServer.json("""{"id":"resp_1","output":[]}"""))
        model(server).doGenerate(
            call.copy(
                providerOptions = mapOf(
                    "openai" to buildJsonObject {
                        put(
                            "contextManagement",
                            kotlinx.serialization.json.buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "compaction")
                                        put("compactThreshold", 0.8)
                                    },
                                )
                            },
                        )
                    },
                ),
            ),
        )

        val body = server.request().bodyJson()
        assertTrue("contextManagement" !in body, body.toString())
        val entry = body["context_management"]!!.let { it as kotlinx.serialization.json.JsonArray }[0].jsonObject
        assertEquals("compaction", entry["type"].string())
        assertEquals(0.8, entry["compact_threshold"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `compactionTrigger appends an input item, not a body field`() = runTest {
        val server = TestServer(TestServer.json("""{"id":"resp_1","output":[]}"""))
        model(server).doGenerate(
            call.copy(providerOptions = mapOf("openai" to buildJsonObject { put("compactionTrigger", true) })),
        )

        val body = server.request().bodyJson()
        assertTrue("compactionTrigger" !in body && "compaction_trigger" !in body, body.toString())
        val items = body["input"]!!.let { it as kotlinx.serialization.json.JsonArray }
        assertEquals("compaction_trigger", items.last().jsonObject["type"].string())
    }

    @Test
    fun `allowedTools rewrites tool_choice with typed entries`() = runTest {
        val server = TestServer(TestServer.json("""{"id":"resp_1","output":[]}"""))
        model(server).doGenerate(
            call.copy(
                tools = listOf(
                    Tool.Function("lookup", buildJsonObject { }),
                    Tool.ProviderDefined(
                        name = "web_search", id = "openai.web_search",
                        args = kotlinx.serialization.json.JsonObject(emptyMap()),
                    ),
                ),
                providerOptions = mapOf(
                    "openai" to buildJsonObject {
                        put(
                            "allowedTools",
                            buildJsonObject {
                                put(
                                    "toolNames",
                                    kotlinx.serialization.json.buildJsonArray {
                                        add(kotlinx.serialization.json.JsonPrimitive("lookup"))
                                        add(kotlinx.serialization.json.JsonPrimitive("web_search"))
                                    },
                                )
                                put("mode", "required")
                            },
                        )
                    },
                ),
            ),
        )

        val body = server.request().bodyJson()
        assertTrue("allowedTools" !in body, body.toString())
        val choice = body["tool_choice"]!!.jsonObject
        assertEquals("allowed_tools", choice["type"].string())
        assertEquals("required", choice["mode"].string())
        val entries = choice["tools"]!!.let { it as kotlinx.serialization.json.JsonArray }.map { it.jsonObject }
        assertEquals("function", entries[0]["type"].string())
        assertEquals("lookup", entries[0]["name"].string())
        // A built-in tool is its bare type, with no name to carry.
        assertEquals("web_search", entries[1]["type"].string())
        assertNull(entries[1]["name"])
    }

    // --- the 2026-09 delta ---------------------------------------------------------------------------

    @Test
    fun `the ultrafast service tier passes through`() = runTest {
        val server = TestServer(TestServer.json("""{"id":"resp_1","output":[]}"""))
        val result = model(server).doGenerate(
            call.copy(providerOptions = mapOf("openai" to buildJsonObject { put("serviceTier", "ultrafast") })),
        )

        assertEquals("ultrafast", server.request(0).bodyJson()["service_tier"].string())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `propertyNames is removed from a response schema and the caller is told`() = runTest {
        val server = TestServer(TestServer.json("""{"id":"resp_1","output":[]}"""))
        val result = model(server).doGenerate(
            call.copy(
                responseFormat = ResponseFormat.Json(
                    schema = parseJsonObject(
                        """{"type":"object","properties":{"variables":{"type":"object",""" +
                            """"propertyNames":{"type":"string","pattern":"^[A-Z_]+$"},""" +
                            """"additionalProperties":{"type":"string"}}},"required":["variables"],"additionalProperties":false}""",
                    ),
                ),
            ),
        )

        assertEquals(
            parseJsonObject(
                """{"type":"json_schema","strict":true,"name":"response","schema":{"type":"object","properties":""" +
                    """{"variables":{"type":"object","additionalProperties":{"type":"string"}}},""" +
                    """"required":["variables"],"additionalProperties":false}}""",
            ),
            server.request(0).bodyJson().obj("text", "format"),
        )
        assertEquals(
            listOf<Warning>(
                Warning.Compatibility(
                    feature = "JSON Schema propertyNames",
                    details = "OpenAI does not support JSON Schema propertyNames. It was removed before sending " +
                        "the schema, so OpenAI will not enforce property-name constraints.",
                ),
            ),
            result.warnings,
        )
    }

    @Test
    fun `a caller can leave web search sources out of the include list`() = runTest {
        val server = TestServer(TestServer.json("""{"id":"resp_1","output":[]}"""))
        model(server).doGenerate(
            call.copy(
                tools = listOf(OpenAITools.webSearch()),
                providerOptions = mapOf("openai" to buildJsonObject { put("includeWebSearchSources", false) }),
            ),
        )

        assertNull(server.request(0).bodyJson()["include"])
    }

    @Test
    fun `a vendor that cannot serve the sources include is never asked for it`() = runTest {
        val server = TestServer(TestServer.json("""{"id":"resp_1","output":[]}"""))
        OpenAIResponsesLanguageModel(
            modelId = "gpt-5-nano",
            http = server.http(),
            quirks = ResponsesQuirks(supportsWebSearchSourcesInclude = false),
        ).doGenerate(call.copy(tools = listOf(OpenAITools.webSearch())))

        assertNull(server.request(0).bodyJson()["include"])
    }

    @Test
    fun `an apply_patch call is a tool call, and the turn finishes on tool-calls`() = runTest {
        val item = """{"type":"apply_patch_call","id":"ap_1","call_id":"call_ap","status":"completed",""" +
            """"operation":{"type":"create_file","path":"a.txt","diff":"+hi"}}"""
        val expectedInput = """{"callId":"call_ap","operation":{"type":"create_file","path":"a.txt","diff":"+hi"}}"""
        val tools = listOf(OpenAITools.applyPatch())

        val generate = TestServer(TestServer.json("""{"id":"resp_1","output":[$item]}"""))
        val result = model(generate).doGenerate(call.copy(tools = tools))
        val toolCall = assertIs<Content.ToolCall>(result.content.single())
        assertEquals("call_ap", toolCall.toolCallId)
        assertEquals("apply_patch", toolCall.toolName)
        assertEquals(expectedInput, toolCall.input)
        // Reported as `stop` before this: the model was waiting on the runtime to apply the patch, and
        // a caller reading `stop` ended the round with it unapplied.
        assertEquals(FinishReason(FinishReason.Unified.ToolCalls, raw = null), result.finishReason)

        val stream = TestServer(
            TestServer.sse(
                *chunks(
                    """{"type":"response.created","response":{"id":"resp_1"}}""",
                    """{"type":"response.output_item.done","output_index":0,"item":$item}""",
                    """{"type":"response.completed","response":{"id":"resp_1","usage":{"input_tokens":5,"output_tokens":7}}}""",
                ),
            ),
        )
        val parts = model(stream).doStream(call.copy(tools = tools)).stream.toList()
        assertEquals(expectedInput, parts.filterIsInstance<StreamPart.ToolCallPart>().single().toolCall.input)
        assertEquals(
            FinishReason.Unified.ToolCalls,
            parts.filterIsInstance<StreamPart.Finish>().single().finishReason.unified,
        )
    }

    @Test
    fun `a late response failed event keeps its usage, unknown counters included`() = runTest {
        val usage = """{"input_tokens":12,"input_tokens_details":{"cached_tokens":2,"future_input_detail":{"tokens":5}},""" +
            """"output_tokens":8,"output_tokens_details":{"reasoning_tokens":3,"future_output_detail":["preserved"]},""" +
            """"total_tokens":20,"future_usage_field":{"value":true}}"""
        val server = TestServer(
            TestServer.sse(
                *chunks(
                    """{"type":"response.created","sequence_number":0,"response":{"id":"resp_failed_with_reason","created_at":1741269019,"model":"gpt-4o-2024-07-18","service_tier":null}}""",
                    """{"type":"response.output_item.added","sequence_number":1,"output_index":0,"item":{"id":"msg_failed_with_reason","type":"message"}}""",
                    """{"type":"error","sequence_number":2,"error":{"type":"server_error","code":"server_error","message":"response failed","param":null}}""",
                    """{"type":"response.failed","sequence_number":3,"response":{"error":{"code":"server_error","message":"response failed"},"incomplete_details":{"reason":"max_output_tokens"},"usage":$usage,"service_tier":null}}""",
                ),
            ),
        )

        val finish = model(server).doStream(call).stream.toList().filterIsInstance<StreamPart.Finish>().single()

        assertEquals(
            Usage(
                inputTokens = Usage.InputTokens(total = 12, noCache = 10, cacheRead = 2, cacheWrite = null),
                outputTokens = Usage.OutputTokens(total = 8, text = 5, reasoning = 3),
                // The whole object, counters this port has never heard of included: normalization
                // reads four fields and must not cost the caller the rest.
                raw = parseJsonObject(usage),
            ),
            finish.usage,
        )
    }

    @Test
    fun `a null usage is no usage, not a frame that failed to decode`() = runTest {
        val stream = TestServer(
            TestServer.sse(
                *chunks(
                    """{"type":"response.created","response":{"id":"resp_1"}}""",
                    """{"type":"response.failed","sequence_number":1,"response":{"error":{"code":"server_error","message":"boom"},"usage":null}}""",
                ),
            ),
        )
        val finish = model(stream).doStream(call).stream.toList().filterIsInstance<StreamPart.Finish>().single()
        assertEquals(Usage(), finish.usage)

        val generate = TestServer(TestServer.json("""{"id":"resp_1","output":[],"usage":null}"""))
        assertEquals(Usage(), model(generate).doGenerate(call).usage)
    }
}
