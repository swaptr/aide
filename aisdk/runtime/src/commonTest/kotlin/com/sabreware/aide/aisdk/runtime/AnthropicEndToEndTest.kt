package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.anthropic.AnthropicProvider
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The full chain, with nothing faked but the socket.
 *
 * A real Anthropic provider, a real tool loop, and a MockEngine that records what actually went out. The
 * assertion is on the SECOND HTTP request body — the one Anthropic would reject with
 * `Invalid signature in thinking block` if any layer between the wire and the replay dropped or reordered
 * the signed block.
 *
 * The unit tests each prove one link: the provider captures the signature, the assembler carries it onto
 * the content, the loop replays it in position. This proves they are actually connected.
 */
class AnthropicEndToEndTest {

    private val signature = "ErUBCkYIBRgCIkDXm2n4Q1p9sT7yZ0aVbNc2eFgHiJkLmNoPqRsTuVwXyZ"

    private val roundOne = """
        event: message_start
        data: {"type":"message_start","message":{"id":"msg_1","model":"claude-opus-4-5","usage":{"input_tokens":20}}}

        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"I should check the calendar."}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"SIGNATURE"}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":0}

        event: content_block_start
        data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_9","name":"calendar"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"day\":\"tue\"}"}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":1}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":40}}

    """.trimIndent().replace("SIGNATURE", signature)

    private val roundTwo = """
        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"You're free on Tuesday."}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":0}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":15}}

    """.trimIndent()

    @Test
    fun `the signed thinking block is replayed first and verbatim on the second request`() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            bodies += (request.body as TextContent).text
            respond(
                content = if (bodies.size == 1) roundOne else roundTwo,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val model = AnthropicProvider(HttpClient(engine), apiKey = "k")
            .languageModel("claude-opus-4-5")

        val result = generateText(
            model = model,
            prompt = listOf(ModelMessage.User(listOf(UserPart.Text("am I free tuesday?")))),
            options = CallOptions(
                prompt = emptyList(),
                reasoning = ReasoningEffort.Medium,
                tools = listOf(Tool.Function("calendar", buildJsonObject { })),
            ),
            toolExecutor = { _, _ -> ToolOutput.Text("no events") },
            stopWhen = stepCountIs(5),
        )

        assertEquals(2, bodies.size, "the loop should have made a second request")

        val secondBody = parseJsonObject(bodies[1])
        val messages = secondBody["messages"]!!.jsonArray
        val assistant = messages.map { it.jsonObject }.single { it["role"]?.jsonPrimitive?.content == "assistant" }
        val blocks = assistant["content"]!!.jsonArray.map { it.jsonObject }

        // Anthropic requires the replayed turn to BEGIN with its thinking block...
        assertEquals("thinking", blocks[0]["type"]?.jsonPrimitive?.content)
        // ...carrying the signature byte-for-byte, or the request is rejected outright.
        assertEquals(signature, blocks[0]["signature"]?.jsonPrimitive?.content)
        assertEquals("I should check the calendar.", blocks[0]["thinking"]?.jsonPrimitive?.content)
        assertEquals("tool_use", blocks[1]["type"]?.jsonPrimitive?.content)

        // The tool result went back as a user turn, which is the only shape Anthropic accepts.
        val userTurns = messages.map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "user" }
        assertTrue(
            userTurns.any { turn ->
                turn["content"]!!.jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_result" }
            },
            "no tool_result block reached the second request",
        )

        assertEquals("You're free on Tuesday.", result.text)
        assertEquals(2, result.steps.size)
        // Usage accumulates across both rounds rather than reporting only the last.
        assertEquals(55, result.usage.outputTokens.total)
    }
}
