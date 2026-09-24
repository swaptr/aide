package com.sabreware.aide.aisdk.providers.perplexity

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.openai.OPENAI_RESPONSE_ID_KEY
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.assertNoWarningAbout
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.bool
import com.sabreware.aide.aisdk.providers.testing.double
import com.sabreware.aide.aisdk.providers.testing.int
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The Agent API, against Perplexity's documentation of 2026-09-02 (see [PerplexityAgentFixtures] for
 * what is doc-derived rather than recorded).
 *
 * The request tests pin the departures from OpenAI's Responses wire that the docs name — `preset`,
 * `response_format`, `reasoning.effort`'s vocabulary, the penalties, `type: "message"` on every input
 * item — and the response tests pin what the shared mappers would drop: the vendor's own output items,
 * the sources they carry, the priced usage, and the `thought_signature` on a function call.
 */
class PerplexityAgentTest {

    private val user = ModelMessage.User(listOf(UserPart.Text("Explain the Transformer.")))
    private val call = CallOptions(prompt = listOf(user))

    private fun provider(server: TestServer) = PerplexityProvider(HttpClient(server.engine()), apiKey = "test-api-key")

    private fun model(server: TestServer, id: String = "openai/gpt-5.6-sol") = provider(server).languageModel(id)

    private fun json(body: String) = TestServer(TestServer.json(body))

    private fun sse(events: List<String>) =
        TestServer(TestServer.sse(events.joinToString("") { "data: $it\n\n" }))

    private val orderSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("order_id", buildJsonObject { put("type", "string") }) })
    }

    // --- the request ------------------------------------------------------------------------------------

    @Test
    fun `the request is the Agent API's shape and reaches its canonical endpoint`() = runTest {
        val server = json(PerplexityAgentFixtures.RESPONSE)
        val model = model(server)

        model.doGenerate(
            call.copy(
                prompt = listOf(ModelMessage.System("Cite your sources."), user),
                maxOutputTokens = 800,
                temperature = 0.3,
                frequencyPenalty = 0.5,
                presencePenalty = 0.1,
                tools = listOf(
                    PerplexityTools.webSearch(
                        buildJsonObject {
                            put("searchContextSize", "medium")
                            put(
                                "filters",
                                buildJsonObject {
                                    put("searchDomainFilter", buildJsonArray { add("arxiv.org") })
                                    put("searchRecencyFilter", "month")
                                },
                            )
                            put("userLocation", buildJsonObject { put("country", "US") })
                        },
                    ),
                    Tool.Function("get_order_status", orderSchema, description = "Look up an order."),
                ),
                providerOptions = mapOf(
                    "perplexity" to buildJsonObject {
                        put("max_steps", 5)
                        put("instructions", "Be brief.")
                    },
                ),
            ),
        )

        assertEquals("perplexity", model.provider)
        val request = server.request()
        assertEquals("https://api.perplexity.ai/v1/agent", request.url)
        request.assertHeader("Authorization", "Bearer test-api-key")
        // No `include`, no `text`, no `reasoning` block, no `store`: none is a field this API documents
        // unless the caller asked for it.
        request.assertBodyKeys(
            "model", "input", "max_output_tokens", "temperature", "frequency_penalty", "presence_penalty",
            "tools", "max_steps", "instructions",
        )
        val body = request.bodyJson()
        assertEquals("openai/gpt-5.6-sol", body["model"].string())
        assertEquals(5, body["max_steps"].int())
        assertEquals("Be brief.", body["instructions"].string())
        assertEquals(0.5, body["frequency_penalty"].double())

        val input = body.arr("input")!!.map { it.jsonObject }
        assertEquals(listOf("message", "message"), input.map { it["type"].string() })
        assertEquals("system", input[0]["role"].string())
        assertEquals("Cite your sources.", input[0]["content"].string())
        assertEquals("user", input[1]["role"].string())
        assertEquals("input_text", input[1].arr("content")!!.single().jsonObject["type"].string())

        val tools = body.arr("tools")!!.map { it.jsonObject }
        assertEquals(
            parseJsonObject(
                """{"type":"web_search","search_context_size":"medium","filters":{"search_domain_filter":["arxiv.org"],"search_recency_filter":"month"},"user_location":{"country":"US"}}""",
            ),
            tools[0],
        )
        assertEquals("function", tools[1]["type"].string())
        assertEquals("get_order_status", tools[1]["name"].string())
        assertEquals(orderSchema, tools[1].obj("parameters"))
    }

    @Test
    fun `a preset name goes out as preset in place of model, and beside a model id as the documented override`() = runTest {
        val server = json(PerplexityAgentFixtures.RESPONSE)

        model(server, PerplexityPresets.LOW).doGenerate(call)
        val presetOnly = server.request(0).bodyJson()
        assertEquals("low", presetOnly["preset"].string())
        assertNull(presetOnly["model"], "\"preset\" is required if model is not provided; a preset is not a model")

        model(server).doGenerate(
            call.copy(providerOptions = mapOf("perplexity" to buildJsonObject { put("preset", "low") })),
        )
        val overridden = server.request(1).bodyJson()
        // "Any field you pass alongside the preset overrides that default" — a model beside a preset is
        // the documented way to keep the preset's tools and step budget on a different model.
        assertEquals("low", overridden["preset"].string())
        assertEquals("openai/gpt-5.6-sol", overridden["model"].string())
    }

    @Test
    fun `the neutral effort is spelled in the Agent API's vocabulary, which has xhigh and no none`() = runTest {
        val server = json(PerplexityAgentFixtures.RESPONSE)
        val model = model(server)

        val deep = model.doGenerate(call.copy(reasoning = ReasoningEffort.XHigh))
        assertEquals("xhigh", server.request(0).bodyJson().obj("reasoning")?.get("effort").string())
        assertTrue(deep.warnings.isEmpty())

        val none = model.doGenerate(call.copy(reasoning = ReasoningEffort.None))
        server.request(1).assertBodyMissing("reasoning")
        none.warnings.assertUnsupported("reasoningEffort")

        // A verbatim `reasoning` object from the caller's options is the caller's business.
        model.doGenerate(
            call.copy(
                reasoning = ReasoningEffort.Low,
                providerOptions = mapOf("perplexity" to buildJsonObject { put("reasoning", buildJsonObject { put("effort", "max") }) }),
            ),
        )
        assertEquals("max", server.request(2).bodyJson().obj("reasoning")?.get("effort").string())
    }

    @Test
    fun `structured output is response_format, the shape this API documents`() = runTest {
        val server = json(PerplexityAgentFixtures.RESPONSE)

        model(server).doGenerate(
            call.copy(responseFormat = ResponseFormat.Json(schema = orderSchema, name = "order", description = "An order.")),
        )

        val body = server.request().bodyJson()
        assertNull(body["text"], "`text.format` is OpenAI's spelling; the Agent API has no `text` field")
        val format = body.obj("response_format")!!
        assertEquals("json_schema", format["type"].string())
        assertEquals("order", format.obj("json_schema")?.get("name").string())
        assertEquals("An order.", format.obj("json_schema")?.get("description").string())
        assertEquals(true, format.obj("json_schema")?.get("strict").bool())
        assertEquals(orderSchema, format.obj("json_schema", "schema"))
    }

    @Test
    fun `a tool this API does not serve is refused with a warning, never sent under a type it never heard of`() = runTest {
        val server = json(PerplexityAgentFixtures.RESPONSE)

        val result = model(server).doGenerate(
            call.copy(tools = listOf(Tool.ProviderDefined(name = "shell", id = "openai.local_shell", args = buildJsonObject { }))),
        )

        assertTrue(result.warnings.any { it is Warning.Unsupported && it.feature == "providerTool:shell" })
        server.request().assertBodyMissing("tools")
    }

    @Test
    fun `a streamed request says so`() = runTest {
        val server = sse(PerplexityAgentFixtures.stream)

        model(server).doStream(call).stream.toList()

        assertEquals(true, server.request().bodyJson()["stream"].bool())
    }

    // --- the response -----------------------------------------------------------------------------------

    @Test
    fun `the documented response maps the answer, every source once, the raw items and the priced usage`() = runTest {
        val server = json(PerplexityAgentFixtures.RESPONSE)

        val result = model(server).doGenerate(call)

        assertEquals(
            listOf("Custom", "Url", "Url", "Custom", "Text"),
            result.content.map { it::class.simpleName },
        )
        val fixture = parseJsonObject(PerplexityAgentFixtures.RESPONSE)
        val searchItem = assertIs<Content.Custom>(result.content[0])
        assertEquals("perplexity.search_results", searchItem.kind)
        assertEquals(fixture.arr("output")!![0].jsonObject, searchItem.providerMetadata?.get("perplexity"))

        val sources = result.content.filterIsInstance<Content.Source.Url>()
        // arxiv is cited three times — the search, the fetch and the annotation — and is one source.
        assertEquals(listOf("https://arxiv.org/abs/1706.03762", "https://example.com/transformers"), sources.map { it.url })
        assertEquals("Attention Is All You Need", sources[0].title)
        val sourceMetadata = sources[0].providerMetadata?.get("perplexity")!!
        assertEquals("search_results", sourceMetadata["type"].string())
        assertEquals("transformer architecture attention", sourceMetadata.arr("queries")!!.single().string())
        assertEquals("2017-06-12", sourceMetadata.obj("result")?.get("date").string())
        assertEquals("perplexity.fetch_url_results", assertIs<Content.Custom>(result.content[3]).kind)

        val text = assertIs<Content.Text>(result.content[4])
        assertEquals("The Transformer relies entirely on attention.", text.text)
        assertEquals("msg_01", text.providerMetadata?.get("perplexity")?.get("itemId").string())

        assertEquals(FinishReason.Unified.Stop, result.finishReason.unified)
        assertEquals(150, result.usage.inputTokens.total)
        assertEquals(50, result.usage.inputTokens.cacheRead)
        assertEquals(0, result.usage.inputTokens.cacheWrite)
        assertEquals(100, result.usage.inputTokens.noCache)
        assertEquals(200, result.usage.outputTokens.total)
        assertEquals(350, result.usage.raw?.get("total_tokens").int())
        // The whole block, not a projection: the wire grows fields faster than any reader of it (`d4a22b0`).
        assertEquals(fixture.obj("usage"), result.usage.raw)
        val metadata = result.providerMetadata?.get("perplexity")!!
        assertEquals("resp_01", metadata[OPENAI_RESPONSE_ID_KEY].string())
        assertEquals(0.01706, metadata.obj("cost")?.get("total_cost").double())
        assertEquals("USD", metadata.obj("cost")?.get("currency").string())
        assertEquals(0.0025, metadata.obj("cost")?.get("tool_calls_cost").double())
        assertEquals("resp_01", result.response?.metadata?.id)
        assertEquals("openai/gpt-5.6-sol", result.response?.metadata?.modelId)
    }

    @Test
    fun `a function call carries its thought signature out, and back on the next turn`() = runTest {
        val server = TestServer(
            TestServer.json(PerplexityAgentFixtures.FUNCTION_CALL),
            TestServer.json(PerplexityAgentFixtures.RESPONSE),
        )
        val model = model(server, "google/gemini-3-flash-preview")
        val tools = listOf(Tool.Function("get_order_status", orderSchema))

        val first = model.doGenerate(call.copy(tools = tools))

        val toolCall = assertIs<Content.ToolCall>(first.content.single())
        assertEquals("call_Ku9yfMSIZWrJBGm2wqCaFF0G", toolCall.toolCallId)
        assertEquals("get_order_status", toolCall.toolName)
        assertEquals("""{"order_id":"ORD-10042"}""", toolCall.input)
        assertEquals(FinishReason.Unified.ToolCalls, first.finishReason.unified)
        val metadata = toolCall.providerMetadata?.get("perplexity")!!
        assertEquals("fc_a181bc3f-7a54-40b6-a85e-a50a0a6fac92", metadata["itemId"].string())
        assertEquals("CkYBVKhc7uZ0", metadata[PERPLEXITY_THOUGHT_SIGNATURE_KEY].string())

        model.doGenerate(
            call.copy(
                tools = tools,
                prompt = listOf(
                    user,
                    ModelMessage.Assistant(
                        listOf(
                            AssistantPart.ToolCall(toolCall.toolCallId, toolCall.toolName, toolCall.input, providerOptions = toolCall.providerMetadata),
                        ),
                    ),
                    ModelMessage.Tool(
                        listOf(ToolPart.Result(toolCall.toolCallId, toolCall.toolName, ToolOutput.Text("""{"status":"in_transit"}"""))),
                    ),
                ),
            ),
        )

        val input = server.request(1).bodyJson().arr("input")!!.map { it.jsonObject }
        assertEquals(listOf("message", "function_call", "function_call_output"), input.map { it["type"].string() })
        // The signature the model issued rides back with the call it belongs to — the failure DESIGN.md
        // names as the reason this library exists, on one more wire that fronts Gemini.
        assertEquals("CkYBVKhc7uZ0", input[1]["thought_signature"].string())
        assertEquals("call_Ku9yfMSIZWrJBGm2wqCaFF0G", input[1]["call_id"].string())
        assertEquals("call_Ku9yfMSIZWrJBGm2wqCaFF0G", input[2]["call_id"].string())
        assertEquals("""{"status":"in_transit"}""", input[2]["output"].string())
    }

    @Test
    fun `replay keeps what the API takes back and drops its own records without a word`() = runTest {
        val server = json(PerplexityAgentFixtures.RESPONSE)
        val rawItem = parseJsonObject(PerplexityAgentFixtures.RESPONSE).arr("output")!![0].jsonObject

        val result = model(server).doGenerate(
            call.copy(
                prompt = listOf(
                    user,
                    ModelMessage.Assistant(
                        listOf(
                            AssistantPart.Reasoning("Searching.", providerOptions = mapOf("perplexity" to buildJsonObject { put("type", "response.reasoning.started") })),
                            AssistantPart.Custom("perplexity.search_results", providerOptions = mapOf("perplexity" to rawItem)),
                            AssistantPart.ToolCall("srch_1", "web_search", "{}", providerExecuted = true),
                            AssistantPart.ToolResult("srch_1", "web_search", ToolOutput.Json(rawItem)),
                            AssistantPart.Text("The answer.", providerOptions = mapOf("perplexity" to buildJsonObject { put("itemId", "msg_01") })),
                        ),
                    ),
                    ModelMessage.User(listOf(UserPart.Text("And why?"))),
                ),
            ),
        )

        val body = server.request().bodyJson()
        val input = body.arr("input")!!.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), input.map { it["role"].string() })
        assertEquals("output_text", input[1].arr("content")!!.single().jsonObject["type"].string())
        assertEquals("The answer.", input[1].arr("content")!!.single().jsonObject["text"].string())
        // Neither an `item_reference` to something Perplexity never said it stores, nor a warning
        // about reasoning that was never replayable here.
        assertFalse(server.request().bodyText.contains("item_reference"))
        result.warnings.assertNoWarningAbout("reasoning")
        assertTrue(result.warnings.isEmpty(), "got: ${result.warnings}")
    }

    @Test
    fun `the documented stream narrates the research, then the answer, then the priced usage`() = runTest {
        val server = sse(PerplexityAgentFixtures.stream)

        val parts = model(server).doStream(call).stream.toList()

        assertEquals(
            listOf(
                "StreamStart", "ResponseMetadataPart", "ResponseMetadataPart",
                "ReasoningStart", "ReasoningDelta", "ReasoningEnd",
                "ReasoningStart", "ReasoningDelta", "ReasoningEnd",
                "SourcePart", "CustomPart", "SourcePart",
                "TextStart", "TextDelta", "TextDelta", "TextEnd", "Finish",
            ),
            parts.map { it::class.simpleName },
        )
        assertEquals("resp_02", assertIs<StreamPart.ResponseMetadataPart>(parts[1]).metadata.id)
        assertEquals(
            listOf("Looking this up.", "Searching the web."),
            parts.filterIsInstance<StreamPart.ReasoningDelta>().map { it.delta },
        )
        val queries = assertIs<StreamPart.ReasoningEnd>(parts[8]).providerMetadata?.get("perplexity")!!
        assertEquals("response.reasoning.search_queries", queries["type"].string())
        assertEquals("transformer architecture", queries.arr("queries")!!.single().string())
        // The first source arrives with the reasoning event; the output item repeats it and adds one.
        val sources = parts.filterIsInstance<StreamPart.SourcePart>().map { it.source }.filterIsInstance<Content.Source.Url>()
        assertEquals(listOf("https://arxiv.org/abs/1706.03762", "https://example.com/transformers"), sources.map { it.url })
        assertEquals("response.reasoning.search_results", sources[0].providerMetadata?.get("perplexity")?.get("type").string())
        assertEquals("perplexity.search_results", assertIs<StreamPart.CustomPart>(parts[10]).custom.kind)
        assertEquals(
            "The Transformer relies on attention.",
            parts.filterIsInstance<StreamPart.TextDelta>().joinToString("") { it.delta },
        )
        val finish = assertIs<StreamPart.Finish>(parts.last())
        assertEquals(FinishReason.Unified.Stop, finish.finishReason.unified)
        assertEquals(150, finish.usage.inputTokens.total)
        assertEquals(40, finish.usage.outputTokens.total)
        assertEquals(190, finish.usage.raw?.get("total_tokens").int())
        val completed = parseJsonObject(PerplexityAgentFixtures.stream.first { "response.completed" in it })
        assertEquals(completed.obj("response", "usage"), finish.usage.raw)
        val metadata = finish.providerMetadata?.get("perplexity")!!
        assertEquals("resp_02", metadata[OPENAI_RESPONSE_ID_KEY].string())
        assertEquals(0.003, metadata.obj("cost")?.get("total_cost").double())
    }

    @Test
    fun `a failed stream reports the top-level error and finishes as one`() = runTest {
        val server = sse(PerplexityAgentFixtures.failedStream)

        val parts = model(server).doStream(call).stream.toList()

        assertEquals(listOf("StreamStart", "ResponseMetadataPart", "Error", "Finish"), parts.map { it::class.simpleName })
        assertEquals("Model overloaded", assertIs<APICallError>(assertIs<StreamPart.Error>(parts[2]).error).message)
        assertEquals(FinishReason(FinishReason.Unified.Error, "error"), assertIs<StreamPart.Finish>(parts[3]).finishReason)
    }

    // --- the tools and the presets ----------------------------------------------------------------------

    @Test
    fun `every Agent API tool is provider-executed and named as the docs name it`() {
        assertEquals(
            mapOf(
                "perplexity.web_search" to "web_search",
                "perplexity.fetch_url" to "fetch_url",
                "perplexity.finance_search" to "finance_search",
                "perplexity.people_search" to "people_search",
                "perplexity.sandbox" to "sandbox",
                "perplexity.mcp" to "mcp",
                "perplexity.connector" to "connector",
            ),
            perplexityProviderToolNames,
        )
        PerplexityTools.all.forEach { factory ->
            assertTrue(factory().providerExecuted, "${factory.id} runs inside Perplexity's loop")
            assertEquals(JsonObject(emptyMap()), factory().args)
        }
    }

    @Test
    fun `tool arguments reach the wire in the documented spellings, headers untouched, unknown keys verbatim`() {
        val mcp = PerplexityTools.mcp(
            buildJsonObject {
                put("serverLabel", "docs")
                put("serverUrl", "https://mcp.example.com")
                put("deferLoading", true)
                put("headers", buildJsonObject { put("X-Custom-Header", "v") })
                put("someFutureKnob", 3)
            },
        )

        val body = perplexityToolBody(mcp.id, mcp.name, mcp.args)

        assertEquals(
            parseJsonObject(
                """{"type":"mcp","server_label":"docs","server_url":"https://mcp.example.com","defer_loading":true,"headers":{"X-Custom-Header":"v"},"someFutureKnob":3}""",
            ),
            body,
        )
        val fetch = PerplexityTools.fetchUrl(buildJsonObject { put("max_urls", 3) })
        assertEquals(3, perplexityToolBody(fetch.id, fetch.name, fetch.args)["max_urls"].int())
    }

    @Test
    fun `the Sonar mapping is a helper the caller invokes, never a rewrite`() = runTest {
        assertEquals("fast", PerplexityPresets.forSonarModel("sonar"))
        assertEquals("low", PerplexityPresets.forSonarModel("sonar-pro"))
        assertEquals("medium", PerplexityPresets.forSonarModel("sonar-reasoning-pro"))
        assertEquals("high", PerplexityPresets.forSonarModel("sonar-deep-research"))
        assertNull(PerplexityPresets.forSonarModel("sonar-reasoning"))
        assertTrue(PerplexityPresets.isPreset("xhigh"))
        assertFalse(PerplexityPresets.isPreset("openai/gpt-5.6-sol"))

        // A retiring id goes out as written: the choice of what replaces it is the caller's.
        val server = json(PerplexityAgentFixtures.RESPONSE)
        model(server, "sonar-pro").doGenerate(call)
        assertEquals("sonar-pro", server.request().bodyJson()["model"].string())
    }
}
