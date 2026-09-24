package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Gemini's `thoughtSignature`, which Google documents in the strongest terms available:
 * *"You MUST always resend all thought blocks exactly as they were received."*
 *
 * Losing it degrades multi-turn reasoning silently rather than loudly, and on the OpenAI-compat path it
 * surfaces as `Function call is missing a thought_signature` on the second tool round — an open upstream
 * bug in Koog and the reason Gemini gets a native provider here rather than riding the compat one.
 */
class GoogleThoughtSignatureTest {

    private var lastRequest: HttpRequestData? = null
    private val signature = "CtYBAdHtim9zZXJfaW50ZXJuYWxfcmVhc29uaW5nX3N0YXRl"

    private fun model(sse: String): GoogleLanguageModel {
        val engine = MockEngine { request ->
            lastRequest = request
            respond(content = sse, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        return GoogleLanguageModel(modelId = "gemini-3-pro", http = ProviderHttp(HttpClient(engine)))
    }

    private val call = CallOptions(
        prompt = listOf(ModelMessage.User(listOf(UserPart.Text("what's on tuesday?")))),
        reasoning = ReasoningEffort.Medium,
    )

    private fun chunks(vararg objects: String) = objects.joinToString("") { "data: $it\n\n" }

    private fun sentBody(): JsonObject = parseJsonObject((lastRequest!!.body as TextContent).text)

    /** A thinking turn that ends in a tool call, with the signature on the call. */
    private fun toolRoundStream() = chunks(
        """{"responseId":"r1","modelVersion":"gemini-3-pro","candidates":[{"content":{"role":"model","parts":[{"text":"Checking the calendar.","thought":true}]}}]}""",
        """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"calendar_search","args":{"day":"tuesday"}},"thoughtSignature":"$signature"}]}}]}""",
        """{"candidates":[{"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":30,"candidatesTokenCount":80,"thoughtsTokenCount":50}}""",
    )

    @Test
    fun `the signature reaches the tool call it belongs to`() = runTest {
        val parts = model(toolRoundStream()).doStream(call).stream.toList()

        val toolCall = parts.filterIsInstance<StreamPart.ToolCallPart>().single().toolCall
        assertEquals("calendar_search", toolCall.toolName)
        assertEquals(
            signature,
            toolCall.providerMetadata?.get(GOOGLE_PROVIDER_ID)
                ?.get(GOOGLE_THOUGHT_SIGNATURE_KEY)?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `a call's signature never leaks onto the reasoning block beside it`() = runTest {
        val parts = model(toolRoundStream()).doStream(call).stream.toList()

        // This used to assert the opposite, and the opposite was the bug: any signature seen anywhere in
        // the stream was hoisted into one variable and stamped on the reasoning block. Round two then
        // sent a thought part carrying the signature Gemini issued for the FUNCTION CALL — a request that
        // fails with the signature visibly present, which is far harder to diagnose than one that fails
        // with it absent. This turn's signature belongs to the call; the unsigned thought has none.
        val end = parts.filterIsInstance<StreamPart.ReasoningEnd>().single()
        assertNull(end.providerMetadata)
    }

    @Test
    fun `a signed thought and a signed call keep their own signatures`() = runTest {
        val sse = chunks(
            """{"candidates":[{"content":{"parts":[{"text":"thinking","thought":true,""" +
                """"thoughtSignature":"THOUGHT_SIG"}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"f","args":{}},""" +
                """"thoughtSignature":"CALL_SIG"}]}}]}""",
            """{"candidates":[{"finishReason":"STOP"}]}""",
        )

        val parts = model(sse).doStream(call).stream.toList()

        assertEquals("THOUGHT_SIG", parts.filterIsInstance<StreamPart.ReasoningEnd>().single().signature())
        assertEquals(
            "CALL_SIG",
            parts.filterIsInstance<StreamPart.ToolCallPart>().single().toolCall.providerMetadata.signature(),
        )
    }

    @Test
    fun `a signature survives a turn that emitted no thought summary at all`() = runTest {
        // Gemini returns signatures with no readable trace on several models. The old code only closed a
        // reasoning block when summary TEXT had arrived, so this turn dropped its signature with no trace
        // of the loss anywhere.
        val sse = chunks(
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"f","args":{}},""" +
                """"thoughtSignature":"$signature"}]}}]}""",
            """{"candidates":[{"finishReason":"STOP"}]}""",
        )

        val toolCall = model(sse).doStream(call).stream.toList()
            .filterIsInstance<StreamPart.ToolCallPart>().single()

        assertEquals(signature, toolCall.toolCall.providerMetadata.signature())
    }

    @Test
    fun `two reasoning blocks in one turn each keep their own signature`() = runTest {
        // One shared block id could hold one reasoning block per turn, so the second overwrote the first.
        val sse = chunks(
            """{"candidates":[{"content":{"parts":[{"text":"first","thought":true,""" +
                """"thoughtSignature":"SIG_ONE"}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"text":"an answer"}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"text":"second","thought":true,""" +
                """"thoughtSignature":"SIG_TWO"}]}}]}""",
            """{"candidates":[{"finishReason":"STOP"}]}""",
        )

        val ends = model(sse).doStream(call).stream.toList()
            .filterIsInstance<StreamPart.ReasoningEnd>()

        assertEquals(listOf("SIG_ONE", "SIG_TWO"), ends.map { it.signature() })
        assertEquals(2, ends.map { it.id }.toSet().size, "each block needs an id of its own")
    }

    @Test
    fun `thought parts become reasoning, not visible text`() = runTest {
        val result = assembleGenerateResult(model(toolRoundStream()).doStream(call).stream)

        val reasoning = result.content[0] as Content.Reasoning
        assertEquals("Checking the calendar.", reasoning.text)
        assertTrue(result.content[1] is Content.ToolCall)
    }

    @Test
    fun `a replayed tool call carries its signature back`() {
        // The exact failure the compat path produces: the call goes back without its signature and the
        // second round is rejected.
        val prompt = listOf(
            ModelMessage.User(listOf(UserPart.Text("what's on tuesday?"))),
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.ToolCall(
                        toolCallId = "gemini_abc",
                        toolName = "calendar_search",
                        input = """{"day":"tuesday"}""",
                        providerOptions = signatureMetadata(),
                    ),
                ),
            ),
            ModelMessage.Tool(
                listOf(ToolPart.Result("gemini_abc", "calendar_search", ToolOutput.Text("nothing"))),
            ),
        )

        val (_, contents) = prompt.toGoogleContents()

        val modelTurn = contents.single { it.role == "model" }
        assertEquals(signature, modelTurn.parts.single().thoughtSignature)
        assertEquals("calendar_search", modelTurn.parts.single().functionCall?.name)
    }

    @Test
    fun `a replayed thought part keeps thought true and its signature`() {
        val prompt = listOf(
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.Reasoning("earlier reasoning", providerOptions = signatureMetadata()),
                    AssistantPart.Text("the answer"),
                ),
            ),
        )

        val (_, contents) = prompt.toGoogleContents()

        val parts = contents.single().parts
        assertEquals(true, parts[0].thought)
        assertEquals(signature, parts[0].thoughtSignature)
        assertEquals("earlier reasoning", parts[0].text)
        // The visible answer is an ordinary part, not a thought.
        assertNull(parts[1].thought)
    }

    @Test
    fun `tool results replay as a user turn of functionResponse parts`() {
        // Gemini has no tool role; results ride a user turn or the request is rejected.
        val prompt = listOf(
            ModelMessage.Tool(
                listOf(
                    ToolPart.Result("id1", "one", ToolOutput.Text("a")),
                    ToolPart.Result("id2", "two", ToolOutput.ErrorText("b")),
                ),
            ),
        )

        val (_, contents) = prompt.toGoogleContents()

        val turn = contents.single()
        assertEquals("user", turn.role)
        // Both results merge into ONE turn — consecutive user turns are rejected.
        assertEquals(2, turn.parts.size)
        assertEquals("one", turn.parts[0].functionResponse?.name)
        assertTrue(turn.parts[1].functionResponse!!.response.containsKey("error"))
    }

    @Test
    fun `the system instruction is hoisted out of the contents`() {
        val prompt = listOf(
            ModelMessage.System("be terse"),
            ModelMessage.User(listOf(UserPart.Text("hi"))),
        )

        val (system, contents) = prompt.toGoogleContents()

        assertNotNull(system)
        assertEquals("be terse", system.parts.single().text)
        assertEquals(listOf("user"), contents.map { it.role })
    }

    @Test
    fun `includeThoughts is set so the trace is not silently empty`() = runTest {
        model(toolRoundStream()).doStream(call).stream.toList()

        // Gemini returns no thought summaries by default: omit this and the trace UI shows nothing.
        val thinking = sentBody()["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals(true, thinking["includeThoughts"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("medium", thinking["thinkingLevel"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a forced tool becomes ANY plus a one-name allow list`() = runTest {
        val tools = listOf(Tool.Function(name = "pick_me", inputSchema = buildJsonObject { }))

        model(toolRoundStream()).doStream(
            call.copy(tools = tools, toolChoice = ToolChoice.Specific("pick_me")),
        ).stream.toList()

        // Gemini has no "this exact tool" mode; ANY with an allow-list of one is the documented way.
        val config = sentBody()["toolConfig"]!!.jsonObject["functionCallingConfig"]!!.jsonObject
        assertEquals("ANY", config["mode"]?.jsonPrimitive?.content)
        assertEquals("pick_me", config["allowedFunctionNames"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `no tools means no toolConfig`() = runTest {
        model(toolRoundStream()).doStream(call.copy(toolChoice = ToolChoice.Required)).stream.toList()

        assertNull(sentBody()["toolConfig"])
    }

    @Test
    fun `usage separates thought tokens from answer tokens`() = runTest {
        val finish = model(toolRoundStream()).doStream(call).stream.toList()
            .filterIsInstance<StreamPart.Finish>().single()

        assertEquals(30, finish.usage.inputTokens.total)
        // `candidatesTokenCount` ALREADY excludes the thought tokens, so the total is their sum and the
        // text share is the candidate count unchanged. Subtracting one from the other double-subtracted:
        // a turn that thought more than it answered reported a NEGATIVE text count.
        assertEquals(130, finish.usage.outputTokens.total)
        assertEquals(50, finish.usage.outputTokens.reasoning)
        assertEquals(80, finish.usage.outputTokens.text)
    }

    @Test
    fun `a safety block is reported as a content filter, keeping the vendor reason`() = runTest {
        val sse = chunks("""{"candidates":[{"finishReason":"SAFETY"}]}""")

        val finish = model(sse).doStream(call).stream.toList()
            .filterIsInstance<StreamPart.Finish>().single()

        assertEquals(FinishReason.Unified.ContentFilter, finish.finishReason.unified)
        assertEquals("SAFETY", finish.finishReason.raw)
    }

    @Test
    fun `structured function args round-trip through the string transport`() = runTest {
        val result = assembleGenerateResult(model(toolRoundStream()).doStream(call).stream)

        // Gemini sends structured args; the spec transports a JSON string. The content must survive.
        val toolCall = result.content.filterIsInstance<Content.ToolCall>().single()
        val parsed = parseJsonObject(toolCall.input)
        assertEquals("tuesday", parsed["day"]?.jsonPrimitive?.content)
    }

    private fun StreamPart.ReasoningEnd.signature(): String? = providerMetadata.signature()

    private fun com.sabreware.aide.aisdk.ProviderMetadata?.signature(): String? =
        this?.get(GOOGLE_PROVIDER_ID)?.get(GOOGLE_THOUGHT_SIGNATURE_KEY)?.jsonPrimitive?.content

    private fun signatureMetadata() = mapOf(
        GOOGLE_PROVIDER_ID to buildJsonObject { put(GOOGLE_THOUGHT_SIGNATURE_KEY, signature) },
    )
}
