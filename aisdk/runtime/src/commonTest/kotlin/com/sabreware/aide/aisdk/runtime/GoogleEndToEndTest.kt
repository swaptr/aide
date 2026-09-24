package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.google.GoogleProvider
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The Gemini half of what this module exists for, over two real HTTP requests.
 *
 * Google's rule is *"you MUST always resend all thought blocks exactly as they were received"*, and a
 * turn can carry several signed parts at once — a thought and the function call it led to, each with its
 * own `thoughtSignature`. Round two must therefore put each signature back on the part that earned it.
 * The failure this pins is the one that was actually shipped: a stream-scoped signature variable stamped
 * whichever value arrived last onto the reasoning block, so Gemini received a thought signed for a
 * function call and a function call signed for nothing, and answered
 * `Function call is missing a thought_signature`.
 *
 * `GoogleResponseTest` reaches the same wire by rebuilding the assistant turn by hand. This one lets the
 * loop rebuild it, so [Step.toAssistantMessage] — the code that actually carries the metadata across a
 * round — is the thing under test rather than the test's own transcription of it.
 */
class GoogleEndToEndTest {

    private val thoughtSignature = "CtIBAcu98PBzYW1wbGUgdGhvdWdodCBzaWduYXR1cmUgZm9yIGdlbWluaQ"
    private val callSignature = "CtIBAcu98PBmdW5jdGlvbiBjYWxsIHNpZ25hdHVyZSwgZGlmZmVyZW50"

    private fun sse(vararg objects: String) = objects.joinToString("") { "data: $it\n\n" }

    private val roundOne = sse(
        """{"responseId":"r1","modelVersion":"gemini-3-pro","candidates":[{"content":{"parts":[""" +
            """{"text":"The calendar knows.","thought":true,"thoughtSignature":"THOUGHT_SIG"}]}}]}""",
        """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"calendar_search",""" +
            """"args":{"day":"tuesday"}},"thoughtSignature":"CALL_SIG"}]}}]}""",
        """{"candidates":[{"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":18,""" +
            """"candidatesTokenCount":30,"thoughtsTokenCount":12}}""",
    ).replace("THOUGHT_SIG", thoughtSignature).replace("CALL_SIG", callSignature)

    private val roundTwo = sse(
        """{"candidates":[{"content":{"parts":[{"text":"You're free on Tuesday."}]}}]}""",
        """{"candidates":[{"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":60,""" +
            """"candidatesTokenCount":8}}""",
    )

    @Test
    fun `each signature returns on its own part in the second request`() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            bodies += (request.body as TextContent).text
            respond(
                content = if (bodies.size == 1) roundOne else roundTwo,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val model = GoogleProvider(HttpClient(engine), apiKey = "k").languageModel("gemini-3-pro")

        val result = generateText(
            model = model,
            prompt = listOf(ModelMessage.User(listOf(UserPart.Text("am I free tuesday?")))),
            options = CallOptions(
                prompt = emptyList(),
                reasoning = ReasoningEffort.Medium,
                tools = listOf(
                    Tool.Function("calendar_search", buildJsonObject { put("type", "object") }),
                ),
            ),
            toolExecutor = { _, _ -> ToolOutput.Text("nothing scheduled") },
            stopWhen = stepCountIs(5),
        )

        assertEquals(2, bodies.size, "the loop should have made a second request")

        val contents = parseJsonObject(bodies[1])["contents"]!!.jsonArray.map { it.jsonObject }
        val modelTurn = contents.single { it["role"]?.jsonPrimitive?.content == "model" }
        val parts = modelTurn["parts"]!!.jsonArray.map { it.jsonObject }

        assertEquals(2, parts.size, "the replayed turn should hold the thought and the call, nothing else")
        assertEquals("true", parts[0]["thought"]?.jsonPrimitive?.content)
        assertEquals(thoughtSignature, parts[0]["thoughtSignature"]?.jsonPrimitive?.content)
        assertEquals("The calendar knows.", parts[0]["text"]?.jsonPrimitive?.content)

        val functionCall = parts[1]["functionCall"]!!.jsonObject
        assertEquals("calendar_search", functionCall["name"]?.jsonPrimitive?.content)
        assertEquals("tuesday", functionCall["args"]!!.jsonObject["day"]?.jsonPrimitive?.content)
        // The whole point: the call's signature is the call's, and the thought never borrowed it.
        assertEquals(callSignature, parts[1]["thoughtSignature"]?.jsonPrimitive?.content)
        assertNull(parts[1]["thought"], "a function call is not a thought block")

        // Gemini takes a tool result back as a user turn of functionResponse parts, not a tool role.
        val functionResponse = contents
            .filter { it["role"]?.jsonPrimitive?.content == "user" }
            .flatMap { it["parts"]!!.jsonArray.map { part -> part.jsonObject } }
            .mapNotNull { it["functionResponse"]?.jsonObject }
            .single()
        assertEquals("calendar_search", functionResponse["name"]?.jsonPrimitive?.content)

        assertEquals("You're free on Tuesday.", result.text)
        assertEquals(2, result.steps.size)
        // `candidatesTokenCount` already excludes thoughts, so the round-one total is 30 + 12.
        assertEquals(50, result.usage.outputTokens.total)
        assertEquals(12, result.usage.outputTokens.reasoning)
    }

    @Test
    fun `a signed turn replays through a persisted round trip`() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            bodies += (request.body as TextContent).text
            respond(
                content = if (bodies.size == 1) roundOne else roundTwo,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val model = GoogleProvider(HttpClient(engine), apiKey = "k").languageModel("gemini-3-pro")
        val tools = listOf(Tool.Function("calendar_search", buildJsonObject { put("type", "object") }))
        val opening = listOf(ModelMessage.User(listOf(UserPart.Text("am I free tuesday?"))))

        val first = generateText(
            model = model,
            prompt = opening,
            options = CallOptions(prompt = emptyList(), reasoning = ReasoningEffort.Medium, tools = tools),
            toolExecutor = { _, _ -> ToolOutput.Text("nothing scheduled") },
            stopWhen = stepCountIs(1),
        )

        val stored = PersistedTurn.roundTrip(first.messages)

        generateText(
            model = model,
            prompt = opening + stored,
            options = CallOptions(prompt = emptyList(), reasoning = ReasoningEffort.Medium, tools = tools),
            toolExecutor = { _, _ -> ToolOutput.Text("nothing scheduled") },
            stopWhen = stepCountIs(1),
        )

        val parts = parseJsonObject(bodies[1])["contents"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["role"]?.jsonPrimitive?.content == "model" }["parts"]!!
            .jsonArray.map { it.jsonObject }

        assertTrue(bodies.size == 2, "the replay should have produced a second request")
        assertEquals(thoughtSignature, parts[0]["thoughtSignature"]?.jsonPrimitive?.content)
        assertEquals(callSignature, parts[1]["thoughtSignature"]?.jsonPrimitive?.content)
    }
}
