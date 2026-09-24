package com.sabreware.aide.aisdk.providers.groq

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.JsonParseError
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.openaicompatible.REASONING_CONTENT_KEY
import com.sabreware.aide.aisdk.providers.openaicompatible.Vendors
import com.sabreware.aide.aisdk.providers.openaicompatible.defaultOpenAICompatibleUsage
import com.sabreware.aide.aisdk.providers.openaicompatible.reasoningTrace
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.bool
import com.sabreware.aide.aisdk.providers.testing.int
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Groq through the vendor table, against `groq-chat-language-model.test.ts` and the wire Groq
 * recorded for it.
 *
 * The thing worth the size of this file: Groq reports a streamed turn's token counts under its own
 * `x_groq.usage` envelope and never honours `stream_options`, and the compat engine has always read
 * that envelope unconditionally. A "Groq loses streamed usage" defect was reported against it and
 * withdrawn; these fixtures are what stop it being reported a second time. The rest pins the reasoning
 * channel, the tool-call assembly, the finish vocabulary, the error frames and the two doc-checked
 * corrections on the row — with the reference's own bytes, so agreement means agreement with Groq.
 */
class GroqFixturesTest {

    private val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Hello"))))
    private val call = CallOptions(prompt = prompt)

    private fun provider(server: TestServer) = Vendors.groq(HttpClient(server.engine()), "test-api-key")

    private fun model(server: TestServer, modelId: String = "gemma2-9b-it") =
        provider(server).languageModel(modelId)!!

    private fun sse(chunks: List<String>) =
        TestServer(TestServer.sse(chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"))

    /** The `delta.<key>` fragments a recorded stream carries, in order, empties dropped. */
    private fun List<String>.fragments(key: String): List<String> = mapNotNull { line ->
        parseJsonObject(line).arr("choices")?.firstOrNull()?.jsonObject?.obj("delta")?.get(key).string()
            ?.takeIf { it.isNotEmpty() }
    }

    // --- the recorded streams ---------------------------------------------------------------------------

    @Test
    fun `the text recording streams one block and its usage, with no stream_options asked for`() = runTest {
        val server = sse(GroqFixtures.textChunks)

        val parts = model(server).doStream(call).stream.toList()

        assertEquals(StreamPart.StreamStart(emptyList()), parts[0])
        val metadata = assertIs<StreamPart.ResponseMetadataPart>(parts[1]).metadata
        assertEquals("chatcmpl-7eb08824-fb8d-47af-a1f0-3aa786f2d1f3", metadata.id)
        assertEquals("llama-3.3-70b-versatile", metadata.modelId)
        assertEquals(1_770_770_839_000L, metadata.timestamp)
        assertIs<StreamPart.TextStart>(parts[2])
        val deltas = parts.filterIsInstance<StreamPart.TextDelta>().map { it.delta }
        assertEquals(GroqFixtures.textChunks.fragments("content"), deltas)
        assertEquals(661, deltas.size)
        assertEquals("Int", deltas.first())
        assertEquals(".", deltas.last())
        assertIs<StreamPart.TextEnd>(parts[parts.size - 2])
        val finish = assertIs<StreamPart.Finish>(parts.last())
        assertEquals(FinishReason(FinishReason.Unified.Stop, "stop"), finish.finishReason)
        assertEquals(45, finish.usage.inputTokens.total)
        assertEquals(662, finish.usage.outputTokens.total)
        assertEquals(707, finish.usage.raw?.get("total_tokens").int())

        val request = server.request()
        assertEquals("https://api.groq.com/openai/v1/chat/completions", request.url)
        request.assertHeader("Authorization", "Bearer test-api-key")
        request.assertBodyKeys("model", "messages", "stream")
        request.assertBodyJson { body ->
            assertEquals("gemma2-9b-it", body["model"].string())
            assertEquals("Hello", body.arr("messages")!!.single().jsonObject["content"].string())
        }
    }

    @Test
    fun `the tool-call recording assembles one call and takes its usage off x_groq`() = runTest {
        val server = sse(GroqFixtures.toolCallChunks)

        val parts = model(server).doStream(call).stream.toList()

        assertEquals(
            listOf(
                "StreamStart", "ResponseMetadataPart", "ToolInputStart", "ToolInputDelta", "ToolInputEnd",
                "ToolCallPart", "Finish",
            ),
            parts.map { it::class.simpleName },
        )
        assertEquals(StreamPart.ToolInputStart("tk85n1k4m", "weather"), parts[2])
        assertEquals(StreamPart.ToolInputDelta("tk85n1k4m", "{}"), parts[3])
        val toolCall = assertIs<StreamPart.ToolCallPart>(parts[5]).toolCall
        assertEquals("tk85n1k4m", toolCall.toolCallId)
        assertEquals("weather", toolCall.toolName)
        assertEquals("{}", toolCall.input)
        val finish = assertIs<StreamPart.Finish>(parts.last())
        assertEquals(FinishReason(FinishReason.Unified.ToolCalls, "tool_calls"), finish.finishReason)
        assertEquals(210, finish.usage.inputTokens.total)
        assertEquals(15, finish.usage.outputTokens.total)
        assertEquals(225, finish.usage.raw?.get("total_tokens").int())
    }

    @Test
    fun `the reasoning recording keeps delta_reasoning on its own channel, then the answer`() = runTest {
        val server = sse(GroqFixtures.reasoningChunks)

        val parts = model(server, "qwen/qwen3-32b").doStream(call).stream.toList()

        assertEquals("chatcmpl-3556c041-562b-471f-9a90-763dbcea5a3f", assertIs<StreamPart.ResponseMetadataPart>(parts[1]).metadata.id)
        assertEquals(StreamPart.ReasoningStart("reasoning-0"), parts[2])
        val reasoning = parts.filterIsInstance<StreamPart.ReasoningDelta>().map { it.delta }
        assertEquals(GroqFixtures.reasoningChunks.fragments("reasoning"), reasoning)
        assertEquals(963, reasoning.size)
        assertEquals("Okay", reasoning.first())
        // The block closes when the answer starts, carrying the text a replay has to echo back.
        val end = parts.filterIsInstance<StreamPart.ReasoningEnd>().single()
        assertEquals(
            reasoning.joinToString(""),
            end.providerMetadata?.get("groq")?.get(REASONING_CONTENT_KEY).string(),
        )
        val text = parts.filterIsInstance<StreamPart.TextDelta>().map { it.delta }
        assertEquals(GroqFixtures.reasoningChunks.fragments("content"), text)
        assertEquals(139, text.size)
        assertEquals("The", text.first())
        assertEquals("}$", text.last())
        assertTrue(parts.indexOf(end) < parts.indexOfFirst { it is StreamPart.TextStart })
        val finish = assertIs<StreamPart.Finish>(parts.last())
        assertEquals(17, finish.usage.inputTokens.total)
        assertEquals(1107, finish.usage.outputTokens.total)
        assertEquals(963, finish.usage.outputTokens.reasoning)
        assertEquals(144, finish.usage.outputTokens.text)
    }

    @Test
    fun `the recorded documents pin the usage arithmetic the engine applies to Groq's counters`() {
        // The compat engine streams every call, so the non-streaming recordings pin the reading of
        // Groq's usage block rather than a round trip — the same numbers the reference snapshots.
        fun usage(document: String) = defaultOpenAICompatibleUsage(parseJsonObject(document).obj("usage")!!)

        val text = usage(GroqFixtures.TEXT)
        assertEquals(45, text.inputTokens.total)
        assertEquals(45, text.inputTokens.noCache)
        assertEquals(607, text.outputTokens.total)
        assertEquals(607, text.outputTokens.text)
        assertNull(text.outputTokens.reasoning)
        assertEquals(652, text.raw?.get("total_tokens").int())

        val toolCall = usage(GroqFixtures.TOOL_CALL)
        assertEquals(218, toolCall.inputTokens.total)
        assertEquals(15, toolCall.outputTokens.total)

        val reasoning = usage(GroqFixtures.REASONING)
        assertEquals(17, reasoning.inputTokens.total)
        assertEquals(649, reasoning.outputTokens.total)
        assertEquals(570, reasoning.outputTokens.reasoning)
        assertEquals(79, reasoning.outputTokens.text)

        // `should extract cached input tokens`: the cached share is split out of the prompt count.
        val cached = defaultOpenAICompatibleUsage(
            parseJsonObject("""{"prompt_tokens":20,"total_tokens":25,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":15}}"""),
        )
        assertEquals(15, cached.inputTokens.cacheRead)
        assertEquals(5, cached.inputTokens.noCache)
        assertEquals(5, cached.outputTokens.text)
    }

    // --- the reference's inline streams -----------------------------------------------------------------

    @Test
    fun `streamed usage comes from x_groq when there is no usage block at all`() = runTest {
        // `should stream tool call deltas when tool call arguments are passed in the first chunk`: the
        // terminal chunk carries `x_groq.usage` and NOTHING under `usage`. This is the read that was
        // reported missing.
        val head = """{"id":"chatcmpl-e7f8e220-656c-4455-a132-dacfc1370798","object":"chat.completion.chunk","created":1711357598,"model":"gemma2-9b-it","system_fingerprint":"fp_3bc1b5746c","choices":[{"index":0,"delta":"""
        val server = sse(
            listOf(
                head + """{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_O17Uplv4lJvD6DVdIvFFeRMw","type":"function","function":{"name":"test-tool","arguments":"{\""}}]},"finish_reason":null}]}""",
                head + """{"tool_calls":[{"index":0,"function":{"arguments":"va"}}]},"finish_reason":null}]}""",
                head + """{"tool_calls":[{"index":0,"function":{"arguments":"lue"}}]},"finish_reason":null}]}""",
                head + """{"tool_calls":[{"index":0,"function":{"arguments":"\":\""}}]},"finish_reason":null}]}""",
                head + """{"tool_calls":[{"index":0,"function":{"arguments":"Spark"}}]},"finish_reason":null}]}""",
                head + """{"tool_calls":[{"index":0,"function":{"arguments":"le"}}]},"finish_reason":null}]}""",
                head + """{"tool_calls":[{"index":0,"function":{"arguments":" Day"}}]},"finish_reason":null}]}""",
                head + """{"tool_calls":[{"index":0,"function":{"arguments":"\"}"}}]},"finish_reason":null}]}""",
                """{"id":"chatcmpl-e7f8e220-656c-4455-a132-dacfc1370798","object":"chat.completion.chunk","created":1729171479,"model":"gemma2-9b-it","system_fingerprint":"fp_10c08bf97d","choices":[{"index":0,"delta":{},"logprobs":null,"finish_reason":"tool_calls"}],"x_groq":{"id":"req_01jadadp0femyae9kav1gpkhe8","usage":{"queue_time":0.061348671,"prompt_tokens":18,"prompt_time":0.000211569,"completion_tokens":439,"completion_time":0.798181818,"total_tokens":457,"total_time":0.798393387}}}""",
            ),
        )

        val parts = model(server).doStream(
            call.copy(tools = listOf(Tool.Function("test-tool", buildJsonObject { put("type", "object") }))),
        ).stream.toList()

        assertEquals(StreamPart.ToolInputStart("call_O17Uplv4lJvD6DVdIvFFeRMw", "test-tool"), parts[2])
        assertEquals(
            listOf("{\"", "va", "lue", "\":\"", "Spark", "le", " Day", "\"}"),
            parts.filterIsInstance<StreamPart.ToolInputDelta>().map { it.delta },
        )
        assertEquals("""{"value":"Sparkle Day"}""", parts.filterIsInstance<StreamPart.ToolCallPart>().single().toolCall.input)
        val finish = assertIs<StreamPart.Finish>(parts.last())
        assertEquals(FinishReason(FinishReason.Unified.ToolCalls, "tool_calls"), finish.finishReason)
        assertEquals(18, finish.usage.inputTokens.total)
        assertEquals(439, finish.usage.outputTokens.total)
        // The envelope rides through whole; the reference's schema keeps only three of its keys.
        assertEquals(457, finish.usage.raw?.get("total_tokens").int())
        assertEquals(0.798393387, finish.usage.raw?.get("total_time")!!.toString().toDouble())
    }

    @Test
    fun `a call sent in one chunk still opens, fills and closes its block`() = runTest {
        val server = sse(
            listOf(
                """{"id":"chatcmpl-e7f8e220-656c-4455-a132-dacfc1370798","object":"chat.completion.chunk","created":1711357598,"model":"gemma2-9b-it","system_fingerprint":"fp_3bc1b5746c","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_O17Uplv4lJvD6DVdIvFFeRMw","type":"function","function":{"name":"test-tool","arguments":"{\"value\":\"Sparkle Day\"}"}}]},"finish_reason":null}]}""",
                """{"id":"chatcmpl-e7f8e220-656c-4455-a132-dacfc1370798","object":"chat.completion.chunk","created":1729171479,"model":"gemma2-9b-it","system_fingerprint":"fp_10c08bf97d","choices":[{"index":0,"delta":{},"logprobs":null,"finish_reason":"tool_calls"}],"x_groq":{"id":"req_01jadadp0femyae9kav1gpkhe8","usage":{"queue_time":0.061348671,"prompt_tokens":18,"prompt_time":0.000211569,"completion_tokens":439,"completion_time":0.798181818,"total_tokens":457,"total_time":0.798393387}}}""",
            ),
        )

        val parts = model(server).doStream(call).stream.toList()

        assertEquals(
            listOf("StreamStart", "ResponseMetadataPart", "ToolInputStart", "ToolInputDelta", "ToolInputEnd", "ToolCallPart", "Finish"),
            parts.map { it::class.simpleName },
        )
        assertEquals("""{"value":"Sparkle Day"}""", (parts[3] as StreamPart.ToolInputDelta).delta)
        assertEquals(18, assertIs<StreamPart.Finish>(parts.last()).usage.inputTokens.total)
    }

    @Test
    fun `an empty chunk after a completed call does not duplicate it`() = runTest {
        val head = """{"id":"chat-2267f7e2910a4254bac0650ba74cfc1c","object":"chat.completion.chunk","created":1733162241,"model":"meta/llama-3.1-8b-instruct:fp8","choices":[{"index":0,"delta":"""
        val server = sse(
            listOf(
                head + """{"role":"assistant","content":""},"logprobs":null,"finish_reason":null}],"usage":{"prompt_tokens":226,"total_tokens":226,"completion_tokens":0}}""",
                head + """{"tool_calls":[{"id":"chatcmpl-tool-b3b307239370432d9910d4b79b4dbbaa","type":"function","index":0,"function":{"name":"searchGoogle"}}]},"logprobs":null,"finish_reason":null}],"usage":{"prompt_tokens":226,"total_tokens":233,"completion_tokens":7}}""",
                head + """{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\": \""}}]},"logprobs":null,"finish_reason":null}],"usage":{"prompt_tokens":226,"total_tokens":241,"completion_tokens":15}}""",
                head + """{"tool_calls":[{"index":0,"function":{"arguments":"latest"}}]},"logprobs":null,"finish_reason":null}],"usage":{"prompt_tokens":226,"total_tokens":242,"completion_tokens":16}}""",
                head + """{"tool_calls":[{"index":0,"function":{"arguments":" news"}}]},"logprobs":null,"finish_reason":null}],"usage":{"prompt_tokens":226,"total_tokens":243,"completion_tokens":17}}""",
                head + """{"tool_calls":[{"index":0,"function":{"arguments":" on"}}]},"logprobs":null,"finish_reason":null}],"usage":{"prompt_tokens":226,"total_tokens":244,"completion_tokens":18}}""",
                head + """{"tool_calls":[{"index":0,"function":{"arguments":" ai\"}"}}]},"logprobs":null,"finish_reason":null}],"usage":{"prompt_tokens":226,"total_tokens":245,"completion_tokens":19}}""",
                head + """{"tool_calls":[{"index":0,"function":{"arguments":""}}]},"logprobs":null,"finish_reason":"tool_calls","stop_reason":128008}],"usage":{"prompt_tokens":226,"total_tokens":246,"completion_tokens":20}}""",
                """{"id":"chat-2267f7e2910a4254bac0650ba74cfc1c","object":"chat.completion.chunk","created":1733162241,"model":"meta/llama-3.1-8b-instruct:fp8","choices":[],"usage":{"prompt_tokens":226,"total_tokens":246,"completion_tokens":20}}""",
            ),
        )

        val parts = model(server).doStream(
            call.copy(tools = listOf(Tool.Function("searchGoogle", buildJsonObject { put("type", "object") }))),
        ).stream.toList()

        val calls = parts.filterIsInstance<StreamPart.ToolCallPart>()
        assertEquals(1, calls.size)
        assertEquals("""{"query": "latest news on ai"}""", calls.single().toolCall.input)
        assertEquals("chatcmpl-tool-b3b307239370432d9910d4b79b4dbbaa", calls.single().toolCall.toolCallId)
        // The reference's Groq model reads `x_groq.usage` ALONE and reports no usage for this stream;
        // the compat engine reads the `usage` block every chunk carries as well, so the count survives.
        val finish = assertIs<StreamPart.Finish>(parts.last())
        assertEquals(226, finish.usage.inputTokens.total)
        assertEquals(20, finish.usage.outputTokens.total)
    }

    @Test
    fun `raw chunks precede the parts they produced, and the tail's x_groq usage is read`() = runTest {
        val server = sse(
            listOf(
                """{"id":"chatcmpl-123","object":"chat.completion.chunk","created":1234567890,"model":"gemma2-9b-it","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}""",
                """{"id":"chatcmpl-456","object":"chat.completion.chunk","created":1234567890,"model":"gemma2-9b-it","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}""",
                """{"id":"chatcmpl-789","object":"chat.completion.chunk","created":1234567890,"model":"gemma2-9b-it","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"x_groq":{"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}}""",
            ),
        )

        val parts = model(server).doStream(call.copy(includeRawChunks = true)).stream.toList()

        assertEquals(
            listOf("StreamStart", "Raw", "ResponseMetadataPart", "TextStart", "TextDelta", "Raw", "TextDelta", "Raw", "TextEnd", "Finish"),
            parts.map { it::class.simpleName },
        )
        assertEquals("chatcmpl-123", (parts[1] as StreamPart.Raw).value.jsonObject["id"].string())
        assertEquals(1_234_567_890_000L, (parts[2] as StreamPart.ResponseMetadataPart).metadata.timestamp)
        val finish = assertIs<StreamPart.Finish>(parts.last())
        assertEquals(10, finish.usage.inputTokens.total)
        assertEquals(5, finish.usage.outputTokens.total)
    }

    @Test
    fun `an error frame on a 200 is a retryable error part and an error finish`() = runTest {
        val server = sse(listOf("""{"error":{"message":"Rate limit reached","type":"rate_limit_error"}}"""))

        val parts = model(server).doStream(call).stream.toList()

        val error = assertIs<APICallError>(assertIs<StreamPart.Error>(parts[1]).error)
        assertEquals("Rate limit reached", error.message)
        assertTrue(error.isRetryable)
        assertEquals("rate_limit_error", error.data?.jsonObject?.obj("error")?.get("type").string())
        assertEquals(FinishReason(FinishReason.Unified.Error), assertIs<StreamPart.Finish>(parts.last()).finishReason)
    }

    @Test
    fun `an unparsable frame is a parse error, not a silently shorter answer`() = runTest {
        val server = sse(listOf("{unparsable}"))

        val parts = model(server).doStream(call).stream.toList()

        val error = assertIs<JsonParseError>(assertIs<StreamPart.Error>(parts[1]).error)
        assertTrue(error.message!!.startsWith("JSON parsing failed: Text: {unparsable}."))
        assertEquals(FinishReason.Unified.Error, assertIs<StreamPart.Finish>(parts.last()).finishReason.unified)
    }

    @Test
    fun `an unknown finish reason is kept verbatim beside other`() = runTest {
        val server = sse(listOf("""{"id":"c","choices":[{"index":0,"delta":{"content":""},"finish_reason":"eos"}]}"""))

        val finish = model(server).doStream(call).stream.toList().filterIsInstance<StreamPart.Finish>().single()

        assertEquals(FinishReason(FinishReason.Unified.Other, "eos"), finish.finishReason)
    }

    @Test
    fun `the response headers reach the assembled result`() = runTest {
        val server = TestServer(
            TestServer.sse(GroqFixtures.textChunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")
                .withHeaders("test-header" to "test-value"),
        )

        val result = model(server).doGenerate(call)

        assertEquals("test-value", result.response?.headers?.get("test-header"))
        assertEquals(662, result.usage.outputTokens.total)
    }

    // --- the request ------------------------------------------------------------------------------------

    @Test
    fun `Groq's own options reach the wire verbatim, under the names Groq reads`() = runTest {
        // The reference renames `reasoningFormat` to `reasoning_format` on the way out. This port spreads
        // the vendor namespace verbatim (TODO.md §8), so the caller writes the wire's own spelling.
        val server = sse(GroqFixtures.toolCallChunks)

        model(server).doGenerate(
            call.copy(
                providerOptions = mapOf(
                    "groq" to buildJsonObject {
                        put("reasoning_format", "hidden")
                        put("user", "test-user-id")
                        put("parallel_tool_calls", false)
                        put("service_tier", "flex")
                    },
                ),
                headers = mapOf("Custom-Request-Header" to "request-header-value"),
            ),
        )

        val request = server.request()
        request.assertHeader("Custom-Request-Header", "request-header-value")
        val body = request.bodyJson()
        assertEquals("hidden", body["reasoning_format"].string())
        assertEquals("test-user-id", body["user"].string())
        assertEquals(false, body["parallel_tool_calls"].bool())
        assertEquals("flex", body["service_tier"].string())
    }

    @Test
    fun `the neutral effort is spelled in Groq's vocabulary, which has no minimal`() = runTest {
        val server = sse(GroqFixtures.toolCallChunks)
        val model = model(server, "openai/gpt-oss-120b")

        model.doGenerate(call.copy(reasoning = ReasoningEffort.High))
        assertEquals("high", server.request(0).bodyJson()["reasoning_effort"].string())

        // Groq's reasoning page lists low/medium/high; `minimal` is OpenAI's word and a 400 here. The
        // reference coerces it to `low`, and so does the row.
        model.doGenerate(call.copy(reasoning = ReasoningEffort.Minimal))
        assertEquals("low", server.request(1).bodyJson()["reasoning_effort"].string())

        model.doGenerate(call.copy(reasoning = ReasoningEffort.XHigh))
        assertEquals("high", server.request(2).bodyJson()["reasoning_effort"].string())

        // `none` is model-specific on Groq (Qwen 3.x only) and the engine never sends it; the field is
        // simply absent, where the reference sends it for one model family and warns for the others.
        model.doGenerate(call.copy(reasoning = ReasoningEffort.None))
        server.request(3).assertBodyMissing("reasoning_effort")

        // A verbatim option outranks the neutral enum.
        model.doGenerate(
            call.copy(
                reasoning = ReasoningEffort.Medium,
                providerOptions = mapOf("groq" to buildJsonObject { put("reasoning_effort", "high") }),
            ),
        )
        assertEquals("high", server.request(4).bodyJson()["reasoning_effort"].string())
    }

    @Test
    fun `json_schema goes out on the models Groq documents for it and is downgraded elsewhere`() = runTest {
        val server = sse(GroqFixtures.toolCallChunks)
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("value", buildJsonObject { put("type", "string") }) })
        }
        val format = ResponseFormat.Json(schema = schema, name = "test-name", description = "test description")

        val onListed = model(server, "openai/gpt-oss-120b").doGenerate(call.copy(responseFormat = format))
        val sent = server.request(0).bodyJson().obj("response_format")!!
        assertEquals("json_schema", sent["type"].string())
        assertEquals("test-name", sent.obj("json_schema")?.get("name").string())
        assertEquals("test description", sent.obj("json_schema")?.get("description").string())
        assertEquals(true, sent.obj("json_schema")?.get("strict").bool())
        assertEquals(schema, sent.obj("json_schema", "schema"))
        assertTrue(onListed.warnings.isEmpty())

        // The reference sends json_schema to gemma2 too; Groq's structured-outputs page says only four
        // models implement it and the rest get "JSON Object Mode ... though it may not match your
        // schema". The vendor wins: json_object, and a warning that says what was lost.
        val elsewhere = model(server, "gemma2-9b-it").doGenerate(call.copy(responseFormat = format))
        assertEquals("json_object", server.request(1).bodyJson().obj("response_format")?.get("type").string())
        elsewhere.warnings.assertUnsupported(
            "responseFormat",
            "JSON response format schema is only supported with structuredOutputs",
        )
    }

    @Test
    fun `tools and a pinned tool choice go out in OpenAI's spelling`() = runTest {
        val server = sse(GroqFixtures.toolCallChunks)
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("value", buildJsonObject { put("type", "string") }) })
        }

        model(server).doGenerate(
            call.copy(tools = listOf(Tool.Function("test-tool", schema)), toolChoice = ToolChoice.Specific("test-tool")),
        )

        val body = server.request().bodyJson()
        val tool = body.arr("tools")!!.single().jsonObject
        assertEquals("function", tool["type"].string())
        assertEquals("test-tool", tool.obj("function")?.get("name").string())
        assertEquals(schema, tool.obj("function", "parameters"))
        assertEquals("function", body.obj("tool_choice")?.get("type").string())
        assertEquals("test-tool", body.obj("tool_choice", "function")?.get("name").string())
    }

    @Test
    fun `browser_search reaches the wire through the row as a bare type entry`() = runTest {
        val server = sse(GroqFixtures.textChunks)

        model(server, "openai/gpt-oss-120b").doGenerate(call.copy(tools = listOf(GroqTools.browserSearch())))

        val tools = server.request().bodyJson().arr("tools")!!
        assertEquals(1, tools.size)
        assertEquals("browser_search", tools.single().jsonObject["type"].string())
        assertNull(tools.single().jsonObject["function"])
    }

    // --- ai@7.0.102 -----------------------------------------------------------------------------

    @Test
    fun `reasoning stays one block across deltas that carry an empty tool_calls array`() = runTest {
        // The reference's fixture (`0096850`), on Groq's `reasoning` channel.
        val server = sse(
            listOf(
                """{"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"test-model",""" +
                    """"choices":[{"index":0,"delta":{"role":"assistant","content":"","reasoning":"Think ","tool_calls":[]},""" +
                    """"finish_reason":null}]}""",
                """{"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"test-model",""" +
                    """"choices":[{"index":0,"delta":{"content":"","reasoning":"more...","tool_calls":[]},""" +
                    """"finish_reason":null}]}""",
                """{"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"test-model",""" +
                    """"choices":[{"index":0,"delta":{"content":"Hello","reasoning":"","tool_calls":[]},""" +
                    """"finish_reason":"stop"}]}""",
            ),
        )

        val parts = model(server).doStream(call).stream.toList()

        assertEquals(
            listOf("start reasoning-0", "delta reasoning-0 Think ", "delta reasoning-0 more...", "end reasoning-0"),
            parts.reasoningTrace(),
        )
    }
}
