package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.openai.OpenAIProvider
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The OpenAI half: an encrypted chain of thought, carried across a tool round.
 *
 * OpenAI's `encrypted_content` is the counterpart to Anthropic's `signature` and Gemini's
 * `thoughtSignature`, and it is the only one of the three whose loss is invisible. Anthropic rejects a
 * modified signature and Gemini rejects a missing one, so both fail loudly; OpenAI simply re-derives the
 * chain of thought, answers correctly, and bills the reasoning tokens again on every round. A regression
 * here shows up as a cost, not as an error, which is why it needs a test rather than a bug report.
 *
 * The three things asserted are the three that can each break on their own:
 *
 * 1. Request one pairs `store: false` with `include: ["reasoning.encrypted_content"]`. Sending the first
 *    without the second is a call that succeeds and returns reasoning with nothing replayable in it.
 * 2. Request two replays the reasoning item — its id and its encrypted payload, byte-for-byte — BEFORE
 *    the function call it led to. Order is part of the contract: reasoning that follows the call it
 *    produced is not a record of how the model got there.
 * 3. The tool's answer goes back as a `function_call_output` keyed by `call_id`, not by item id.
 */
class OpenAIEndToEndTest {

    private val encrypted = "gAAAAABm7Uk9ZW5jcnlwdGVkIHJlYXNvbmluZyBwYXlsb2FkIGZyb20gb3BlbmFp"

    private fun sse(vararg objects: String) = objects.joinToString("") { "data: $it\n\n" }

    private val roundOne = sse(
        """{"type":"response.created","response":{"id":"resp_1","created_at":1770000000,""" +
            """"model":"gpt-5.1"}}""",
        """{"type":"response.output_item.added","output_index":0,""" +
            """"item":{"type":"reasoning","id":"rs_1","summary":[]}}""",
        """{"type":"response.reasoning_summary_text.delta","item_id":"rs_1","summary_index":0,""" +
            """"delta":"Check the calendar."}""",
        """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"rs_1",""" +
            """"encrypted_content":"ENCRYPTED","summary":[{"type":"summary_text",""" +
            """"text":"Check the calendar."}]}}""",
        """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call",""" +
            """"id":"fc_1","call_id":"call_9","name":"calendar_search","arguments":""}}""",
        """{"type":"response.function_call_arguments.delta","output_index":1,"item_id":"fc_1",""" +
            """"delta":"{\"day\":\"tuesday\"}"}""",
        """{"type":"response.output_item.done","output_index":1,"item":{"type":"function_call",""" +
            """"id":"fc_1","call_id":"call_9","name":"calendar_search",""" +
            """"arguments":"{\"day\":\"tuesday\"}"}}""",
        """{"type":"response.completed","response":{"id":"resp_1","model":"gpt-5.1","usage":""" +
            """{"input_tokens":30,"output_tokens":48,"output_tokens_details":{"reasoning_tokens":40}}}}""",
    ).replace("ENCRYPTED", encrypted)

    private val roundTwo = sse(
        """{"type":"response.created","response":{"id":"resp_2","created_at":1770000100,""" +
            """"model":"gpt-5.1"}}""",
        """{"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"msg_1",""" +
            """"role":"assistant","content":[]}}""",
        """{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,""" +
            """"delta":"You're free on Tuesday."}""",
        """{"type":"response.output_item.done","output_index":0,"item":{"type":"message","id":"msg_1",""" +
            """"role":"assistant","content":[{"type":"output_text",""" +
            """"text":"You're free on Tuesday."}]}}""",
        """{"type":"response.completed","response":{"id":"resp_2","model":"gpt-5.1","usage":""" +
            """{"input_tokens":70,"output_tokens":9}}}""",
    )

    private fun run(bodies: MutableList<String>) = OpenAIProvider(
        HttpClient(
            MockEngine { request ->
                bodies += (request.body as TextContent).text
                respond(
                    content = if (bodies.size == 1) roundOne else roundTwo,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        ),
        apiKey = "k",
    ).languageModel("gpt-5.1")

    /** `store: false` is what makes the reasoning payload the caller's to keep rather than OpenAI's. */
    private val stateless = mapOf("openai" to buildJsonObject { put("store", false) })

    @Test
    fun `the encrypted reasoning item is replayed before its tool call on the second request`() = runTest {
        val bodies = mutableListOf<String>()

        val result = streamText(
            model = run(bodies),
            prompt = listOf(ModelMessage.User(listOf(UserPart.Text("am I free tuesday?")))),
            options = CallOptions(
                prompt = emptyList(),
                reasoning = ReasoningEffort.Medium,
                tools = listOf(
                    Tool.Function("calendar_search", buildJsonObject { put("type", "object") }),
                ),
                providerOptions = stateless,
            ),
            toolExecutor = { _, _ -> ToolOutput.Text("nothing scheduled") },
            stopWhen = stepCountIs(5),
        ).result()

        assertEquals(2, bodies.size, "the loop should have made a second request")

        // A stateless reasoning call that does not ask for the encrypted payload succeeds and returns
        // nothing replayable, so the pairing is checked on the wire rather than trusted.
        val first = parseJsonObject(bodies[0])
        assertEquals("false", first["store"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("reasoning.encrypted_content"),
            first["include"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        val input = parseJsonObject(bodies[1])["input"]!!.jsonArray.map { it.jsonObject }
        val reasoning = input.single { it.type() == "reasoning" }
        val functionCall = input.single { it.type() == "function_call" }

        assertEquals("rs_1", reasoning["id"]?.jsonPrimitive?.content)
        assertEquals(encrypted, reasoning["encrypted_content"]?.jsonPrimitive?.content)
        assertEquals(
            "Check the calendar.",
            reasoning["summary"]!!.jsonArray.single().jsonObject["text"]?.jsonPrimitive?.content,
        )
        assertTrue(
            input.indexOf(reasoning) < input.indexOf(functionCall),
            "the reasoning that produced a call must precede it, or it is not a record of anything",
        )

        assertEquals("call_9", functionCall["call_id"]?.jsonPrimitive?.content)
        assertEquals("calendar_search", functionCall["name"]?.jsonPrimitive?.content)

        // OpenAI matches a result to its call by `call_id`; keying it by the item id is a 400.
        val output = input.single { it.type() == "function_call_output" }
        assertEquals("call_9", output["call_id"]?.jsonPrimitive?.content)
        assertTrue(
            output["output"].toString().contains("nothing scheduled"),
            "the tool's answer never reached the second request",
        )

        assertEquals("You're free on Tuesday.", result.text)
        assertEquals(2, result.steps.size)
        assertEquals(57, result.usage.outputTokens.total)
        assertEquals(40, result.usage.outputTokens.reasoning)
    }

    @Test
    fun `an encrypted turn survives being stored and read back`() = runTest {
        val bodies = mutableListOf<String>()
        val model = run(bodies)
        val options = CallOptions(
            prompt = emptyList(),
            reasoning = ReasoningEffort.Medium,
            tools = listOf(Tool.Function("calendar_search", buildJsonObject { put("type", "object") })),
            providerOptions = stateless,
        )
        val opening = listOf(ModelMessage.User(listOf(UserPart.Text("am I free tuesday?"))))

        val first = streamText(
            model = model,
            prompt = opening,
            options = options,
            toolExecutor = { _, _ -> ToolOutput.Text("nothing scheduled") },
            stopWhen = stepCountIs(1),
        ).result()
        val stored = PersistedTurn.roundTrip(first.messages)

        streamText(
            model = model,
            prompt = opening + stored,
            options = options,
            toolExecutor = { _, _ -> ToolOutput.Text("nothing scheduled") },
            stopWhen = stepCountIs(1),
        ).result()

        val reasoning = parseJsonObject(bodies[1])["input"]!!.jsonArray
            .map { it.jsonObject }
            .single { it.type() == "reasoning" }
        assertEquals(encrypted, reasoning["encrypted_content"]?.jsonPrimitive?.content)
    }

    private fun JsonObject.type(): String? = this["type"]?.jsonPrimitive?.content

    /**
     * Drains a run to its result.
     *
     * `streamText` rather than `generateText`, because the Responses API's two halves are two different
     * wires: `doGenerate` reads one JSON document and `doStream` reads the SSE stream this test serves.
     * Reaching the streaming mapper is the point — it is where the encrypted payload is picked off the
     * `output_item.done` frame, which is the only frame that carries it.
     */
    private suspend fun Flow<RunEvent>.result(): RunResult =
        filterIsInstance<RunEvent.Finish>().last().result
}
