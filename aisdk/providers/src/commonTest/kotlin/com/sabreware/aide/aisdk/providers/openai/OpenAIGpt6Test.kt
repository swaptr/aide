package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.bool
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * GPT-6's request rules and async tool calling, against the GPT-6 cases of
 * `openai-responses-language-model.test.ts`.
 *
 * Every rule here is a 400 if got wrong, and each is derived from the version rather than a name
 * table: a closed effort list, a `configuration_update` item that changes effort without touching the
 * cached prefix, `async` on tools and their calls, and the settings the family refuses outright.
 */
class OpenAIGpt6Test {

    private val emptyResponse = TestServer.json("""{"id":"resp_1","output":[]}""")

    private val call = CallOptions(prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Hello")))))

    private val userItem = parseJsonObject("""{"role":"user","content":[{"type":"input_text","text":"Hello"}]}""")

    private fun model(server: TestServer, modelId: String = "gpt-6-astra") =
        OpenAIResponsesLanguageModel(modelId = modelId, http = server.http(), generateId = { "src_1" })

    private fun openai(build: JsonObjectBuilder.() -> Unit) = mapOf("openai" to buildJsonObject(build))

    private fun chunks(vararg objects: String) = objects.map { "data: $it\n\n" }.toTypedArray()

    private val weather = Tool.Function(
        "weather",
        parseJsonObject("""{"type":"object","properties":{"location":{"type":"string"}}}"""),
        providerOptions = openai { put("async", true) },
    )

    @Test
    fun `sampling and logprob settings are stripped for GPT-6`() = runTest {
        val server = TestServer(emptyResponse)
        val result = model(server).doGenerate(
            call.copy(
                temperature = 0.5,
                topP = 0.7,
                providerOptions = openai {
                    put("reasoningEffort", "low")
                    put("logprobs", 5)
                    put("include", buildJsonArray { add("message.output_text.logprobs") })
                },
            ),
        )

        assertEquals(
            parseJsonObject(
                """{"model":"gpt-6-astra","input":[$userItem],"reasoning":{"effort":"low","summary":"detailed"}}""",
            ),
            server.request(0).bodyJson(),
        )
        assertEquals(
            listOf("temperature", "topP", "logprobs"),
            result.warnings.map { assertIs<Warning.Unsupported>(it).feature },
        )
    }

    @Test
    fun `an explicit effort outside the closed list is dropped with a warning`() = runTest {
        val server = TestServer(emptyResponse)
        val result = model(server).doGenerate(call.copy(providerOptions = openai { put("reasoningEffort", "none") }))

        assertNull(server.request(0).bodyJson()["reasoning"])
        result.warnings.assertUnsupported(
            "reasoningEffort",
            "gpt-6-astra only supports the following reasoning efforts: low, medium, high, xhigh, max",
        )
    }

    @Test
    fun `a reasoning effort update goes first in the input, leaving the request-level effort in place`() = runTest {
        val server = TestServer(emptyResponse)
        val result = model(server).doGenerate(
            call.copy(
                providerOptions = openai {
                    put("previousResponseId", "resp_123")
                    put("reasoningEffort", "low")
                    put("reasoningEffortUpdate", "high")
                },
            ),
        )

        assertEquals(
            parseJsonObject(
                """{"model":"gpt-6-astra","input":[{"type":"configuration_update","reasoning":{"effort":"high"}},$userItem],""" +
                    """"previous_response_id":"resp_123","reasoning":{"effort":"low","summary":"detailed"}}""",
            ),
            server.request(0).bodyJson(),
        )
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `an effort update is refused before GPT-6`() = runTest {
        val server = TestServer(emptyResponse)
        val result = model(server, "gpt-5.6").doGenerate(
            call.copy(providerOptions = openai { put("reasoningEffortUpdate", "high") }),
        )

        assertEquals(listOf(userItem), server.request(0).bodyJson().arr("input")!!.map { it.jsonObject })
        assertEquals(
            listOf<Warning>(
                Warning.Unsupported(
                    "reasoningEffortUpdate",
                    "reasoningEffortUpdate is only supported by GPT-6 and later models",
                ),
            ),
            result.warnings,
        )
    }

    @Test
    fun `an effort update is refused alongside automatic compaction`() = runTest {
        val server = TestServer(emptyResponse)
        val result = model(server).doGenerate(
            call.copy(
                providerOptions = openai {
                    put("reasoningEffortUpdate", "high")
                    put(
                        "contextManagement",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "compaction")
                                    put("compactThreshold", 1000)
                                },
                            )
                        },
                    )
                },
            ),
        )

        assertEquals(listOf(userItem), server.request(0).bodyJson().arr("input")!!.map { it.jsonObject })
        assertEquals(
            listOf<Warning>(
                Warning.Unsupported(
                    "reasoningEffortUpdate",
                    "reasoningEffortUpdate requires standard reasoning mode without automatic compaction " +
                        "or automatic truncation",
                ),
            ),
            result.warnings,
        )
    }

    @Test
    fun `the legacy cache retention flag is dropped on GPT-6`() = runTest {
        val server = TestServer(emptyResponse)
        val result = model(server).doGenerate(
            call.copy(providerOptions = openai { put("promptCacheRetention", "24h") }),
        )

        assertEquals(parseJsonObject("""{"model":"gpt-6-astra","input":[$userItem]}"""), server.request(0).bodyJson())
        assertEquals(
            listOf<Warning>(
                Warning.Unsupported(
                    "promptCacheRetention",
                    "promptCacheRetention is not supported by GPT-6 and later models; use promptCacheOptions instead",
                ),
            ),
            result.warnings,
        )
    }

    @Test
    fun `async tools are gated to GPT-6 and later`() = runTest {
        val unsupported = TestServer(emptyResponse)
        val result = model(unsupported, "gpt-5.6").doGenerate(call.copy(tools = listOf(weather)))
        assertNull(unsupported.request(0).bodyJson()["tools"]!!.jsonArray.single().jsonObject["async"])
        result.warnings.assertUnsupported(
            "async tool calling for \"weather\"",
            "Async tool calling is only supported by GPT-6 and later models.",
        )

        val supported = TestServer(emptyResponse)
        model(supported, "gpt-99").doGenerate(call.copy(tools = listOf(weather)))
        assertEquals(true, supported.request(0).bodyJson()["tools"]!!.jsonArray.single().jsonObject["async"].bool())
    }

    @Test
    fun `async mode, namespace and caller come off a function call and are filed for replay`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"id":"resp_1","output":[{"type":"function_call","id":"fc_ns_1","call_id":"call_1",""" +
                    """"name":"weather","arguments":"{\"location\":\"NYC\"}","status":"completed","async":true,""" +
                    """"namespace":"weather_ns","caller":{"type":"program","caller_id":"prog_1"}}]}""",
            ),
        )

        val result = model(server).doGenerate(call.copy(tools = listOf(weather)))

        val toolCall = assertIs<Content.ToolCall>(result.content.single())
        assertEquals(
            parseJsonObject(
                """{"itemId":"fc_ns_1","async":true,"namespace":"weather_ns","caller":{"type":"program","callerId":"prog_1"}}""",
            ),
            toolCall.providerMetadata!!["openai"],
        )
    }

    @Test
    fun `async mode survives a streamed function call whose closing item omits it`() = runTest {
        val server = TestServer(
            TestServer.sse(
                *chunks(
                    """{"type":"response.created","response":{"id":"response_async","created_at":1,"model":"gpt-6-astra"}}""",
                    """{"type":"response.output_item.added","output_index":0,"item":{"id":"fc_async","type":"function_call","name":"weather","call_id":"call_async","arguments":"","status":"in_progress","async":true}}""",
                    """{"type":"response.output_item.done","output_index":0,"item":{"id":"fc_async","type":"function_call","name":"weather","call_id":"call_async","arguments":"{\"location\":\"Berlin\"}","status":"completed"}}""",
                    """{"type":"response.completed","response":{"incomplete_details":null,"usage":{"input_tokens":1,"output_tokens":2}}}""",
                ),
            ),
        )

        val parts = model(server).doStream(call.copy(tools = listOf(weather))).stream.toList()

        val toolCall = parts.filterIsInstance<StreamPart.ToolCallPart>().single().toolCall
        assertEquals("call_async", toolCall.toolCallId)
        assertEquals("weather", toolCall.toolName)
        assertEquals("""{"location":"Berlin"}""", toolCall.input)
        assertEquals(
            parseJsonObject("""{"itemId":"fc_async","async":true}"""),
            toolCall.providerMetadata!!["openai"],
        )
    }
}
