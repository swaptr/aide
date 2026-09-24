package com.sabreware.aide.aisdk.providers.zai

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.bool
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Z.AI, ported from the reference's `zai-chat-language-model.test.ts` / `zai-provider.test.ts`.
 *
 * The fixture bodies are the reference's own, reshaped only where our compat model always streams:
 * the reference's non-streaming response document becomes one SSE frame carrying the same delta.
 */
class ZaiTest {

    private fun provider(server: TestServer, headers: Map<String, String> = emptyMap()) = ZaiProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-key",
        extraHeaders = headers,
    )

    private val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Hello"))))

    private val calculator = Tool.Function(
        name = "calculator",
        inputSchema = buildJsonObject { put("type", "object") },
        description = "Calculate a value",
    )

    /** The reference's SUCCESS_RESPONSE, as the one streamed frame our always-streaming wire reads. */
    private fun successServer() = TestServer(
        TestServer.sse(
            """data: {"id":"chatcmpl-123","created":1777000000,"model":"glm-5.3","choices":[{"index":0,""" +
                """"delta":{"role":"assistant","content":"The answer is 42.","reasoning_content":""" +
                """"I should calculate the answer.","tool_calls":[{"index":0,"id":"call-1","type":"function",""" +
                """"function":{"name":"calculator","arguments":"{\"value\":42}"}}]},""" +
                """"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":7,""" +
                """"prompt_tokens_details":{"cached_tokens":3},"total_tokens":17}}""" + "\n\n" +
                "data: [DONE]\n\n",
        ),
    )

    private fun finishServer(reason: String) = TestServer(
        TestServer.sse(
            """data: {"id":"c","created":1,"model":"glm-5.3","choices":[{"delta":{"role":"assistant"},""" +
                """"finish_reason":"$reason"}]}""" + "\n\n" +
                "data: [DONE]\n\n",
        ),
    )

    @Test
    fun `zai options translate to the wire and the unsupported samplers drop with warnings`() = runTest {
        val server = successServer()

        val result = provider(server).languageModel("glm-5.3").doGenerate(
            CallOptions(
                prompt = prompt,
                frequencyPenalty = 0.2,
                presencePenalty = 0.3,
                seed = 42,
                toolChoice = ToolChoice.Required,
                tools = listOf(calculator),
                providerOptions = mapOf(
                    ZAI_PROVIDER_ID to buildJsonObject {
                        put("doSample", false)
                        putJsonObject("thinking") {
                            put("type", "enabled")
                            put("clearThinking", false)
                        }
                        put("reasoningEffort", "max")
                        put("toolStream", true)
                        put("requestId", "request-123456")
                        put("userId", "user-123456")
                        put("ignoredOption", true)
                    },
                ),
            ),
        )

        val body = server.request().bodyJson()
        assertEquals("glm-5.3", body["model"].string())
        assertEquals(false, body["do_sample"].bool())
        assertEquals("enabled", body.obj("thinking")?.get("type").string())
        assertEquals(false, body.obj("thinking")?.get("clear_thinking").bool())
        assertEquals("max", body["reasoning_effort"].string())
        assertEquals(true, body["tool_stream"].bool())
        assertEquals("request-123456", body["request_id"].string())
        assertEquals("user-123456", body["user_id"].string())
        // The three samplers Z.AI rejects, the choice it cannot express, and the key it never documented.
        for (absent in listOf("frequency_penalty", "presence_penalty", "seed", "ignoredOption", "tool_choice")) {
            assertFalse(absent in body, "expected no `$absent` in the body")
        }
        assertTrue("tools" in body, "the tools themselves still go out; only the choice is dropped")

        result.warnings.assertUnsupported("frequencyPenalty", "This model rejects it; the value was dropped.")
        result.warnings.assertUnsupported("presencePenalty", "This model rejects it; the value was dropped.")
        result.warnings.assertUnsupported("seed", "This model rejects it; the value was dropped.")
        result.warnings.assertUnsupported(
            "toolChoice required",
            "Z.AI currently supports only automatic tool selection.",
        )
    }

    @Test
    fun `toolChoice none is expressed by omitting the tools entirely`() = runTest {
        val server = successServer()

        provider(server).languageModel("glm-5.3").doGenerate(
            CallOptions(prompt = prompt, toolChoice = ToolChoice.None, tools = listOf(calculator)),
        )

        val body = server.request().bodyJson()
        assertFalse("tools" in body)
        assertFalse("tool_choice" in body)
    }

    @Test
    fun `a requestId outside the documented length fails before any request`() = runTest {
        val server = successServer()

        assertFailsWith<InvalidArgumentError> {
            provider(server).languageModel("glm-5.3").doGenerate(
                CallOptions(
                    prompt = prompt,
                    providerOptions = mapOf(
                        ZAI_PROVIDER_ID to buildJsonObject { put("requestId", "short") },
                    ),
                ),
            )
        }
        assertEquals(0, server.callCount, "an invalid option must fail before the vendor sees it")
    }

    @Test
    fun `text, reasoning, tool calls, cached usage and the finish reason all map`() = runTest {
        val result = provider(successServer()).languageModel("glm-5.3")
            .doGenerate(CallOptions(prompt = prompt))

        // Arrival order: GLM's `reasoning_content` rides the same delta as the text and is read first.
        assertEquals(3, result.content.size)
        val reasoning = assertIs<Content.Reasoning>(result.content[0])
        assertEquals("I should calculate the answer.", reasoning.text)
        val text = assertIs<Content.Text>(result.content[1])
        assertEquals("The answer is 42.", text.text)
        val call = assertIs<Content.ToolCall>(result.content[2])
        assertEquals("call-1", call.toolCallId)
        assertEquals("calculator", call.toolName)
        assertEquals("""{"value":42}""", call.input)

        assertEquals(FinishReason(FinishReason.Unified.ToolCalls, raw = "tool_calls"), result.finishReason)
        assertEquals(10, result.usage.inputTokens.total)
        assertEquals(3, result.usage.inputTokens.cacheRead)
        assertEquals(7, result.usage.inputTokens.noCache)

        val metadata = result.response?.metadata
        assertEquals("chatcmpl-123", metadata?.id)
        assertEquals("glm-5.3", metadata?.modelId)
        assertEquals(1_777_000_000_000, metadata?.timestamp)
    }

    @Test
    fun `zai's finish vocabulary maps onto the unified reasons with the raw string kept`() = runTest {
        val cases = listOf(
            "sensitive" to FinishReason.Unified.ContentFilter,
            "model_context_window_exceeded" to FinishReason.Unified.Length,
            "network_error" to FinishReason.Unified.Error,
        )
        for ((raw, unified) in cases) {
            val parts = provider(finishServer(raw)).languageModel("glm-5.3")
                .doStream(CallOptions(prompt = prompt)).stream.toList()

            val finish = assertIs<StreamPart.Finish>(parts.last())
            assertEquals(FinishReason(unified, raw = raw), finish.finishReason, "raw `$raw`")
        }
    }

    @Test
    fun `the stream sends no stream_options and reads the bare usage chunk`() = runTest {
        val server = TestServer(
            TestServer.sse(
                """data: {"id":"s","created":1777000000,"model":"glm-5.3","choices":[{"delta":""" +
                    """{"role":"assistant","reasoning_content":"Think."},"finish_reason":null}]}""" + "\n\n" +
                    """data: {"id":"s","choices":[{"delta":{"content":"Answer."},"finish_reason":null}]}""" +
                    "\n\n" +
                    """data: {"id":"s","choices":[{"delta":{},"finish_reason":"stop"}]}""" + "\n\n" +
                    """data: {"id":"s","choices":[],"usage":{"prompt_tokens":4,"completion_tokens":3,""" +
                    """"total_tokens":7}}""" + "\n\n" +
                    "data: [DONE]\n\n",
            ),
        )

        val parts = provider(server).languageModel("glm-5.3").doStream(
            CallOptions(
                prompt = prompt,
                providerOptions = mapOf(ZAI_PROVIDER_ID to buildJsonObject { put("toolStream", true) }),
            ),
        ).stream.toList()

        val body = server.request().bodyJson()
        assertEquals(true, body["stream"].bool())
        assertEquals(true, body["tool_stream"].bool())
        // Z.AI reports usage unprompted; `stream_options` is not in its schema.
        assertFalse("stream_options" in body)

        val finish = assertIs<StreamPart.Finish>(parts.last())
        assertEquals(FinishReason(FinishReason.Unified.Stop, raw = "stop"), finish.finishReason)
        assertEquals(4, finish.usage.inputTokens.total)
        assertEquals(3, finish.usage.outputTokens.total)
    }

    @Test
    fun `both documented error envelopes surface the vendor's message`() = runTest {
        // Z.AI answers with either `{code, message}` at the top level or the same nested under `error`.
        val envelopes = listOf(
            """{"code":1001,"message":"Invalid request."}""",
            """{"error":{"code":1001,"message":"Invalid request."}}""",
        )
        for (envelope in envelopes) {
            val server = TestServer(TestServer.error(400, envelope))

            val error = assertFailsWith<APICallError> {
                provider(server).languageModel("glm-5.3").doGenerate(CallOptions(prompt = prompt))
            }
            // The house message keeps the status and URL around the vendor's own text.
            assertTrue(
                error.message.orEmpty().endsWith("Invalid request."),
                "envelope $envelope produced: ${error.message}",
            )
            assertEquals(400, error.statusCode)
        }
    }

    @Test
    fun `the provider wires the documented endpoint, bearer auth and extra headers`() = runTest {
        val server = successServer()

        provider(server, headers = mapOf("x-custom" to "value")).languageModel("glm-5.3")
            .doGenerate(CallOptions(prompt = prompt))

        val call = server.request()
        assertEquals("https://api.z.ai/api/paas/v4/chat/completions", call.url)
        assertEquals("Bearer test-key", call.header("Authorization"))
        assertEquals("value", call.header("x-custom"))
    }

    @Test
    fun `a custom base URL keeps its path and loses its trailing slash`() = runTest {
        val server = successServer()

        ZaiProvider(
            client = HttpClient(server.engine()),
            apiKey = "custom-key",
            baseUrl = "https://example.com/zai/",
        ).languageModel("glm-5.3").doGenerate(CallOptions(prompt = prompt))

        assertEquals("https://example.com/zai/chat/completions", server.request().url)
    }

    @Test
    fun `image and video URLs are declared fetchable so the runtime passes them through`() = runTest {
        val urls = provider(successServer()).languageModel("glm-5.3").supportedUrls()

        assertTrue(urls.getValue("image/*").single().containsMatchIn("https://example.com/a.png"))
        assertTrue(urls.getValue("video/*").single().containsMatchIn("http://example.com/a.mp4"))
    }

    @Test
    fun `modalities zai does not serve resolve to null rather than throwing`() {
        // The reference throws NoSuchModelError from these; the house contract says a provider with no
        // models of a kind returns null, and DESIGN.md documents the divergence.
        val provider = provider(successServer())

        assertNull(provider.embeddingModel("any"))
        assertNull(provider.imageModel("any"))
    }
}
