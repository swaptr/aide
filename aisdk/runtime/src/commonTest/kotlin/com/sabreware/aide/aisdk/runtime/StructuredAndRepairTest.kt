package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.NoObjectGeneratedError
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.TypeValidationError
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class StructuredAndRepairTest {

    @Serializable
    private data class Person(val name: String, val age: Int)

    private class Replying(private val rounds: List<List<StreamPart>>) : LanguageModel {
        override val provider: String = "t"
        override val modelId: String = "t-1"
        var lastOptions: CallOptions? = null
        private var index = 0

        override suspend fun doStream(options: CallOptions): StreamResult {
            lastOptions = options
            return StreamResult(rounds[index.coerceAtMost(rounds.lastIndex)].also { index++ }.asFlow())
        }

        // Assembled from the same script rather than throwing. `generateText` and `generateObject` reach
        // the model through doGenerate now — a fake that answers only doStream tests the streaming path
        // twice and the non-streaming path never, which is how half the specification's call surface
        // stayed unreachable and untested.
        override suspend fun doGenerate(options: CallOptions): GenerateResult {
            lastOptions = options
            val round = rounds[index.coerceAtMost(rounds.lastIndex)].also { index++ }
            return assembleGenerateResult(round.asFlow())
        }
    }

    private fun says(text: String) = listOf(
        listOf(
            StreamPart.TextStart("t"),
            StreamPart.TextDelta("t", text),
            StreamPart.TextEnd("t"),
            StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.Stop)),
        ),
    )

    private val prompt: Prompt = listOf(ModelMessage.User(listOf(UserPart.Text("who?"))))
    private val schema = buildJsonObject { put("type", "object") }

    // --- structured output ----------------------------------------------------------------------

    @Test
    fun `clean JSON decodes into the target type`() = runTest {
        val model = Replying(says("""{"name":"Ada","age":36}"""))

        val result = generateObject(model, prompt, schema, Person.serializer())

        assertEquals(Person("Ada", 36), result.value)
    }

    @Test
    fun `the schema is sent as a response format, not merely as prompt text`() = runTest {
        val model = Replying(says("{}"))

        generateObjectJson(model, prompt, schema, schemaName = "person")

        // A provider with constrained decoding enforces it at generation time; guidance in the prompt
        // cannot.
        val format = model.lastOptions?.responseFormat
        assertTrue(format is com.sabreware.aide.aisdk.ResponseFormat.Json)
        assertEquals("person", format.name)
        assertEquals(schema, format.schema)
    }

    @Test
    fun `a fenced block is unwrapped rather than treated as a failure`() = runTest {
        val model = Replying(says("```json\n{\"name\":\"Ada\",\"age\":36}\n```"))

        // Models told to return JSON still wrap it in a fence; losing the answer to that is a waste.
        assertEquals(Person("Ada", 36), generateObject(model, prompt, schema, Person.serializer()).value)
    }

    @Test
    fun `JSON is found even when the model narrates around it`() = runTest {
        val model = Replying(says("""Sure! Here you go: {"name":"Ada","age":36} Hope that helps."""))

        assertEquals(Person("Ada", 36), generateObject(model, prompt, schema, Person.serializer()).value)
    }

    @Test
    fun `the raw JSON is kept alongside the decoded value`() = runTest {
        val model = Replying(says("""{"name":"Ada","age":36,"extra":"kept"}"""))

        val result = generateObject(model, prompt, schema, Person.serializer())

        // The decoded type drops unknown fields; the raw value is what a bug report needs.
        assertEquals("kept", result.raw.jsonObject["extra"]?.jsonPrimitive?.content)
    }

    @Test
    fun `text that is not JSON at all fails with the text attached`() = runTest {
        val model = Replying(says("I'd rather not."))

        val error = assertFailsWith<NoObjectGeneratedError> {
            generateObjectJson(model, prompt, schema)
        }
        // A structured call that failed was still paid for: the text, the usage and the finish reason
        // ride on the error so diagnosing it does not mean running it again.
        assertTrue(error.message!!.contains("I'd rather not"), error.message!!)
        assertEquals("I'd rather not.", error.text)
        assertEquals(FinishReason.Unified.Stop, error.finishReason?.unified)
    }

    @Test
    fun `JSON of the wrong shape fails as a type error, not silently`() = runTest {
        val model = Replying(says("""{"name":"Ada"}"""))

        val error = assertFailsWith<NoObjectGeneratedError> {
            generateObject(model, prompt, schema, Person.serializer())
        }
        assertIs<TypeValidationError>(error.cause)
    }

    // --- tool repair ----------------------------------------------------------------------------

    private fun toolThen(text: String) = listOf(
        listOf(
            StreamPart.ToolCallPart(Content.ToolCall("c1", "lookup", "{not json")),
            StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.ToolCalls)),
        ),
        listOf(
            StreamPart.TextStart("t"),
            StreamPart.TextDelta("t", text),
            StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.Stop)),
        ),
    )

    @Test
    fun `malformed arguments are repaired before the tool runs`() = runTest {
        val model = Replying(toolThen("done"))
        var executedWith: String? = null

        generateText(
            model = model,
            prompt = prompt,
            toolExecutor = { call, _ -> executedWith = call.input; ToolOutput.Text("ok") },
            stopWhen = stepCountIs(5),
            repairToolCall = { call, _ -> call.copy(input = """{"q":"fixed"}""") },
        )

        assertEquals("""{"q":"fixed"}""", executedWith)
    }

    @Test
    fun `a valid call is never handed to the repair hook`() = runTest {
        val model = Replying(
            listOf(
                listOf(
                    StreamPart.ToolCallPart(Content.ToolCall("c1", "lookup", """{"q":"fine"}""")),
                    StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.ToolCalls)),
                ),
                says("done").single(),
            ),
        )
        var repairCalls = 0

        generateText(
            model = model,
            prompt = prompt,
            toolExecutor = { _, _ -> ToolOutput.Text("ok") },
            stopWhen = stepCountIs(5),
            repairToolCall = { call, _ -> repairCalls++; call },
        )

        // A hook that runs on every call is one that eventually rewrites a correct one.
        assertEquals(0, repairCalls)
    }

    @Test
    fun `declining to repair means the bad call is reported, never executed`() = runTest {
        val model = Replying(toolThen("recovered"))
        var executed = false

        val result = generateText(
            model = model,
            prompt = prompt,
            options = CallOptions(
                prompt = prompt,
                tools = listOf(Tool.Function("calendar", buildJsonObject { put("type", "object") })),
            ),
            toolExecutor = { _, _ -> executed = true; ToolOutput.Text("ok") },
            stopWhen = stepCountIs(5),
            repairToolCall = { _, _ -> null },
        )

        // Declining a repair is a refusal, and a refusal that still runs the call inverts the safety
        // default of the very hook it belongs to.
        assertFalse(executed, "a declined repair still executed the malformed call")
        val output = result.steps[0].toolResults.single().output
        assertTrue(output is ToolOutput.ErrorText, "the model was not told why its call was rejected")
        // The model reads the error and tries something else, which is often the better outcome.
        assertEquals("recovered", result.text)
    }

    // --- approvals ------------------------------------------------------------------------------

    private fun approvalRound() = listOf(
        listOf(
            StreamPart.ToolCallPart(Content.ToolCall("c1", "delete_everything", "{}")),
            StreamPart.ToolApprovalRequestPart(Content.ToolApprovalRequest("a1", "c1")),
            StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.ToolCalls)),
        ),
        listOf(
            StreamPart.TextStart("t"),
            StreamPart.TextDelta("t", "understood"),
            StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.Stop)),
        ),
    )

    @Test
    fun `a denied tool does not run and the model is told`() = runTest {
        val model = Replying(approvalRound())
        var ran = false

        val result = generateText(
            model = model,
            prompt = prompt,
            toolExecutor = { _, _ -> ran = true; ToolOutput.Text("boom") },
            stopWhen = stepCountIs(5),
            approveTool = { _, _ -> false },
        )

        assertTrue(!ran, "a denied tool must not execute")
        // Denial is an outcome, not an error: the model can choose another path.
        assertTrue(result.steps[0].toolResults.single().output is ToolOutput.ExecutionDenied)
    }

    @Test
    fun `an approved tool runs`() = runTest {
        val model = Replying(approvalRound())
        var ran = false

        generateText(
            model = model,
            prompt = prompt,
            toolExecutor = { _, _ -> ran = true; ToolOutput.Text("done") },
            stopWhen = stepCountIs(5),
            approveTool = { _, _ -> true },
        )

        assertTrue(ran)
    }

    @Test
    fun `with no handler nothing is approved`() = runTest {
        val model = Replying(approvalRound())
        var ran = false

        generateText(
            model = model,
            prompt = prompt,
            toolExecutor = { _, _ -> ran = true; ToolOutput.Text("done") },
            stopWhen = stepCountIs(5),
        )

        // Defaulting to yes would let a provider run tools the host never agreed to.
        assertTrue(!ran)
    }
}
