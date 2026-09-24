package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.NoObjectGeneratedError
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The tool-mode fallback and the streaming error hook.
 *
 * Tool mode is the arm for servers with no `response_format` at all — the reference dropped it in v7,
 * and it is kept here because a compat server that rejects the field cannot do structured output any
 * other way.
 */
class StructuredModeTest {

    private val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("where?"))))

    private val schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("city") { put("type", "string") } }
    }

    /** Records what it was asked for, and replays a scripted stream. */
    private class ScriptedModel(private val parts: List<StreamPart>) : LanguageModel {
        override val provider: String = "scripted"
        override val modelId: String = "scripted-1"
        var seen: CallOptions? = null

        override suspend fun doStream(options: CallOptions): StreamResult {
            seen = options
            return StreamResult(parts.asFlow())
        }

        override suspend fun doGenerate(options: CallOptions): GenerateResult {
            seen = options
            return assembleGenerateResult(parts.asFlow())
        }
    }

    private fun toolAnswer(input: String, name: String = "json") = listOf(
        StreamPart.ToolCallPart(Content.ToolCall("c1", name, input)),
        StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.ToolCalls, raw = "tool_calls")),
    )

    private fun textAnswer(text: String) = listOf(
        StreamPart.TextStart("t0"),
        StreamPart.TextDelta("t0", text),
        StreamPart.TextEnd("t0"),
        StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.Stop, raw = "stop")),
    )

    @Test
    fun `tool mode sends a forced tool and no response format`() = runTest {
        val model = ScriptedModel(toolAnswer("""{"city":"Paris"}"""))

        val result = generateObjectJson(
            model = model,
            prompt = prompt,
            schema = schema,
            mode = StructuredMode.Tool,
        )

        val sent = assertNotNull(model.seen)
        // The whole point: a server that rejects `response_format` never sees one.
        assertNull(sent.responseFormat)
        assertEquals(ToolChoice.Specific("json"), sent.toolChoice)
        val tool = sent.tools?.single() as com.sabreware.aide.aisdk.Tool.Function
        assertEquals("json", tool.name)
        assertEquals(schema, tool.inputSchema)
        assertEquals("Paris", (result.value as JsonObject)["city"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the forced tool is never executed`() = runTest {
        val model = ScriptedModel(toolAnswer("""{"city":"Paris"}"""))
        // Its arguments ARE the answer; running it would run the model's own reply.
        val result = generateObjectJson(model, prompt, schema, mode = StructuredMode.Tool)

        assertTrue(result.steps.single().toolResults.isEmpty())
    }

    @Test
    fun `a schema name names the tool`() = runTest {
        val model = ScriptedModel(toolAnswer("""{"city":"Rome"}""", name = "location"))

        generateObjectJson(model, prompt, schema, schemaName = "location", mode = StructuredMode.Tool)

        assertEquals(ToolChoice.Specific("location"), assertNotNull(model.seen).toolChoice)
    }

    @Test
    fun `a model that answers in prose instead of calling the tool is still read`() = runTest {
        // Losing a correct answer to a disobedience the caller cannot fix would be the worse outcome.
        val model = ScriptedModel(textAnswer("""{"city":"Oslo"}"""))

        val result = generateObjectJson(model, prompt, schema, mode = StructuredMode.Tool)

        assertEquals("Oslo", (result.value as JsonObject)["city"]?.jsonPrimitive?.content)
    }

    @Test
    fun `repairText fires on a tool-mode answer that will not parse`() = runTest {
        val model = ScriptedModel(toolAnswer("""{"city": """))
        var sawText: String? = null

        val result = generateObjectJson(
            model = model,
            prompt = prompt,
            schema = schema,
            repairText = { text, _ -> sawText = text; """{"city":"Bern"}""" },
            mode = StructuredMode.Tool,
        )

        // The hook sees the tool's arguments, not the run's empty text — one repair path, both modes.
        assertEquals("""{"city": """, sawText)
        assertEquals("Bern", (result.value as JsonObject)["city"]?.jsonPrimitive?.content)
    }

    @Test
    fun `response format stays the default`() = runTest {
        val model = ScriptedModel(textAnswer("""{"city":"Kyoto"}"""))

        generateObjectJson(model, prompt, schema)

        val sent = assertNotNull(model.seen)
        assertNotNull(sent.responseFormat)
        assertNull(sent.tools)
    }

    @Test
    fun `streamObject in tool mode reads the answer off the tool input deltas`() = runTest {
        val model = ScriptedModel(
            listOf(
                StreamPart.ToolInputStart("c1", "json"),
                StreamPart.ToolInputDelta("c1", """{"city":"""),
                StreamPart.ToolInputDelta("c1", """"Lima"}"""),
                StreamPart.ToolInputEnd("c1"),
                StreamPart.ToolCallPart(Content.ToolCall("c1", "json", """{"city":"Lima"}""")),
                StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.ToolCalls, raw = "tool_calls")),
            ),
        )

        val events = streamObject(
            model = model,
            prompt = prompt,
            output = ObjectOutput.Object(schema),
            mode = StructuredMode.Tool,
        ).toList()

        val finish = events.filterIsInstance<ObjectStreamEvent.Finish<JsonObject>>().single()
        assertEquals("Lima", (finish.result.value as JsonObject)["city"]?.jsonPrimitive?.content)
    }

    @Test
    fun `streamObject in tool mode still reads an answer given in prose`() = runTest {
        // The round is a tool-choice violation — the model was told to call `json` and did not — and
        // the runtime reports it as an error event. In tool mode that report is the answer's own text
        // arriving the other way, so it is read rather than raised; see ToolChoiceEnforcementTest.
        val model = ScriptedModel(textAnswer("""{"city":"Oslo"}"""))
        val errors = mutableListOf<Throwable>()

        val events = streamObject(
            model = model,
            prompt = prompt,
            output = ObjectOutput.Object(schema),
            mode = StructuredMode.Tool,
            onError = { errors += it },
        ).toList()

        val finish = events.filterIsInstance<ObjectStreamEvent.Finish<JsonObject>>().single()
        assertEquals("Oslo", (finish.result.value as JsonObject)["city"]?.jsonPrimitive?.content)
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `a streamed tool answer is read once, not once per representation`() = runTest {
        // The normal provider shape: input deltas AND the assembled call at the end. Relabelling both
        // would feed the reader `{...}{...}`, which parses only by the truncation-salvage path.
        val model = ScriptedModel(
            listOf(
                StreamPart.ToolInputStart("c1", "json"),
                StreamPart.ToolInputDelta("c1", """{"city":"""),
                StreamPart.ToolInputDelta("c1", """"Lima"}"""),
                StreamPart.ToolInputEnd("c1"),
                StreamPart.ToolCallPart(Content.ToolCall("c1", "json", """{"city":"Lima"}""")),
                StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.ToolCalls, raw = "tool_calls")),
            ),
        )

        val events = streamObject(
            model = model,
            prompt = prompt,
            output = ObjectOutput.Object(schema),
            mode = StructuredMode.Tool,
        ).toList()

        val text = events.filterIsInstance<ObjectStreamEvent.TextDelta>().joinToString("") { it.delta }
        assertEquals("""{"city":"Lima"}""", text)
    }

    @Test
    fun `onError is told about a final object that will not fit, instead of it being thrown`() = runTest {
        val model = ScriptedModel(textAnswer("not json at all"))
        var reported: Throwable? = null

        val events = streamObject(
            model = model,
            prompt = prompt,
            output = ObjectOutput.Enum(listOf("a", "b")),
            onError = { reported = it },
        ).toList()

        // The stream ends quietly, so a UI keeps whatever it already rendered.
        assertTrue(events.none { it is ObjectStreamEvent.Finish })
        assertTrue(reported is NoObjectGeneratedError)
    }

    @Test
    fun `without a handler the failure still throws`() = runTest {
        val model = ScriptedModel(textAnswer("not json at all"))

        // The behaviour every existing caller relies on, unchanged.
        assertFailsWith<NoObjectGeneratedError> {
            streamObject(model, prompt, ObjectOutput.Enum(listOf("a", "b"))).toList()
        }
    }

    @Test
    fun `a provider error mid-stream is reported and the object still settles`() = runTest {
        val model = ScriptedModel(
            listOf(
                StreamPart.TextStart("t0"),
                StreamPart.TextDelta("t0", """{"city":"Baku"}"""),
                StreamPart.Error(IllegalStateException("connection reset")),
                StreamPart.TextEnd("t0"),
                StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.Stop, raw = "stop")),
            ),
        )
        val reported = mutableListOf<Throwable>()

        val events = streamObject(
            model = model,
            prompt = prompt,
            output = ObjectOutput.Object(schema),
            onError = { reported += it },
        ).toList()

        // The text before the error was a complete object; ending the stream would have discarded it.
        assertEquals("connection reset", reported.single().message)
        val finish = events.filterIsInstance<ObjectStreamEvent.Finish<JsonObject>>().single()
        assertEquals("Baku", (finish.result.value as JsonObject)["city"]?.jsonPrimitive?.content)
    }
}
