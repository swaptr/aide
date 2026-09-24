package com.sabreware.aide.aisdk.providers.moonshotai

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.providers.openaicompatible.reasoningTrace
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarningAbout
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Moonshot's wire, pinned where a table row got it wrong.
 *
 * Details verified against the current API reference at <https://platform.kimi.ai/docs/api/chat>
 * rather than only the vendored `ai@7.0.85` snapshot: top-level `cached_tokens`, `partial` prefill,
 * `video_url` parts, and the MFJS schema requirement.
 */
class MoonshotTest {

    private fun provider(server: TestServer) = MoonshotProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-key",
    )

    private val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Hello"))))
    private val call = CallOptions(prompt = prompt)

    /** `TestServer.sse` joins verbatim, so the framing is the caller's to add. */
    private fun sse(vararg objects: String) = TestServer(
        TestServer.sse(objects.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"),
    )

    private fun ok(usage: String? = null) = sse(
        """{"id":"c","created":1777000000,"model":"kimi-k2.6","choices":[{"index":0,""" +
            """"delta":{"role":"assistant","content":"Hi."},"finish_reason":"stop"}]""" +
            (usage?.let { ""","usage":$it""" } ?: "") + "}",
    )

    private fun vendor(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        mapOf(MOONSHOT_PROVIDER_ID to buildJsonObject(build))

    // --- Endpoint and surface -------------------------------------------------------------------------
    // Moved here when the `Vendors.moonshot` row was deleted: the row is gone, but the facts it pinned
    // are facts about the vendor, and they now belong to the provider that replaced it.

    @Test
    fun `the request goes to the documented base and carries bearer auth`() = runTest {
        val server = ok()

        provider(server).languageModel("kimi-k2.6").doStream(call).stream.toList()

        val request = server.request()
        assertEquals("https://api.moonshot.ai/v1/chat/completions", request.url)
        assertEquals("Bearer test-key", request.header("Authorization"))
    }

    @Test
    fun `chat is the only modality, because advertising more is how a 404 gets promised`() = runTest {
        val moonshot = provider(ok())

        // The honesty rule: a capability the vendor does not serve in this shape is bound NOWHERE, so a
        // caller reads null and omits the affordance rather than discovering it as a 404 at runtime.
        assertNull(moonshot.imageModel("anything"))
        assertNull(moonshot.embeddingModel("anything"))
        assertNull(moonshot.speechModel("anything"))
        assertNull(moonshot.transcriptionModel("anything"))
    }

    // --- usage ----------------------------------------------------------------------------------------

    @Test
    fun `cached_tokens is read from the top level, where Moonshot actually reports it`() = runTest {
        // platform.kimi.ai documents cached_tokens as a top-level field of `usage`. The shared OpenAI
        // reading looks under prompt_tokens_details and finds nothing, so a cached call reported zero
        // cache reads — a plausible number, which is exactly what made it invisible.
        val server = ok("""{"prompt_tokens":100,"completion_tokens":20,"cached_tokens":80}""")

        val finish = provider(server).languageModel("kimi-k2.6")
            .doStream(call).stream.toList().filterIsInstance<StreamPart.Finish>().single()

        assertEquals(80, finish.usage.inputTokens.cacheRead)
        assertEquals(20, finish.usage.inputTokens.noCache)
        assertEquals(100, finish.usage.inputTokens.total)
    }

    @Test
    fun `the nested spelling still works, as a fallback`() = runTest {
        val server = ok(
            """{"prompt_tokens":100,"completion_tokens":30,"prompt_tokens_details":{"cached_tokens":40},""" +
                """"completion_tokens_details":{"reasoning_tokens":10}}""",
        )

        val finish = provider(server).languageModel("kimi-k2.6")
            .doStream(call).stream.toList().filterIsInstance<StreamPart.Finish>().single()

        assertEquals(40, finish.usage.inputTokens.cacheRead)
        assertEquals(10, finish.usage.outputTokens.reasoning)
        assertEquals(20, finish.usage.outputTokens.text)
    }

    // --- schemas --------------------------------------------------------------------------------------

    @Test
    fun `every tool schema is rewritten for MFJS before it reaches the wire`() = runTest {
        val server = ok()
        val tool = Tool.Function(
            name = "search",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("pair") {
                        put("type", "array")
                        put(
                            "items",
                            kotlinx.serialization.json.buildJsonArray {
                                add(buildJsonObject { put("type", "string") })
                                add(buildJsonObject { put("type", "number") })
                            },
                        )
                    }
                }
                put("title", "Search")
            },
        )

        provider(server).languageModel("kimi-k2.6").doStream(call.copy(tools = listOf(tool))).stream.toList()

        val parameters = server.request().bodyJson()["tools"]!!.jsonArray.single()
            .jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertNull(parameters["title"], "a prohibited annotation must not reach MFJS")
        val pair = parameters["properties"]!!.jsonObject["pair"]!!.jsonObject
        assertNull(pair["prefixItems"], "the MFJS spec prohibits prefixItems")
        assertEquals(2, pair["items"]!!.jsonObject["anyOf"]!!.jsonArray.size)
    }

    @Test
    fun `the structured-output schema is rewritten and loses its dollar-schema`() = runTest {
        val server = ok()
        val schema = buildJsonObject {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("type", "object")
            putJsonObject("properties") { putJsonObject("n") { put("type", "number"); put("format", "float") } }
        }

        provider(server).languageModel("kimi-k2.5")
            .doStream(call.copy(responseFormat = ResponseFormat.Json(schema = schema))).stream.toList()

        val sent = server.request().bodyJson()["response_format"]!!.jsonObject["json_schema"]!!
            .jsonObject["schema"]!!.jsonObject
        // K2.5 produces nonsensical output when the injected $schema keyword is present.
        assertNull(sent["\$schema"])
        assertNull(sent["properties"]!!.jsonObject["n"]!!.jsonObject["format"])
    }

    // --- family gating --------------------------------------------------------------------------------

    @Test
    fun `Kimi K3 rejects the thinking field, so it is dropped with a warning`() = runTest {
        val server = ok()

        val parts = provider(server).languageModel("kimi-k3")
            .doStream(call.copy(providerOptions = vendor { putJsonObject("thinking") { put("type", "enabled") } }))
            .stream.toList()

        assertNull(server.request().bodyJson()["thinking"])
        parts.filterIsInstance<StreamPart.StreamStart>().single().warnings.assertUnsupported("thinking")
    }

    @Test
    fun `Kimi K2_7 thinking cannot be disabled`() = runTest {
        val server = ok()

        val parts = provider(server).languageModel("kimi-k2.7-code")
            .doStream(call.copy(providerOptions = vendor { putJsonObject("thinking") { put("type", "disabled") } }))
            .stream.toList()

        assertNull(server.request().bodyJson()["thinking"])
        parts.filterIsInstance<StreamPart.StreamStart>().single().warnings
            .assertUnsupported("thinking.type \"disabled\"")
    }

    @Test
    fun `Kimi K2_6 alone can preserve reasoning across turns`() = runTest {
        val server = ok()

        provider(server).languageModel("kimi-k2.6")
            .doStream(call.copy(providerOptions = vendor { put("reasoningHistory", "preserved") }))
            .stream.toList()

        val thinking = server.request().bodyJson()["thinking"]!!.jsonObject
        assertEquals("all", thinking["keep"]!!.jsonPrimitive.content)
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `preserving reasoning warns on a family that cannot`() = runTest {
        val server = ok()

        val parts = provider(server).languageModel("kimi-k2.5")
            .doStream(call.copy(providerOptions = vendor { put("reasoningHistory", "preserved") }))
            .stream.toList()

        parts.filterIsInstance<StreamPart.StreamStart>().single().warnings
            .assertUnsupported("reasoningHistory \"preserved\"")
    }

    @Test
    fun `moonshot-v1 has no reasoning at all, and says so rather than sending one`() = runTest {
        val server = ok()

        val parts = provider(server).languageModel("moonshot-v1-32k")
            .doStream(call.copy(reasoning = ReasoningEffort.High)).stream.toList()

        val body = server.request().bodyJson()
        assertNull(body["thinking"])
        assertNull(body["reasoning_effort"])
        parts.filterIsInstance<StreamPart.StreamStart>().single().warnings.assertUnsupported("reasoning")
    }

    @Test
    fun `reasoning effort is K3's alone, mapped onto the three levels it names`() = runTest {
        val server = ok()

        provider(server).languageModel("kimi-k3")
            .doStream(call.copy(reasoning = ReasoningEffort.XHigh)).stream.toList()

        // The unified scale tops out above Moonshot's, so xhigh lands on `max` rather than being sent raw.
        assertEquals("max", server.request().bodyJson()["reasoning_effort"].string())
    }

    @Test
    fun `reasoning effort on a non-K3 model warns instead of reaching the wire`() = runTest {
        val server = ok()

        val parts = provider(server).languageModel("kimi-k2.6")
            .doStream(call.copy(providerOptions = vendor { put("reasoningEffort", "high") })).stream.toList()

        assertNull(server.request().bodyJson()["reasoning_effort"])
        parts.filterIsInstance<StreamPart.StreamStart>().single().warnings.assertUnsupported("reasoningEffort")
    }

    // --- tool choice ----------------------------------------------------------------------------------

    @Test
    fun `required tool choice is dropped on the three models that reject it`() = runTest {
        val server = ok()
        val tool = Tool.Function(name = "t", inputSchema = buildJsonObject { put("type", "object") })

        val parts = provider(server).languageModel("kimi-k2.6")
            .doStream(call.copy(tools = listOf(tool), toolChoice = ToolChoice.Required)).stream.toList()

        assertNull(server.request().bodyJson()["tool_choice"])
        parts.filterIsInstance<StreamPart.StreamStart>().single().warnings
            .assertUnsupported("tool choice \"required\" for model \"kimi-k2.6\"")
    }

    @Test
    fun `required tool choice survives on a model that accepts it`() = runTest {
        val server = ok()
        val tool = Tool.Function(name = "t", inputSchema = buildJsonObject { put("type", "object") })

        val parts = provider(server).languageModel("kimi-k3")
            .doStream(call.copy(tools = listOf(tool), toolChoice = ToolChoice.Required)).stream.toList()

        assertEquals("required", server.request().bodyJson()["tool_choice"].string())
        parts.filterIsInstance<StreamPart.StreamStart>().single().warnings.assertNoWarningAbout("tool choice")
    }

    // --- message-level options ------------------------------------------------------------------------

    @Test
    fun `partial prefill rides on the assistant message it belongs to`() = runTest {
        val server = ok()
        val withPrefill = listOf(
            ModelMessage.User(listOf(UserPart.Text("Write a poem"))),
            ModelMessage.Assistant(
                listOf(com.sabreware.aide.aisdk.AssistantPart.Text("Roses are")),
                providerOptions = vendor { put("partial", true) },
            ),
        )

        provider(server).languageModel("kimi-k2.6").doStream(CallOptions(prompt = withPrefill)).stream.toList()

        val messages = server.request().bodyJson()["messages"]!!.jsonArray
        assertNull(messages[0].jsonObject["partial"])
        // Documented at platform.kimi.ai as a boolean on the final assistant message.
        assertEquals("true", messages[1].jsonObject["partial"]!!.jsonPrimitive.content)
        assertEquals("assistant", messages[1].jsonObject["role"].string())
    }

    // --- passthrough ----------------------------------------------------------------------------------

    @Test
    fun `the documented caller options reach the wire in Moonshot's spellings`() = runTest {
        val server = ok()

        provider(server).languageModel("kimi-k2.6")
            .doStream(
                call.copy(
                    providerOptions = vendor {
                        put("promptCacheKey", "session-1")
                        put("safetyIdentifier", "user-hash")
                        put("topLogprobs", 5)
                    },
                ),
            ).stream.toList()

        val body = server.request().bodyJson()
        assertEquals("session-1", body["prompt_cache_key"].string())
        assertEquals("user-hash", body["safety_identifier"].string())
        assertEquals("5", body["top_logprobs"]!!.jsonPrimitive.content)
        // Asking for the top-N implies asking for log-probs at all.
        assertEquals("true", body["logprobs"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a thinking budget is not a Moonshot concept, and says so`() = runTest {
        val server = ok()

        val parts = provider(server).languageModel("kimi-k2.6")
            .doStream(
                call.copy(
                    providerOptions = vendor {
                        putJsonObject("thinking") { put("type", "enabled"); put("budgetTokens", 2048) }
                    },
                ),
            ).stream.toList()

        assertNull(server.request().bodyJson().obj("thinking")?.get("budgetTokens"))
        parts.filterIsInstance<StreamPart.StreamStart>().single().warnings
            .assertUnsupported("thinking.budgetTokens")
    }

    @Test
    fun `the family table maps every documented id, and never guesses`() {
        assertEquals(MoonshotFamily.KimiK3, moonshotFamily("kimi-k3"))
        assertEquals(MoonshotFamily.KimiK27, moonshotFamily("kimi-k2.7-code"))
        assertEquals(MoonshotFamily.KimiK27, moonshotFamily("kimi-k2.7-code-highspeed"))
        assertEquals(MoonshotFamily.KimiK26, moonshotFamily("kimi-k2.6"))
        assertEquals(MoonshotFamily.KimiK25, moonshotFamily("kimi-k2.5"))
        assertEquals(MoonshotFamily.MoonshotV1, moonshotFamily("moonshot-v1-128k"))
        // An id the table has never seen is Unknown rather than assumed into a family whose rules
        // would then be applied to it.
        assertEquals(MoonshotFamily.Unknown, moonshotFamily("kimi-k4-preview"))
    }

    @Test
    fun `structured output is an allowlist, so an unknown model is downgraded rather than assumed`() {
        assertTrue(supportsStructuredOutputs("kimi-k2.6"))
        assertTrue(supportsStructuredOutputs("moonshot-v1-32k"))
        assertTrue(!supportsStructuredOutputs("some-other-model"))
    }

    // --- ai@7.0.102 -----------------------------------------------------------------------------

    @Test
    fun `reasoning stays one block across deltas that carry an empty tool_calls array`() = runTest {
        // The reference's fixture (`0096850`); the fix is the compat engine's, this pins it on this wire.
        val server = sse(
            """{"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"test-model",""" +
                """"choices":[{"index":0,"delta":{"role":"assistant","content":"","reasoning_content":"Think ","tool_calls":[]},""" +
                """"finish_reason":null}]}""",
            """{"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"test-model",""" +
                """"choices":[{"index":0,"delta":{"content":"","reasoning_content":"more...","tool_calls":[]},""" +
                """"finish_reason":null}]}""",
            """{"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"test-model",""" +
                """"choices":[{"index":0,"delta":{"content":"Hello","reasoning_content":"","tool_calls":[]},""" +
                """"finish_reason":"stop"}]}""",
        )

        val parts = provider(server).languageModel("kimi-k2-thinking").doStream(call).stream.toList()

        assertEquals(
            listOf("start reasoning-0", "delta reasoning-0 Think ", "delta reasoning-0 more...", "end reasoning-0"),
            parts.reasoningTrace(),
        )
    }
}
