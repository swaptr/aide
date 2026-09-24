package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The end-to-end claim, tested end to end: a thinking signature survives the stream, the assembly, and
 * the replay back onto the wire.
 *
 * This is the exact loop Koog cannot complete — its `AnthropicStreamDelta` has no `signature` field, so
 * `signature_delta` events are dropped as an unknown delta type and the next turn is rejected with
 * `Invalid signature in thinking block`. If this test passes, that class of failure is gone.
 */
class AnthropicSignatureRoundTripTest {

    private val signature = "ErUBCkYIBRgCIkDXm2n4Q1p9sT7yZ0aVbNc2eFgHiJkLmNoPqRsTuVwXyZ0123456789=="
    private val redacted = "EroBCkYIBRgCIkC9zXencryptedpayload"

    /** A realistic Anthropic stream: thinking with a signature, then a tool call. */
    private fun toolRoundStream() = """
        event: message_start
        data: {"type":"message_start","message":{"id":"msg_01","model":"claude-opus-4-5","usage":{"input_tokens":42}}}

        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Check the calendar "}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"before answering."}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"$signature"}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":0}

        event: content_block_start
        data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_01A","name":"calendar_search"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"day\":"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"tuesday\"}"}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":1}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":88}}

        event: message_stop
        data: {"type":"message_stop"}

    """.trimIndent()

    private fun modelReturning(sse: String): AnthropicLanguageModel {
        val engine = MockEngine {
            respond(
                content = sse,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        return AnthropicLanguageModel(
            modelId = "claude-opus-4-5",
            http = ProviderHttp(HttpClient(engine)),
        )
    }

    private val call = CallOptions(
        prompt = listOf(ModelMessage.User(listOf(UserPart.Text("what's on tuesday?")))),
        reasoning = ReasoningEffort.Medium,
    )

    @Test
    fun `the signature arrives on the reasoning block's end part`() = runTest {
        val parts = modelReturning(toolRoundStream()).doStream(call).stream.toList()

        val end = parts.filterIsInstance<StreamPart.ReasoningEnd>().single()
        val payload = end.providerMetadata?.get(ANTHROPIC_PROVIDER_ID)
        assertNotNull(payload, "reasoning end carried no anthropic metadata")
        assertEquals(
            signature,
            payload[ANTHROPIC_SIGNATURE_KEY]?.toString()?.trim('"'),
        )
    }

    @Test
    fun `blocks are delimited and correlated by id`() = runTest {
        val parts = modelReturning(toolRoundStream()).doStream(call).stream.toList()

        // Reasoning opens, streams, closes — all under one id. Two deltas, not one merged blob.
        assertEquals(1, parts.filterIsInstance<StreamPart.ReasoningStart>().size)
        assertEquals(2, parts.filterIsInstance<StreamPart.ReasoningDelta>().size)
        assertEquals(1, parts.filterIsInstance<StreamPart.ReasoningEnd>().size)

        val toolCall = parts.filterIsInstance<StreamPart.ToolCallPart>().single().toolCall
        assertEquals("toolu_01A", toolCall.toolCallId)
        assertEquals("calendar_search", toolCall.toolName)
        // Fragments concatenate; they are never cumulative.
        assertEquals("""{"day":"tuesday"}""", toolCall.input)
    }

    @Test
    fun `usage and finish reason keep the vendor's own words`() = runTest {
        val parts = modelReturning(toolRoundStream()).doStream(call).stream.toList()

        val finish = parts.filterIsInstance<StreamPart.Finish>().single()
        assertEquals(FinishReason.Unified.ToolCalls, finish.finishReason.unified)
        assertEquals("tool_use", finish.finishReason.raw)
        assertEquals(42, finish.usage.inputTokens.total)
        assertEquals(88, finish.usage.outputTokens.total)
    }

    @Test
    fun `assembly preserves order and carries the signature onto the content`() = runTest {
        val result = assembleGenerateResult(modelReturning(toolRoundStream()).doStream(call).stream)

        // Reasoning opened first, so it is first — even though a tool call closed after it.
        val reasoning = result.content[0] as Content.Reasoning
        assertEquals("Check the calendar before answering.", reasoning.text)
        assertEquals(
            signature,
            reasoning.providerMetadata?.get(ANTHROPIC_PROVIDER_ID)
                ?.get(ANTHROPIC_SIGNATURE_KEY)?.toString()?.trim('"'),
        )
        assertTrue(result.content[1] is Content.ToolCall)
    }

    @Test
    fun `a replayed turn puts the signed thinking block first, verbatim`() {
        // The other half of the loop: what came off the wire goes back onto it unchanged.
        val replay = listOf(
            ModelMessage.User(listOf(UserPart.Text("what's on tuesday?"))),
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.Reasoning(
                        text = "Check the calendar before answering.",
                        providerOptions = anthropicMetadata(ANTHROPIC_SIGNATURE_KEY to signature),
                    ),
                    AssistantPart.ToolCall("toolu_01A", "calendar_search", """{"day":"tuesday"}"""),
                ),
            ),
        )

        val assistant = replay.toAnthropic(promptContext()).messages.single { it.role == "assistant" }

        val thinking = assistant.content[0]
        assertEquals("\"thinking\"", thinking["type"].toString())
        assertEquals(signature, thinking["signature"].toString().trim('"'))
        assertEquals("\"tool_use\"", assistant.content[1]["type"].toString())
    }

    @Test
    fun `redacted thinking survives with its payload and its position`() = runTest {
        val sse = """
            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"redacted_thinking","data":"$redacted"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Here you go."}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

        """.trimIndent()

        val result = assembleGenerateResult(modelReturning(sse).doStream(call).stream)

        // Arrival ORDER is the contract: the redacted block came first and must stay first.
        val reasoning = result.content[0] as Content.Reasoning
        assertEquals(
            redacted,
            reasoning.providerMetadata?.get(ANTHROPIC_PROVIDER_ID)
                ?.get(ANTHROPIC_REDACTED_KEY)?.toString()?.trim('"'),
        )
        assertEquals("Here you go.", (result.content[1] as Content.Text).text)
    }

    @Test
    fun `a redacted block replays as its own wire type`() {
        val replay = listOf(
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.Reasoning(
                        text = "",
                        providerOptions = anthropicMetadata(ANTHROPIC_REDACTED_KEY to redacted),
                    ),
                ),
            ),
        )

        val block = replay.toAnthropic(promptContext()).messages.single().content.single()

        assertEquals("\"redacted_thinking\"", block["type"].toString())
        assertEquals(redacted, block["data"].toString().trim('"'))
    }

    /** What a persisted assistant turn looks like coming back out of storage. */
    private fun anthropicMetadata(vararg entries: Pair<String, String>) = mapOf(
        ANTHROPIC_PROVIDER_ID to buildJsonObject { entries.forEach { (k, v) -> put(k, v) } },
    )

    /**
     * A prompt context with nothing configured, for the replay tests.
     *
     * Cache control, tool-name mapping and the beta set are all request-level concerns; what these tests
     * assert is that a signed block survives the conversion, which none of them touch.
     */
    private fun promptContext(): AnthropicPromptContext = AnthropicPromptContext(
        warnings = mutableListOf(),
        cacheControls = AnthropicCacheControlBudget(),
    )
}
