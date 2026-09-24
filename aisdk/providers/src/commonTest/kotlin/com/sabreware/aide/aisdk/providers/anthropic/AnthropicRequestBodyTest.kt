package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.assertCompatibility
import com.sabreware.aide.aisdk.providers.testing.assertNoWarningAbout
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.int
import com.sabreware.aide.aisdk.providers.testing.string
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What Anthropic actually receives.
 *
 * The two existing signature suites assert only on the response half, so every request-shaping rule this
 * provider enforces was unverified — which is how three guaranteed 400s (samplers alongside thinking, an
 * empty text block for unsigned reasoning, a `max_tokens` above the model's ceiling) shipped green. Each
 * case here asserts the SERIALIZED body: `ProviderJson` sets `encodeDefaults = false`, so a field left at
 * a Kotlin default is absent from the wire and invisible to any assertion made on the Kotlin object.
 */
class AnthropicRequestBodyTest {

    private val stream = TestServer.sse(
        "event: message_delta\n" +
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}," +
            "\"usage\":{\"output_tokens\":12}}\n\n",
    )

    private suspend fun send(
        modelId: String,
        options: CallOptions,
    ): Pair<JsonObject, List<com.sabreware.aide.aisdk.Warning>> {
        val server = TestServer(stream)
        val model = AnthropicLanguageModel(modelId = modelId, http = server.http())
        val parts = model.doStream(options).stream.toList()
        val warnings = parts.filterIsInstance<com.sabreware.aide.aisdk.StreamPart.StreamStart>()
            .flatMap { it.warnings }
        return server.request().bodyJson() to warnings
    }

    private fun user(text: String = "hi") =
        listOf(ModelMessage.User(listOf(UserPart.Text(text))))

    // --- samplers under thinking ----------------------------------------------------------------

    @Test
    fun `thinking drops the three samplers Anthropic rejects, and names each one`() = runTest {
        val (body, warnings) = send(
            "claude-sonnet-4-5",
            CallOptions(
                prompt = user(),
                reasoning = ReasoningEffort.Medium,
                temperature = 0.7,
                topP = 0.9,
                topK = 40,
            ),
        )

        assertTrue("temperature" !in body, "temperature must not survive thinking: $body")
        assertTrue("top_p" !in body, "top_p must not survive thinking: $body")
        assertTrue("top_k" !in body, "top_k must not survive thinking: $body")
        listOf("temperature", "topP", "topK").forEach {
            warnings.assertUnsupported(it, "$it is not supported when thinking is enabled")
        }
    }

    @Test
    fun `without thinking the samplers go out untouched`() = runTest {
        val (body, warnings) = send(
            "claude-sonnet-4-5",
            CallOptions(prompt = user(), reasoning = ReasoningEffort.None, temperature = 0.7, topK = 40),
        )

        assertEquals(0.7, body["temperature"].string()?.toDouble())
        assertEquals(40, body["top_k"].int())
        warnings.assertNoWarningAbout("temperature")
        warnings.assertNoWarningAbout("topK")
    }

    @Test
    fun `the 4_7 families reject samplers whether or not they are thinking`() = runTest {
        val (body, warnings) = send(
            "claude-opus-4-7",
            CallOptions(prompt = user(), reasoning = ReasoningEffort.None, temperature = 0.5),
        )

        assertTrue("temperature" !in body, "opus 4.7 rejects temperature outright: $body")
        warnings.assertUnsupported(
            "temperature",
            "temperature is not supported by claude-opus-4-7 and will be ignored",
        )
    }

    // --- unsigned reasoning ---------------------------------------------------------------------

    @Test
    fun `reasoning with no signature is omitted, never sent as an empty text block`() = runTest {
        val prompt = listOf(
            ModelMessage.User(listOf(UserPart.Text("hi"))),
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.Reasoning("a thought whose signature was lost upstream"),
                    AssistantPart.Text("the visible answer"),
                ),
            ),
            ModelMessage.User(listOf(UserPart.Text("go on"))),
        )
        val (body, warnings) = send("claude-sonnet-4-5", CallOptions(prompt = prompt))

        val assistant = body.arr("messages")!![1].jsonObject
        val blocks = assistant.arr("content")!!.map { it.jsonObject }
        assertEquals(listOf("text"), blocks.map { it["type"].string() })
        assertEquals("the visible answer", blocks.single()["text"].string())
        assertTrue(
            warnings.any { it is com.sabreware.aide.aisdk.Warning.Other && "reasoning metadata" in it.message },
            "the dropped block must be reported: $warnings",
        )
    }

    @Test
    fun `a signed thinking block replays verbatim, ahead of the text`() = runTest {
        val prompt = listOf(
            ModelMessage.User(listOf(UserPart.Text("hi"))),
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.Reasoning(
                        text = "step one",
                        providerOptions = mapOf(
                            ANTHROPIC_PROVIDER_ID to buildJsonObject { put("signature", "Er4BCkYIBRgC") },
                        ),
                    ),
                    AssistantPart.Text("answer"),
                ),
            ),
        )
        val (body, _) = send("claude-sonnet-4-5", CallOptions(prompt = prompt))

        val blocks = body.arr("messages")!![1].jsonObject.arr("content")!!.map { it.jsonObject }
        assertEquals(listOf("thinking", "text"), blocks.map { it["type"].string() })
        assertEquals("step one", blocks[0]["thinking"].string())
        assertEquals("Er4BCkYIBRgC", blocks[0]["signature"].string())
    }

    // --- max_tokens -----------------------------------------------------------------------------

    @Test
    fun `an unset limit gets the model's own ceiling, not a flat default`() = runTest {
        val (body, _) = send(
            "claude-opus-5",
            CallOptions(prompt = user(), reasoning = ReasoningEffort.ProviderDefault),
        )

        // Opus 5 is adaptive, so no budget is added: the caller's absent limit resolves to what the model
        // will actually emit. A flat 4096 here truncated every long answer on every current model.
        assertEquals(128_000, body["max_tokens"].int())
    }

    @Test
    fun `the thinking budget is added to the caller's limit, not taken out of it`() = runTest {
        val (body, warnings) = send(
            "claude-sonnet-4-5",
            CallOptions(prompt = user(), reasoning = ReasoningEffort.Low, maxOutputTokens = 2_000),
        )

        val budget = body["thinking"]!!.jsonObject["budget_tokens"].int()!!
        assertEquals(2_000 + budget, body["max_tokens"].int())
        warnings.assertCompatibility("maxOutputTokens")
    }

    @Test
    fun `a limit above the model's ceiling is clamped and the caller is told`() = runTest {
        val (body, warnings) = send(
            "claude-sonnet-4-5",
            CallOptions(prompt = user(), reasoning = ReasoningEffort.None, maxOutputTokens = 200_000),
        )

        assertEquals(64_000, body["max_tokens"].int())
        warnings.assertUnsupported("maxOutputTokens")
    }

    // --- provider-defined tools -----------------------------------------------------------------

    @Test
    fun `a provider-defined tool goes out as its wire type and pulls in its beta`() = runTest {
        val server = TestServer(stream)
        val model = AnthropicLanguageModel(modelId = "claude-sonnet-4-5", http = server.http())
        model.doStream(
            CallOptions(
                prompt = user(),
                reasoning = ReasoningEffort.None,
                tools = listOf(
                    Tool.ProviderDefined(
                        name = "search_the_web",
                        id = "anthropic.web_search_20250305",
                        args = buildJsonObject { put("maxUses", 3) },
                    ),
                ),
            ),
        ).stream.toList()

        val tool = server.request().bodyJson().arr("tools")!!.single().jsonObject
        assertEquals("web_search_20250305", tool["type"].string())
        // The vendor's fixed name, not the caller's: the response half translates back through
        // ToolNameMapping, and a call naming `search_the_web` would reach a client that never heard of it.
        assertEquals("web_search", tool["name"].string())
        assertEquals(3, tool["max_uses"].int())
    }

    @Test
    fun `an unknown provider-defined tool is reported rather than dropped in silence`() = runTest {
        val (body, warnings) = send(
            "claude-sonnet-4-5",
            CallOptions(
                prompt = user(),
                reasoning = ReasoningEffort.None,
                tools = listOf(
                    Tool.ProviderDefined("x", "anthropic.not_a_real_tool", buildJsonObject { }),
                ),
            ),
        )

        assertTrue("tools" !in body, "an unmappable tool must not be guessed at: $body")
        warnings.assertUnsupported("provider-defined tool anthropic.not_a_real_tool")
    }

    // --- headers --------------------------------------------------------------------------------

    @Test
    fun `caller headers reach the vendor and their betas merge with the request's own`() = runTest {
        val server = TestServer(stream)
        val model = AnthropicLanguageModel(
            modelId = "claude-sonnet-4-5",
            http = server.http(),
            headers = mapOf("x-api-key" to "k"),
        )
        model.doStream(
            CallOptions(
                prompt = user(),
                reasoning = ReasoningEffort.Medium,
                tools = listOf(
                    Tool.Function("echo", buildJsonObject { put("type", "object") }),
                ),
                headers = mapOf("x-trace-id" to "t-1", "anthropic-beta" to "files-api-2025-04-14"),
            ),
        ).stream.toList()

        val call = server.request()
        call.assertHeader("x-api-key", "k")
        call.assertHeader("x-trace-id", "t-1")
        val betas = call.header("anthropic-beta")!!.split(",").toSet()
        assertEquals(
            setOf(ANTHROPIC_INTERLEAVED_THINKING_BETA, "files-api-2025-04-14"),
            betas,
            "the caller's beta must join the request's own, never replace it",
        )
    }

    // --- eager input streaming and per-tool options ---------------------------------------------

    @Test
    fun `function tools stream their inputs eagerly by default`() = runTest {
        val (body, _) = send(
            "claude-opus-4-5",
            CallOptions(prompt = user(), tools = listOf(Tool.Function("f", buildJsonObject { }))),
        )

        val tool = body.arr("tools")!![0].jsonObject
        assertEquals(true, tool["eager_input_streaming"]!!.jsonPrimitive.content.toBoolean())
        // The option is CONSUMED: there is no top-level tool_streaming body field on this API.
        assertTrue("tool_streaming" !in body, body.toString())
    }

    @Test
    fun `toolStreaming=false turns eager streaming off without leaking a body field`() = runTest {
        val (body, _) = send(
            "claude-opus-4-5",
            CallOptions(
                prompt = user(),
                tools = listOf(Tool.Function("f", buildJsonObject { })),
                providerOptions = mapOf("anthropic" to buildJsonObject { put("toolStreaming", false) }),
            ),
        )

        val tool = body.arr("tools")!![0].jsonObject
        assertTrue("eager_input_streaming" !in tool, tool.toString())
        assertTrue("tool_streaming" !in body, body.toString())
    }

    @Test
    fun `a tool's own eagerInputStreaming and deferLoading override and pass through`() = runTest {
        val perTool = mapOf(
            "anthropic" to buildJsonObject {
                put("eagerInputStreaming", false)
                put("deferLoading", true)
            },
        )
        val (body, _) = send(
            "claude-opus-4-5",
            CallOptions(
                prompt = user(),
                tools = listOf(Tool.Function("f", buildJsonObject { }, providerOptions = perTool)),
            ),
        )

        val tool = body.arr("tools")!![0].jsonObject
        assertTrue("eager_input_streaming" !in tool, tool.toString())
        assertEquals(true, tool["defer_loading"]!!.jsonPrimitive.content.toBoolean())
    }

    // --- options namespace ----------------------------------------------------------------------

    @Test
    fun `a rehosting vendor's own options namespace merges over the canonical one`() = runTest {
        val server = TestServer(stream)
        val model = AnthropicLanguageModel(
            modelId = "claude-opus-4-5",
            http = server.http(),
            optionsNamespace = "minimax",
        )
        model.doStream(
            CallOptions(
                prompt = user(),
                providerOptions = mapOf(
                    "anthropic" to buildJsonObject { put("serviceTier", "standard") },
                    "minimax" to buildJsonObject { put("serviceTier", "priority") },
                ),
            ),
        ).stream.toList()

        // Custom key wins field-by-field; the merged value is snake-cased onto the wire.
        assertEquals("priority", server.request().bodyJson()["service_tier"]!!.jsonPrimitive.content)
    }
}
