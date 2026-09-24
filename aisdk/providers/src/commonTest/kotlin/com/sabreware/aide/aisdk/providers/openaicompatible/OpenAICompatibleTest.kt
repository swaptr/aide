package com.sabreware.aide.aisdk.providers.openaicompatible

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.JsonParseError
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The widest-surface provider: OpenAI, Ollama, LM Studio, vLLM, Groq, DeepSeek, OpenRouter.
 *
 * The reasoning tests are the ones that matter. The ecosystem has three channels and a client handling
 * only one is broken against the other two — which is exactly the state Koog left AIDE in, where
 * `reasoning_content` was dropped outright and inline tag-splitting was the only path that worked.
 */
class OpenAICompatibleTest {

    private var lastRequest: HttpRequestData? = null

    private fun model(
        sse: String,
        providerId: String = "openai",
        modelId: String = "gpt-4o",
        inlineReasoning: Boolean = false,
        unsupported: Set<SamplerParam> = emptySet(),
    ): OpenAICompatibleLanguageModel {
        val engine = MockEngine { request ->
            lastRequest = request
            respond(content = sse, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        return OpenAICompatibleLanguageModel(
            provider = providerId,
            modelId = modelId,
            http = ProviderHttp(HttpClient(engine)),
            chatUrl = "https://example.invalid/v1/chat/completions",
            extractInlineReasoning = inlineReasoning,
            capabilities = OpenAICompatibleCapabilities(unsupportedSamplers = unsupported),
        )
    }

    private val call = CallOptions(prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi")))))

    private fun sentBody(): JsonObject =
        parseJsonObject((lastRequest!!.body as TextContent).text)

    private val eventStream = headersOf(HttpHeaders.ContentType, "text/event-stream")

    private fun chunks(vararg objects: String) =
        objects.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    // --- tool calls -----------------------------------------------------------------------------

    @Test
    fun `index-correlated tool fragments concatenate into one call`() = runTest {
        val sse = chunks(
            """{"id":"c1","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"lookup","arguments":"{\"q\":"}}]}}]}""",
            """{"id":"c1","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"cats\"}"}}]}}]}""",
            """{"id":"c1","choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        )

        val parts = model(sse).doStream(call).stream.toList()

        val toolCall = parts.filterIsInstance<StreamPart.ToolCallPart>().single().toolCall
        assertEquals("call_a", toolCall.toolCallId)
        assertEquals("lookup", toolCall.toolName)
        // Fragments concatenate; a client that treats them as cumulative doubles the arguments.
        assertEquals("""{"q":"cats"}""", toolCall.input)
    }

    @Test
    fun `two tool calls stay separated by index`() = runTest {
        val sse = chunks(
            """{"id":"c","choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"one","arguments":"{}"}}]}}]}""",
            """{"id":"c","choices":[{"delta":{"tool_calls":[{"index":1,"id":"b","function":{"name":"two","arguments":"{}"}}]}}]}""",
            """{"id":"c","choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        )

        val calls = model(sse).doStream(call).stream.toList()
            .filterIsInstance<StreamPart.ToolCallPart>().map { it.toolCall }

        assertEquals(listOf("one", "two"), calls.map { it.toolName })
    }

    @Test
    fun `a call with no arguments still replays as valid JSON`() = runTest {
        val sse = chunks(
            """{"id":"c","choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"now"}}]}}]}""",
            """{"id":"c","choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        )

        val toolCall = model(sse).doStream(call).stream.toList()
            .filterIsInstance<StreamPart.ToolCallPart>().single().toolCall

        assertEquals("{}", toolCall.input)
    }

    // --- reasoning channel 1: reasoning_content -------------------------------------------------

    @Test
    fun `reasoning_content becomes a reasoning block, not text`() = runTest {
        val sse = chunks(
            """{"id":"c","choices":[{"delta":{"reasoning_content":"Let me think. "}}]}""",
            """{"id":"c","choices":[{"delta":{"reasoning_content":"Done."}}]}""",
            """{"id":"c","choices":[{"delta":{"content":"42"},"finish_reason":"stop"}]}""",
        )

        val result = assembleGenerateResult(model(sse, modelId = "deepseek-r1").doStream(call).stream)

        // This is what Koog dropped entirely on the Completions path.
        assertEquals("Let me think. Done.", (result.content[0] as Content.Reasoning).text)
        assertEquals("42", (result.content[1] as Content.Text).text)
    }

    @Test
    fun `reasoning_content survives a full round trip, not just the display`() = runTest {
        val sse = chunks(
            """{"id":"c","choices":[{"delta":{"reasoning_content":"Let me think."}}]}""",
            """{"id":"c","choices":[{"delta":{"content":"42"},"finish_reason":"stop"}]}""",
        )

        val turn = assembleGenerateResult(
            model(sse, providerId = "deepseek", modelId = "deepseek-r1").doStream(call).stream,
        )
        val reasoning = turn.content[0] as Content.Reasoning
        val replayed = listOf(
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.Reasoning(reasoning.text, providerOptions = reasoning.providerMetadata),
                    AssistantPart.Text("42"),
                ),
            ),
        ).toOpenAIMessages("deepseek").single()

        // The key was READ on replay and written nowhere, so DeepSeek-R1, Moonshot, Alibaba and xAI
        // reasoning was display-only: visible in the UI and absent from the conversation the model sees.
        assertEquals(
            "Let me think.",
            reasoning.providerMetadata?.get("deepseek")?.get(REASONING_CONTENT_KEY)?.jsonPrimitive?.content,
        )
        assertEquals("Let me think.", replayed.reasoningContent)
    }

    // --- Gemini's thought_signature, as it rides this wire ---------------------------------------

    @Test
    fun `a Gemini thought_signature is carried per call and replayed with it`() = runTest {
        val signature = "CkYBVKhc7uZ0="
        val sse = chunks(
            """{"id":"c","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function",""" +
                """"function":{"name":"lookup","arguments":"{}"},""" +
                """"extra_content":{"google":{"thought_signature":"$signature"}}}]}}]}""",
            """{"id":"c","choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        )

        val turn = assembleGenerateResult(model(sse, providerId = "openrouter").doStream(call).stream)
        val toolCall = turn.content.filterIsInstance<Content.ToolCall>().single()
        val replayed = listOf(
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.ToolCall(
                        toolCallId = toolCall.toolCallId,
                        toolName = toolCall.toolName,
                        input = toolCall.input,
                        providerOptions = toolCall.providerMetadata,
                    ),
                ),
            ),
        ).toOpenAIMessages("openrouter").single()

        // Gemini rejects the NEXT turn if a call is replayed without its signature, and anyone reaching
        // Gemini through OpenRouter, Vercel's gateway or a proxy is on this path — the defect
        // aisdk/DESIGN.md names as the reason this library exists, reproduced on the compatible wire.
        assertEquals(
            signature,
            toolCall.providerMetadata?.get("openrouter")?.get(THOUGHT_SIGNATURE_KEY)?.jsonPrimitive?.content,
        )
        assertEquals(signature, replayed.toolCalls?.single()?.extraContent?.google?.thoughtSignature)
    }

    // --- reasoning channel 2: reasoning_details (OpenRouter) ------------------------------------

    @Test
    fun `reasoning_details are carried opaquely and replayed unmodified`() = runTest {
        val signature = "sha256:abc123def456"
        val sse = chunks(
            """{"id":"c","choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"thinking","signature":"$signature","format":"anthropic-claude-v1","index":0}]}}]}""",
            """{"id":"c","choices":[{"delta":{"content":"answer"},"finish_reason":"stop"}]}""",
        )

        val result = assembleGenerateResult(
            model(sse, providerId = "openrouter").doStream(call).stream,
        )

        val reasoning = result.content[0] as Content.Reasoning
        val details = reasoning.providerMetadata?.get("openrouter")?.get(REASONING_DETAILS_KEY)?.jsonObject
        assertNotNull(details, "reasoning_details were dropped")
        // The signature survives verbatim — rebuilding these blocks is what corrupts them in other clients.
        assertEquals(signature, details["signature"]?.jsonPrimitive?.content)
        assertEquals("anthropic-claude-v1", details["format"]?.jsonPrimitive?.content)
        assertEquals("thinking", reasoning.text)
    }

    @Test
    fun `a stored reasoning_details block goes back on the wire untouched`() {
        val block = buildJsonObject {
            put("type", "reasoning.text")
            put("text", "thinking")
            put("signature", "sha256:abc123def456")
            put("format", "anthropic-claude-v1")
        }
        val prompt = listOf(
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.Reasoning(
                        text = "thinking",
                        providerOptions = mapOf(
                            "openrouter" to buildJsonObject { put(REASONING_DETAILS_KEY, block) },
                        ),
                    ),
                    AssistantPart.Text("answer"),
                ),
            ),
        )

        val message = prompt.toOpenAIMessages("openrouter").single()

        assertEquals(listOf(block), message.reasoningDetails)
    }

    // --- Mistral's shape: content as an array of typed parts -------------------------------------

    @Test
    fun `Magistral streams content as an array of parts and both channels survive`() = runTest {
        // The reference's own recorded wire (mistral-reasoning.chunks.txt), verbatim. `delta.content`
        // was typed as String?, so kotlinx threw on every content chunk of a Magistral turn and the
        // failure was swallowed: reasoning and answer both vanished, and the caller got empty content,
        // no error and no warning.
        val sse = chunks(
            """{"id":"a4e29c5b82f94d67b23e108a7c9df6e1","object":"chat.completion.chunk","created":1769088912,"model":"magistral-medium-2507","choices":[{"index":0,"delta":{"role":"assistant","content":[{"type":"thinking","thinking":[{"type":"text","text":"The user is asking"}]}]},"finish_reason":null}]}""",
            """{"id":"a4e29c5b82f94d67b23e108a7c9df6e1","object":"chat.completion.chunk","created":1769088912,"model":"magistral-medium-2507","choices":[{"index":0,"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":" for 2+2. This is basic arithmetic. 2+2=4."}]}]},"finish_reason":null}]}""",
            """{"id":"a4e29c5b82f94d67b23e108a7c9df6e1","object":"chat.completion.chunk","created":1769088912,"model":"magistral-medium-2507","choices":[{"index":0,"delta":{"content":[{"type":"text","text":"2 + 2 = 4"}]},"finish_reason":null}]}""",
            """{"id":"a4e29c5b82f94d67b23e108a7c9df6e1","object":"chat.completion.chunk","created":1769088912,"model":"magistral-medium-2507","choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"total_tokens":56,"completion_tokens":46}}""",
        )

        val parts = model(sse, providerId = "mistral", modelId = "magistral-medium-2507")
            .doStream(call).stream.toList()
        val result = assembleGenerateResult(parts.asFlow())

        assertEquals(
            "The user is asking for 2+2. This is basic arithmetic. 2+2=4.",
            (result.content[0] as Content.Reasoning).text,
        )
        assertEquals("2 + 2 = 4", (result.content[1] as Content.Text).text)
        assertEquals(FinishReason.Unified.Stop, result.finishReason.unified)

        // `created` is epoch SECONDS on this wire and epoch MILLISECONDS in the contract; passing it
        // through put every timestamp in January 1970.
        val metadata = parts.filterIsInstance<StreamPart.ResponseMetadataPart>().first().metadata
        assertEquals(1_769_088_912_000L, metadata.timestamp)
    }

    // --- reasoning channel 3: inline tags -------------------------------------------------------

    @Test
    fun `inline think tags split out when enabled`() = runTest {
        val sse = chunks(
            """{"id":"c","choices":[{"delta":{"content":"<think>reasoning here</think>the answer"},"finish_reason":"stop"}]}""",
        )

        val result = assembleGenerateResult(model(sse, inlineReasoning = true).doStream(call).stream)

        assertEquals("reasoning here", (result.content[0] as Content.Reasoning).text)
        assertEquals("the answer", (result.content[1] as Content.Text).text)
    }

    @Test
    fun `a tag split across chunks is not emitted as text`() = runTest {
        // The failure this guards: "<thi" flushed as text before the next chunk completes the tag.
        val sse = chunks(
            """{"id":"c","choices":[{"delta":{"content":"<thi"}}]}""",
            """{"id":"c","choices":[{"delta":{"content":"nk>hidden</think>shown"},"finish_reason":"stop"}]}""",
        )

        val result = assembleGenerateResult(model(sse, inlineReasoning = true).doStream(call).stream)

        assertEquals("hidden", (result.content[0] as Content.Reasoning).text)
        assertEquals("shown", (result.content[1] as Content.Text).text)
    }

    @Test
    fun `tags are left alone when splitting is off`() = runTest {
        val sse = chunks(
            """{"id":"c","choices":[{"delta":{"content":"<think>x</think>y"},"finish_reason":"stop"}]}""",
        )

        val result = assembleGenerateResult(model(sse).doStream(call).stream)

        // Off by default: a model that legitimately writes "<think>" must keep it.
        assertEquals("<think>x</think>y", (result.content.single() as Content.Text).text)
    }

    // --- request shaping ------------------------------------------------------------------------

    @Test
    fun `params a model rejects are dropped with a warning, not sent`() = runTest {
        val sse = chunks("""{"id":"c","choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}""")
        val gpt5 = model(
            sse,
            modelId = "gpt-5",
            unsupported = setOf(
                SamplerParam.Temperature,
                SamplerParam.TopP,
            ),
        )

        val parts = gpt5.doStream(call.copy(temperature = 0.7, topP = 0.9)).stream.toList()

        // Sending them is a 400; sending nothing silently is a mystery. Warn and drop.
        assertNull(sentBody()["temperature"])
        assertNull(sentBody()["top_p"])
        val warnings = parts.filterIsInstance<StreamPart.StreamStart>().single().warnings
        assertEquals(2, warnings.size)
    }

    @Test
    fun `tool_choice is omitted when there are no tools`() = runTest {
        val sse = chunks("""{"id":"c","choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}""")

        model(sse).doStream(call.copy(toolChoice = com.sabreware.aide.aisdk.ToolChoice.Required))
            .stream.toList()

        // A tool_choice with no tools is a 400 on every vendor.
        assertNull(sentBody()["tool_choice"])
    }

    @Test
    fun `tool_choice is sent when tools are present`() = runTest {
        val sse = chunks("""{"id":"c","choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}""")
        val tools = listOf(Tool.Function(name = "t", inputSchema = buildJsonObject { }))

        model(sse).doStream(
            call.copy(tools = tools, toolChoice = com.sabreware.aide.aisdk.ToolChoice.Required),
        ).stream.toList()

        assertEquals("required", sentBody()["tool_choice"]?.jsonPrimitive?.content)
        assertEquals("t", sentBody()["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a text-only user turn sends a bare string, not an array`() = runTest {
        val sse = chunks("""{"id":"c","choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}""")

        model(sse).doStream(call).stream.toList()

        // Older llama.cpp builds and some Ollama versions reject the array form for text-only messages.
        val content = sentBody()["messages"]!!.jsonArray[0].jsonObject["content"]
        assertEquals("hi", content?.jsonPrimitive?.content)
    }

    @Test
    fun `usage separates reasoning tokens from text tokens`() = runTest {
        val sse = chunks(
            """{"id":"c","choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}""",
            """{"id":"c","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":100,"completion_tokens_details":{"reasoning_tokens":70},"prompt_tokens_details":{"cached_tokens":4}}}""",
        )

        val finish = model(sse).doStream(call).stream.toList()
            .filterIsInstance<StreamPart.Finish>().single()

        assertEquals(10, finish.usage.inputTokens.total)
        assertEquals(4, finish.usage.inputTokens.cacheRead)
        assertEquals(100, finish.usage.outputTokens.total)
        assertEquals(70, finish.usage.outputTokens.reasoning)
        assertEquals(30, finish.usage.outputTokens.text)
    }

    @Test
    fun `citations become sources once each, however often the vendor repeats them`() = runTest {
        // Perplexity repeats the whole list on every chunk; xAI sends it once, on the last. Both shapes
        // are here, because the de-duplication is what lets one reader serve them.
        val sse = chunks(
            """{"id":"c","citations":["https://a.example","https://b.example"],"choices":[{"delta":{"content":"The "}}]}""",
            """{"id":"c","citations":["https://a.example","https://b.example"],"choices":[{"delta":{"content":"answer."}}]}""",
            """{"id":"c","citations":["https://a.example","https://c.example"],"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )

        val sources = model(sse).doStream(call).stream.toList()
            .filterIsInstance<StreamPart.SourcePart>().map { it.source }

        // On Perplexity the citations ARE the product; dropped, the answer is prose with nothing behind
        // it. Emitted per chunk instead, a three-sentence answer cites the same page six times.
        assertEquals(
            listOf("https://a.example", "https://b.example", "https://c.example"),
            sources.filterIsInstance<Content.Source.Url>().map { it.url },
        )
        assertEquals(3, sources.map { it.id }.distinct().size)
    }

    @Test
    fun `finish reason keeps the vendor string alongside the normalized one`() = runTest {
        val sse = chunks("""{"id":"c","choices":[{"delta":{},"finish_reason":"content_filter"}]}""")

        val finish = model(sse).doStream(call).stream.toList()
            .filterIsInstance<StreamPart.Finish>().single()

        assertEquals(FinishReason.Unified.ContentFilter, finish.finishReason.unified)
        assertEquals("content_filter", finish.finishReason.raw)
    }

    @Test
    fun `the DONE sentinel ends the stream`() = runTest {
        val sse = chunks("""{"id":"c","choices":[{"delta":{"content":"kept"},"finish_reason":"stop"}]}""") +
            "data: {\"id\":\"c\",\"choices\":[{\"delta\":{\"content\":\"after done\"}}]}\n\n"

        val result = assembleGenerateResult(model(sse).doStream(call).stream)

        assertEquals("kept", (result.content.single() as Content.Text).text)
    }

    // --- failure that used to present as success -------------------------------------------------
    //
    // Three ways a generation can fail on an HTTP 200, all of which used to arrive as a short but
    // otherwise ordinary answer: nothing above could tell them from a model that simply stopped early,
    // so nothing retried and nothing told the user. Each now ends the turn as an error.

    @Test
    fun `an unparsable frame ends the turn as an error`() = runTest {
        val sse = "data: {not json}\n\n" +
            chunks("""{"id":"c","choices":[{"delta":{"content":"survived"},"finish_reason":"stop"}]}""")

        val parts = model(sse).doStream(call).stream.toList()

        // An upstream 502 on an aggregator and an injected HTML error page both land here.
        assertTrue(parts.filterIsInstance<StreamPart.Error>().single().error is JsonParseError)
        // Latched: the `stop` on the frame that follows must not talk the turn back into success.
        assertEquals(
            FinishReason.Unified.Error,
            parts.filterIsInstance<StreamPart.Finish>().single().finishReason.unified,
        )
    }

    @Test
    fun `an error frame on a 200 stream ends the turn as an error`() = runTest {
        // A rate limit hit mid-generation arrives exactly like this: HTTP 200, then an error object no
        // retry policy has seen. Under ignoreUnknownKeys it decoded to an empty chunk and vanished.
        val sse = chunks("""{"error":{"message":"Rate limit reached","type":"rate_limit_error"}}""")

        val parts = model(sse).doStream(call).stream.toList()

        val error = parts.filterIsInstance<StreamPart.Error>().single().error as APICallError
        assertEquals(true, error.message?.contains("Rate limit reached"))
        // Retryable, which is the whole point of naming it: the caller can send the turn again.
        assertTrue(error.isRetryable)
    }

    @Test
    fun `a stream that ends without a finish reason ends the turn as an error`() = runTest {
        // A dropped socket closes the stream cleanly. Reporting `other` here is what made a truncated
        // answer look complete.
        val sse = "data: {\"id\":\"c\",\"choices\":[{\"delta\":{\"content\":\"half an ans\"}}]}\n\n"

        val parts = model(sse).doStream(call).stream.toList()

        assertTrue(parts.filterIsInstance<StreamPart.Error>().single().error is InvalidResponseDataError)
        assertEquals(
            FinishReason.Unified.Error,
            parts.filterIsInstance<StreamPart.Finish>().single().finishReason.unified,
        )
        // The partial text is still delivered — the caller keeps what arrived AND learns it is partial.
        assertEquals(
            "half an ans",
            parts.filterIsInstance<StreamPart.TextDelta>().joinToString("") { it.delta },
        )
    }

    @Test
    fun `a keyless local server gets no Authorization header`() = runTest {
        val engine = MockEngine { request ->
            lastRequest = request
            respond(
                content = chunks("""{"id":"c","choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}"""),
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val ollama = OpenAICompatibleProvider(
            client = HttpClient(engine),
            providerId = "ollama",
            baseUrl = "http://localhost:11434/v1",
        )

        ollama.languageModel("llama3")!!.doStream(call).stream.toList()

        // Sending an empty bearer token is how a working keyless setup starts returning 401.
        assertNull(lastRequest!!.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `reasoning families are detected by parsed version, not by prefix`() {
        assertTrue(defaultCapabilities("gpt-5").isReasoningModel)
        assertTrue(defaultCapabilities("o3-mini").isReasoningModel)
        // Prefix matching gets all three of these wrong, and each wrong answer is a 400 or a silently
        // stripped sampler rather than a visible mistake.
        assertFalse(defaultCapabilities("gpt-4o").isReasoningModel)
        assertFalse(defaultCapabilities("gpt-5-chat-latest").isReasoningModel)
        assertTrue(defaultCapabilities("o5").isReasoningModel, "an unshipped o-series is still o-series")
        // gpt-5.1 accepts samplers at effort `none`; gpt-5 rejects them at every effort.
        assertTrue(defaultCapabilities("gpt-5.1").supportsNonReasoningParameters)
        assertFalse(defaultCapabilities("gpt-5").supportsNonReasoningParameters)
    }

    // --- cerebras structured-output correction --------------------------------------------------

    @Test
    fun `a structured answer with a repeated tool call is the final answer, not a paused turn`() = runTest {
        val sse = chunks(
            """{"id":"c1","choices":[{"delta":{"content":"{\"answer\":42}"}}]}""",
            """{"id":"c1","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"lookup","arguments":"{}"}}]}}]}""",
            """{"id":"c1","choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        )
        val engine = MockEngine { request ->
            lastRequest = request
            respond(content = sse, headers = eventStream)
        }
        val corrected = OpenAICompatibleLanguageModel(
            provider = "cerebras",
            modelId = "glm-4",
            http = ProviderHttp(HttpClient(engine)),
            chatUrl = "https://example.invalid/v1/chat/completions",
            jsonToolCallsFinishIsStop = true,
        )

        val parts = corrected.doStream(
            call.copy(responseFormat = com.sabreware.aide.aisdk.ResponseFormat.Json()),
        ).stream.toList()

        // The repeated call is suppressed, and a loop keyed on the finish reason sees a finished turn.
        assertTrue(parts.filterIsInstance<StreamPart.ToolCallPart>().isEmpty(), parts.toString())
        val finish = parts.filterIsInstance<StreamPart.Finish>().single().finishReason
        assertEquals(com.sabreware.aide.aisdk.FinishReason.Unified.Stop, finish.unified)
        // The raw string is preserved: the correction is a unification, not a rewrite of the wire.
        assertEquals("tool_calls", finish.raw)
    }

    // --- ai@7.0.102 -----------------------------------------------------------------------------

    @Test
    fun `reasoning stays one block across deltas that carry an empty tool_calls array`() = runTest {
        // The reference's fixture (`e5a22f0`): a vendor that sends `tool_calls: []` on every delta,
        // which used to end the block on the first one and open a fresh block per chunk.
        val sse = chunks(
            """{"id":"chatcmpl-test","object":"chat.completion.chunk","model":"test-model",""" +
                """"choices":[{"index":0,"delta":{"role":"assistant","content":"","reasoning_content":"Think ","tool_calls":[]},""" +
                """"finish_reason":null}]}""",
            """{"id":"chatcmpl-test","object":"chat.completion.chunk","model":"test-model",""" +
                """"choices":[{"index":0,"delta":{"content":"","reasoning_content":"more...","tool_calls":[]},""" +
                """"finish_reason":null}]}""",
            """{"id":"chatcmpl-test","object":"chat.completion.chunk","model":"test-model",""" +
                """"choices":[{"index":0,"delta":{"content":"Hello","reasoning_content":"","tool_calls":[]},""" +
                """"finish_reason":"stop"}]}""",
        )

        val parts = model(sse).doStream(call).stream.toList()

        assertEquals(
            listOf("start reasoning-0", "delta reasoning-0 Think ", "delta reasoning-0 more...", "end reasoning-0"),
            parts.reasoningTrace(),
        )
    }

    @Test
    fun `a response with no choices is a typed error, not a crash or a silent empty answer`() = runTest {
        // The reference's document (`ccb8952`), framed as the one chunk an always-streaming doGenerate sees.
        val sse = chunks(
            """{"id":"chatcmpl-empty","object":"chat.completion","created":1711115037,"model":"grok-3",""" +
                """"choices":[],"usage":{"prompt_tokens":4,"total_tokens":4,"completion_tokens":0}}""",
        )

        val error = assertFailsWith<InvalidResponseDataError> { model(sse).doGenerate(call) }

        assertEquals("Response did not contain any choices.", error.message)
    }
}

/** The reasoning parts of a stream, flattened so a split block reads as two starts. */
internal fun List<StreamPart>.reasoningTrace(): List<String> = mapNotNull { part ->
    when (part) {
        is StreamPart.ReasoningStart -> "start ${part.id}"
        is StreamPart.ReasoningDelta -> "delta ${part.id} ${part.delta}"
        is StreamPart.ReasoningEnd -> "end ${part.id}"
        else -> null
    }
}
