package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * The Gemini response channels that were read and thrown away.
 *
 * Each of these is a capability the caller wired up and never saw: an image the model generated, the
 * citations behind a grounded answer, the code it ran, and — the one that reports failure as success — a
 * mid-stream error frame.
 */
class GoogleResponseTest {

    private fun chunks(vararg objects: String) = objects.joinToString("") { "data: $it\n\n" }

    private fun model(sse: String, server: TestServer = TestServer(TestServer.sse(sse))) =
        GoogleLanguageModel(modelId = "gemini-3-pro", http = server.http())

    private val call = CallOptions(
        prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi")))),
        reasoning = ReasoningEffort.Medium,
    )

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `generated image output becomes a file part instead of vanishing`() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val sse = chunks(
            """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png",""" +
                """"data":"${Base64.encode(bytes)}"}}]}}]}""",
            """{"candidates":[{"finishReason":"STOP"}]}""",
        )

        val file = model(sse).doStream(call).stream.toList()
            .filterIsInstance<StreamPart.FilePart>().single().file

        assertEquals("image/png", file.mediaType)
        assertEquals(FileData.Bytes(bytes), file.data)
    }

    @Test
    fun `grounding chunks become sources, once each`() = runTest {
        // Grounding metadata is repeated on every chunk of the stream; a caller wants one citation.
        val grounding = """"groundingMetadata":{"groundingChunks":[""" +
            """{"web":{"uri":"https://example.com/a","title":"A"}},""" +
            """{"retrievedContext":{"uri":"gs://bucket/report.pdf","title":"Report"}}]}"""
        val sse = chunks(
            """{"candidates":[{$grounding,"content":{"parts":[{"text":"grounded"}]}}]}""",
            """{"candidates":[{$grounding,"finishReason":"STOP"}]}""",
        )

        val sources = model(sse).doStream(call).stream.toList()
            .filterIsInstance<StreamPart.SourcePart>().map { it.source }

        assertEquals(2, sources.size)
        val url = assertIs<Content.Source.Url>(sources[0])
        assertEquals("https://example.com/a", url.url)
        val document = assertIs<Content.Source.Document>(sources[1])
        assertEquals("application/pdf", document.mediaType)
        assertEquals("report.pdf", document.filename)
    }

    @Test
    fun `code the model ran is reported as a provider-executed call and its result`() = runTest {
        val sse = chunks(
            """{"candidates":[{"content":{"parts":[{"executableCode":{"language":"PYTHON",""" +
                """"code":"print(1)"}}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"codeExecutionResult":{"outcome":"OUTCOME_OK",""" +
                """"output":"1\n"}}]}}]}""",
            """{"candidates":[{"finishReason":"STOP"}]}""",
        )

        val parts = model(sse).doStream(call).stream.toList()

        val toolCall = parts.filterIsInstance<StreamPart.ToolCallPart>().single().toolCall
        assertEquals("code_execution", toolCall.toolName)
        // Provider-executed: a runtime that tried to run this itself would have no implementation to run.
        assertTrue(toolCall.providerExecuted)
        val result = parts.filterIsInstance<StreamPart.ToolResultPart>().single().toolResult
        assertEquals(toolCall.toolCallId, result.toolCallId)
    }

    @Test
    fun `the caller's name for the code execution tool is used on the call and its result`() = runTest {
        // "should use the custom code execution tool name for generated and streamed results": Gemini
        // reports the tool under its own name, and the caller declared it under another.
        val sse = chunks(
            """{"candidates":[{"content":{"parts":[{"executableCode":{"language":"PYTHON",""" +
                """"code":"print('ok')\\nprint(1/0)"}},{"codeExecutionResult":{"outcome":"OUTCOME_OK",""" +
                """"output":"ok\\n"}},{"codeExecutionResult":{"outcome":"OUTCOME_FAILED",""" +
                """"output":"ZeroDivisionError\\n"}}]}}]}""",
            """{"candidates":[{"finishReason":"STOP"}]}""",
        )
        val tools = listOf(GoogleTools.codeExecution(name = "CodeExecutionTool"))

        val streamed = model(sse).doStream(call.copy(tools = tools)).stream.toList()
        val generated = model(sse).doGenerate(call.copy(tools = tools))

        assertEquals(
            listOf("CodeExecutionTool"),
            streamed.filterIsInstance<StreamPart.ToolCallPart>().map { it.toolCall.toolName },
        )
        assertEquals(
            listOf("CodeExecutionTool", "CodeExecutionTool"),
            streamed.filterIsInstance<StreamPart.ToolResultPart>().map { it.toolResult.toolName },
        )
        assertEquals(
            listOf("CodeExecutionTool", "CodeExecutionTool", "CodeExecutionTool"),
            generated.content.map { (it as? Content.ToolCall)?.toolName ?: (it as Content.ToolResult).toolName },
        )
    }

    // --- prompt feedback across chunks ----------------------------------------------------------

    @Test
    fun `the unspecified block reasons are not a content filter`() = runTest {
        // "should not classify the default prompt block reason %j as a content filter": the enum's zero
        // value is sent on prompts that were never blocked, in both of Google's spellings.
        for (blockReason in listOf("", "BLOCK_REASON_UNSPECIFIED", "BLOCKED_REASON_UNSPECIFIED")) {
            val sse = chunks(
                """{"candidates":[],"promptFeedback":{"blockReason":"$blockReason"},""" +
                    """"usageMetadata":{"promptTokenCount":9,"totalTokenCount":9}}""",
            )

            val finish = model(sse).doStream(call).stream.toList().filterIsInstance<StreamPart.Finish>().single()

            assertEquals(FinishReason(FinishReason.Unified.Other, raw = null), finish.finishReason, blockReason)
        }
    }

    @Test
    fun `prompt feedback and trailing usage from separate chunks are both kept`() = runTest {
        // "should preserve prompt feedback and trailing usage from separate chunks".
        val promptFeedback = """{"blockReason":"BLOCK_REASON_UNSPECIFIED","safetyRatings":[]}"""
        val usageMetadata = """{"promptTokenCount":10,"candidatesTokenCount":3,"totalTokenCount":13}"""
        val sse = chunks(
            """{"promptFeedback":$promptFeedback}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Fixture text."}]},"finishReason":"STOP"}]}""",
            """{"usageMetadata":$usageMetadata}""",
        )

        val finish = model(sse).doStream(call).stream.toList().filterIsInstance<StreamPart.Finish>().single()

        assertEquals(FinishReason(FinishReason.Unified.Stop, raw = "STOP"), finish.finishReason)
        assertEquals(3, finish.usage.outputTokens.total)
        val google = finish.providerMetadata!!.getValue(GOOGLE_PROVIDER_ID)
        assertEquals(parseJsonObject(promptFeedback), google.obj("promptFeedback"))
        assertEquals(parseJsonObject(usageMetadata), google.obj("usageMetadata"))
    }

    @Test
    fun `a confirmed prompt block stays terminal across later chunks`() = runTest {
        // "should keep a confirmed prompt block terminal across later chunks": the text that follows a
        // SAFETY frame is not streamed, and the unspecified frame after it does not unblock the prompt.
        val usageMetadata = """{"promptTokenCount":10,"candidatesTokenCount":0,"totalTokenCount":10}"""
        val sse = chunks(
            """{"promptFeedback":{"blockReason":"SAFETY"}}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Fixture text."}]},"finishReason":"STOP"}]}""",
            """{"promptFeedback":{"blockReason":"BLOCK_REASON_UNSPECIFIED"},"usageMetadata":$usageMetadata}""",
        )

        val parts = model(sse).doStream(call).stream.toList()

        assertEquals(emptyList(), parts.filterIsInstance<StreamPart.TextDelta>())
        val finish = parts.filterIsInstance<StreamPart.Finish>().single()
        assertEquals(FinishReason(FinishReason.Unified.ContentFilter, raw = "SAFETY"), finish.finishReason)
        val google = finish.providerMetadata!!.getValue(GOOGLE_PROVIDER_ID)
        assertEquals(parseJsonObject("""{"blockReason":"SAFETY"}"""), google.obj("promptFeedback"))
        assertEquals(parseJsonObject(usageMetadata), google.obj("usageMetadata"))
        assertNull(google["safetyRatings"])
    }

    @Test
    fun `a mid-stream error frame is an error, not a short successful answer`() = runTest {
        // Gemini returns HTTP 200 and reports the failure in the body. Every field of the chunk type is
        // optional, so this used to decode SUCCESSFULLY into an empty chunk: a rate limit hit halfway
        // through a generation presented as a complete, slightly short reply that nothing retried.
        val sse = chunks(
            """{"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}""",
            """{"error":{"code":429,"message":"Resource exhausted","status":"RESOURCE_EXHAUSTED"}}""",
        )

        val error = model(sse).doStream(call).stream.toList()
            .filterIsInstance<StreamPart.Error>().single().error

        val apiError = assertIs<APICallError>(error)
        assertEquals("Resource exhausted", apiError.message)
        assertEquals(429, apiError.statusCode)
        assertTrue(apiError.isRetryable, "a retry policy can only fire on an error it can see")
    }

    /**
     * Round one to round two, over two real HTTP requests.
     *
     * This is the whole point of the provider: the payload Gemini issued has to come back verbatim, on
     * the part it was issued for. Asserting it on the SECOND request body is the only assertion that
     * proves the loop closes — everything short of that proves only that the value was parsed.
     */
    @Test
    fun `each signature comes back on its own part in the second request`() = runTest {
        val server = TestServer(
            TestServer.sse(
                chunks(
                    """{"responseId":"r1","candidates":[{"content":{"parts":[{"text":"Consider it.",""" +
                        """"thought":true,"thoughtSignature":"THOUGHT_SIG"}]}}]}""",
                    """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"calendar_search",""" +
                        """"args":{"day":"tuesday"}},"thoughtSignature":"CALL_SIG"}]}}]}""",
                    """{"candidates":[{"finishReason":"STOP"}]}""",
                ),
            ),
            TestServer.sse(chunks("""{"candidates":[{"finishReason":"STOP"}]}""")),
        )
        val model = GoogleLanguageModel(modelId = "gemini-3-pro", http = server.http())
        val tools = listOf(Tool.Function("calendar_search", buildJsonObject { put("type", "object") }))

        val first = assembleGenerateResult(model.doStream(call.copy(tools = tools)).stream)

        // Round two: the assistant turn is rebuilt from what round one produced, exactly as a runtime
        // that persisted the turn would rebuild it.
        val replayed = call.copy(
            tools = tools,
            prompt = call.prompt + listOf(
                ModelMessage.Assistant(
                    first.content.mapNotNull { content ->
                        when (content) {
                            is Content.Reasoning ->
                                AssistantPart.Reasoning(content.text, providerOptions = content.providerMetadata)
                            is Content.ToolCall -> AssistantPart.ToolCall(
                                toolCallId = content.toolCallId,
                                toolName = content.toolName,
                                input = content.input,
                                providerOptions = content.providerMetadata,
                            )
                            else -> null
                        }
                    },
                ),
                ModelMessage.Tool(
                    listOf(ToolPart.Result("gemini_1", "calendar_search", ToolOutput.Text("nothing"))),
                ),
            ),
        )
        model.doStream(replayed).stream.toList()

        val modelTurn = server.request(1).bodyJson().arr("contents")!!
            .map { it.jsonObject }
            .single { it["role"].string() == "model" }
        val parts = modelTurn.arr("parts")!!.map { it.jsonObject }

        assertEquals(2, parts.size)
        assertEquals(true, parts[0]["thought"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("THOUGHT_SIG", parts[0]["thoughtSignature"].string())
        assertEquals("calendar_search", parts[1].jsonObject["functionCall"]!!.jsonObject["name"].string())
        // The cross-contamination proof: the call's signature is the call's, and nothing else's.
        assertEquals("CALL_SIG", parts[1]["thoughtSignature"].string())
    }
}
