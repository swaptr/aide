package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The reference's `tool choice enforcement` cases (`generate-text.test.ts`, `stream-text.test.ts`),
 * translated.
 *
 * A model told it MUST call a tool and answering in prose used to come back as an ordinary text step —
 * the forced call silently became a paragraph nobody executed. Both entry points now refuse that
 * response, and the streaming one refuses it AFTER the completed call's usage has been recorded and
 * without ever retrying it.
 */
class ToolChoiceEnforcementTest {

    private class ScriptedModel(private val rounds: List<List<StreamPart>>) : LanguageModel {
        override val provider: String = "scripted"
        override val modelId: String = "scripted-1"
        var calls = 0

        override suspend fun doStream(options: CallOptions): StreamResult {
            val index = calls++
            return StreamResult(rounds[index.coerceAtMost(rounds.lastIndex)].asFlow())
        }

        override suspend fun doGenerate(options: CallOptions): GenerateResult {
            val index = calls++
            return assembleGenerateResult(rounds[index.coerceAtMost(rounds.lastIndex)].asFlow())
        }
    }

    private val prompt: Prompt = listOf(ModelMessage.User(listOf(UserPart.Text("test-input"))))
    private val objectSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("value", buildJsonObject { put("type", "string") }) })
    }
    private val tool1 = Tool.Function(name = "tool1", inputSchema = objectSchema)
    private val tool2 = Tool.Function(name = "tool2", inputSchema = objectSchema)
    private val usage = Usage(Usage.InputTokens(total = 3), Usage.OutputTokens(total = 10))

    private fun prose(text: String = "No tool call.") = listOf(
        StreamPart.ReasoningStart("r"),
        StreamPart.ReasoningDelta("r", "I will not call the tool."),
        StreamPart.ReasoningEnd("r"),
        StreamPart.TextStart("t"),
        StreamPart.TextDelta("t", text),
        StreamPart.TextEnd("t"),
        StreamPart.Finish(usage, FinishReason(FinishReason.Unified.Stop)),
    )

    private fun calling(name: String) = listOf(
        StreamPart.ToolCallPart(Content.ToolCall("call-1", name, """{ "value": "value" }""")),
        StreamPart.Finish(usage, FinishReason(FinishReason.Unified.ToolCalls)),
    )

    private fun options(choice: ToolChoice?, vararg tools: Tool) =
        CallOptions(prompt = prompt, tools = tools.toList(), toolChoice = choice)

    @Test
    fun `generateText rejects a round that answered in prose under a required tool choice`() = runTest {
        val model = ScriptedModel(listOf(prose()))

        val error = assertFailsWith<ToolChoiceViolationError> {
            generateText(model, prompt, options(ToolChoice.Required, tool1))
        }

        assertEquals("AI_ToolChoiceViolationError", error.errorName)
        assertEquals(
            "Model response did not contain a tool call even though tool choice was required.",
            error.message,
        )
        assertEquals(ToolChoice.Required, error.toolChoice)
        assertEquals(FinishReason.Unified.Stop, error.finishReason.unified)
        assertEquals("scripted", error.provider)
        assertEquals("scripted-1", error.modelId)
        // The content, verbatim and in order — what a caller inspects to recover a call written as prose.
        assertEquals(
            listOf(Content.Reasoning("I will not call the tool."), Content.Text("No tool call.")),
            error.content,
        )
    }

    @Test
    fun `a call to a different tool than the one required is a violation naming the required tool`() = runTest {
        val model = ScriptedModel(listOf(calling("tool2")))

        val error = assertFailsWith<ToolChoiceViolationError> {
            generateText(model, prompt, options(ToolChoice.Specific("tool1"), tool1, tool2))
        }

        assertEquals("Model response did not contain a call to the required tool 'tool1'.", error.message)
        assertEquals(ToolChoice.Specific("tool1"), error.toolChoice)
    }

    @Test
    fun `the tool choice a plan sets for the round is the one enforced`() = runTest {
        val model = ScriptedModel(listOf(prose()))

        val error = assertFailsWith<ToolChoiceViolationError> {
            generateText(
                model,
                prompt,
                options(ToolChoice.Auto, tool1),
                prepareStep = { StepPlan(toolChoice = ToolChoice.Required) },
            )
        }

        assertEquals(ToolChoice.Required, error.toolChoice)
    }

    @Test
    fun `a plan may relax a required tool choice, and prose is then an answer`() = runTest {
        val model = ScriptedModel(listOf(prose()))

        val result = generateText(
            model,
            prompt,
            options(ToolChoice.Required, tool1),
            prepareStep = { StepPlan(toolChoice = ToolChoice.Auto) },
        )

        assertEquals("No tool call.", result.text)
    }

    @Test
    fun `auto and none enforce nothing`() = runTest {
        assertEquals("No tool call.", generateText(ScriptedModel(listOf(prose())), prompt, options(ToolChoice.Auto, tool1)).text)
        assertEquals("No tool call.", generateText(ScriptedModel(listOf(prose())), prompt, options(ToolChoice.None, tool1)).text)
        assertEquals("No tool call.", generateText(ScriptedModel(listOf(prose())), prompt, options(null, tool1)).text)
    }

    @Test
    fun `a call the provider serialized as text stays recoverable on the error`() = runTest {
        val serializedToolCall = """{"toolName":"tool1","input":{"value":"value"}}"""
        val model = ScriptedModel(listOf(prose(serializedToolCall)))

        val error = assertFailsWith<ToolChoiceViolationError> {
            generateText(model, prompt, options(ToolChoice.Required, tool1))
        }

        val text = error.content.filterIsInstance<Content.Text>().single().text
        assertEquals(serializedToolCall, text)
        val recovered = Json.parseToJsonElement(text).jsonObject
        assertEquals("tool1", recovered["toolName"]?.jsonPrimitive?.content)
        assertEquals("value", recovered["input"]?.jsonObject?.get("value")?.jsonPrimitive?.content)
    }

    @Test
    fun `streamText reports the violation after the completed call, ends the round as an error, and never retries it`() =
        runTest {
            val model = ScriptedModel(listOf(prose()))

            val events = streamText(
                model,
                prompt,
                options(ToolChoice.Required, tool1),
                // A retry policy that would replay any provider error — and must not replay this one.
                streamRetries = StreamRetries(maxRetries = 2),
            ).toList()

            assertEquals(1, model.calls)
            val error = events.filterIsInstance<RunEvent.Error>().single().error
            assertIs<ToolChoiceViolationError>(error)
            // The completed call was reported first: its finish part, with the usage, precedes the error.
            val finishAt = events.indexOfFirst { (it as? RunEvent.Part)?.part is StreamPart.Finish }
            assertTrue(finishAt in 0 until events.indexOfFirst { it is RunEvent.Error })
            val step = events.filterIsInstance<RunEvent.StepFinish>().single().step
            assertEquals(usage, step.usage)
            assertEquals(FinishReason.Unified.Error, step.finishReason.unified)
            assertEquals("No tool call.", step.text)
            assertEquals(1, events.filterIsInstance<RunEvent.Finish>().size)
        }
}
