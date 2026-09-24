package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The whole point of the project, tested where it actually has to hold: across a tool round in the loop.
 *
 * A provider can capture a signature perfectly and the run still breaks if the loop rebuilds the
 * assistant turn from display text, filters it to "the parts we understand", or reorders it. That is the
 * bug every LLM framework currently has an open issue for — vercel/ai#11602, pydantic-ai#2293,
 * openai-agents-js#770 — so the loop is tested by inspecting the prompt the SECOND round actually
 * receives.
 */
class ToolLoopTest {

    private val signature = "ErUBCkYIBRgCIkDXm2n4Q1p9sT7yZ0aVbNc"

    /** Records every prompt it is called with, and replays a scripted response per round. */
    private class ScriptedModel(private val rounds: List<List<StreamPart>>) : LanguageModel {
        override val provider: String = "scripted"
        override val modelId: String = "scripted-1"
        val seenPrompts = mutableListOf<Prompt>()

        override suspend fun doStream(options: CallOptions): StreamResult {
            val index = seenPrompts.size
            seenPrompts += options.prompt
            return StreamResult(rounds[index.coerceAtMost(rounds.lastIndex)].asFlow())
        }

        // Assembled from the same script. A fake that answers only doStream exercises the streaming
        // path twice and the non-streaming path never, which is how every provider's doGenerate and
        // every middleware's wrapGenerate stayed unreachable from the runtime.
        override suspend fun doGenerate(options: CallOptions): GenerateResult {
            val index = seenPrompts.size
            seenPrompts += options.prompt
            return assembleGenerateResult(rounds[index.coerceAtMost(rounds.lastIndex)].asFlow())
        }
    }

    private fun reasoningWithSignature(text: String) = listOf(
        StreamPart.ReasoningStart("r0"),
        StreamPart.ReasoningDelta("r0", text),
        StreamPart.ReasoningEnd(
            "r0",
            providerMetadata = mapOf(
                "scripted" to buildJsonObject { put("signature", signature) },
            ),
        ),
    )

    private fun toolCall(id: String, name: String, input: String) = listOf(
        StreamPart.ToolCallPart(Content.ToolCall(id, name, input)),
        StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.ToolCalls, raw = "tool_use")),
    )

    private fun finalAnswer(text: String) = listOf(
        StreamPart.TextStart("t0"),
        StreamPart.TextDelta("t0", text),
        StreamPart.TextEnd("t0"),
        StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.Stop, raw = "end_turn")),
    )

    private val prompt: Prompt = listOf(ModelMessage.User(listOf(UserPart.Text("what's on tuesday?"))))

    private val echoTool = ToolExecutor { _, _ -> ToolOutput.Text("nothing scheduled") }

    @Test
    fun `the second round receives the signed reasoning block, first and intact`() = runTest {
        val model = ScriptedModel(
            listOf(
                reasoningWithSignature("Check the calendar.") + toolCall("c1", "calendar", """{"d":"tue"}"""),
                finalAnswer("You're free."),
            ),
        )

        generateText(model, prompt, toolExecutor = echoTool, stopWhen = stepCountIs(5))

        assertEquals(2, model.seenPrompts.size, "the loop should have run a second round")
        val secondRound = model.seenPrompts[1]
        val assistant = secondRound.filterIsInstance<ModelMessage.Assistant>().single()

        // Anthropic requires the replayed turn to BEGIN with its thinking block.
        val reasoning = assistant.content.first() as AssistantPart.Reasoning
        assertEquals("Check the calendar.", reasoning.text)
        // And the signature must arrive byte-for-byte, or the request is rejected outright.
        assertEquals(
            signature,
            reasoning.providerOptions?.get("scripted")?.get("signature")?.jsonPrimitive?.content,
        )
        assertTrue(assistant.content[1] is AssistantPart.ToolCall)
    }

    @Test
    fun `the tool result reaches the second round as a tool turn`() = runTest {
        val model = ScriptedModel(
            listOf(
                toolCall("c1", "calendar", "{}"),
                finalAnswer("done"),
            ),
        )

        generateText(model, prompt, toolExecutor = echoTool, stopWhen = stepCountIs(5))

        val toolTurn = model.seenPrompts[1].filterIsInstance<ModelMessage.Tool>().single()
        val result = toolTurn.content.single() as ToolPart.Result
        assertEquals("calendar", result.toolName)
        assertEquals("c1", result.toolCallId)
    }

    @Test
    fun `the original prompt is never mutated`() = runTest {
        val model = ScriptedModel(listOf(toolCall("c1", "t", "{}"), finalAnswer("done")))

        generateText(model, prompt, toolExecutor = echoTool, stopWhen = stepCountIs(5))

        // A loop that appends in place corrupts a prompt the caller may reuse for another run.
        assertEquals(1, prompt.size)
        assertEquals(1, model.seenPrompts[0].size)
    }

    // --- stopping ------------------------------------------------------------------------------

    @Test
    fun `the default runs a single round even when a tool was called`() = runTest {
        val model = ScriptedModel(listOf(toolCall("c1", "t", "{}"), finalAnswer("never")))

        val result = generateText(model, prompt, toolExecutor = echoTool)

        // stepCountIs(1) by default: an unbounded loop is not something to opt OUT of.
        assertEquals(1, result.steps.size)
        assertEquals(1, model.seenPrompts.size)
    }

    @Test
    fun `stepCountIs caps the rounds`() = runTest {
        // Always asks for a tool: without a cap this never terminates.
        val model = ScriptedModel(listOf(toolCall("c", "t", "{}")))

        val result = generateText(model, prompt, toolExecutor = echoTool, stopWhen = stepCountIs(3))

        assertEquals(3, result.steps.size)
    }

    @Test
    fun `the loop ends on its own when no tool is called`() = runTest {
        val model = ScriptedModel(listOf(finalAnswer("just an answer")))

        val result = generateText(model, prompt, toolExecutor = echoTool, stopWhen = stepCountIs(10))

        assertEquals(1, result.steps.size)
        assertEquals("just an answer", result.text)
    }

    @Test
    fun `hasToolCall stops once the named tool runs`() = runTest {
        val model = ScriptedModel(
            listOf(
                toolCall("c1", "search", "{}"),
                toolCall("c2", "submit", "{}"),
                toolCall("c3", "search", "{}"),
            ),
        )

        val result = generateText(
            model,
            prompt,
            toolExecutor = echoTool,
            stopWhen = anyOf(hasToolCall("submit"), stepCountIs(10)),
        )

        assertEquals(2, result.steps.size)
    }

    @Test
    fun `with no executor the loop stops after one round`() = runTest {
        val model = ScriptedModel(listOf(toolCall("c1", "t", "{}"), finalAnswer("never")))

        val result = generateText(model, prompt, stopWhen = stepCountIs(5))

        // Nothing can answer the call, so going again would just repeat it forever.
        assertEquals(1, result.steps.size)
    }

    // --- failures ------------------------------------------------------------------------------

    @Test
    fun `a throwing tool becomes an error result instead of ending the run`() = runTest {
        val model = ScriptedModel(listOf(toolCall("c1", "boom", "{}"), finalAnswer("recovered")))
        val failing = ToolExecutor { _, _ -> error("disk on fire") }

        val result = generateText(model, prompt, toolExecutor = failing, stopWhen = stepCountIs(5))

        // The model can read an error and try something else; an exception takes the conversation with it.
        val output = result.steps[0].toolResults.single().output as ToolOutput.ErrorText
        assertTrue(output.value.contains("disk on fire"), output.value)
        assertEquals("recovered", result.text)
    }

    @Test
    fun `a provider-executed call is not run again locally`() = runTest {
        var localRuns = 0
        val model = ScriptedModel(
            listOf(
                listOf(
                    StreamPart.ToolCallPart(
                        Content.ToolCall("c1", "web_search", "{}", providerExecuted = true),
                    ),
                    StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.ToolCalls)),
                ),
            ),
        )

        generateText(
            model,
            prompt,
            toolExecutor = { _, _ -> localRuns++; ToolOutput.Text("x") },
            stopWhen = stepCountIs(3),
        )

        // It already ran on the vendor's servers; running it again duplicates its effects.
        assertEquals(0, localRuns)
    }

    // --- events and accounting -----------------------------------------------------------------

    @Test
    fun `events arrive in order with their step index`() = runTest {
        val model = ScriptedModel(
            listOf(
                reasoningWithSignature("think") + toolCall("c1", "t", "{}"),
                finalAnswer("done"),
            ),
        )

        val events = streamText(model, prompt, toolExecutor = echoTool, stopWhen = stepCountIs(5)).toList()

        val stepFinishes = events.filterIsInstance<RunEvent.StepFinish>()
        assertEquals(listOf(0, 1), stepFinishes.map { it.stepIndex })
        // The tool result belongs to the round that asked for it.
        assertEquals(0, events.filterIsInstance<RunEvent.ToolResult>().single().stepIndex)
        assertTrue(events.last() is RunEvent.Finish)
    }

    @Test
    fun `text deltas can be read on their own`() = runTest {
        val model = ScriptedModel(listOf(finalAnswer("hello there")))

        val text = streamText(model, prompt).textDeltas().toList().joinToString("")

        assertEquals("hello there", text)
    }

    @Test
    fun `usage sums across rounds`() = runTest {
        fun round(input: Int, output: Int, more: Boolean) = listOf(
            StreamPart.Finish(
                Usage(Usage.InputTokens(total = input), Usage.OutputTokens(total = output)),
                FinishReason(if (more) FinishReason.Unified.ToolCalls else FinishReason.Unified.Stop),
            ),
        )
        val model = ScriptedModel(
            listOf(
                listOf(StreamPart.ToolCallPart(Content.ToolCall("c", "t", "{}"))) + round(10, 5, true),
                round(20, 7, false),
            ),
        )

        val result = generateText(model, prompt, toolExecutor = echoTool, stopWhen = stepCountIs(5))

        assertEquals(30, result.usage.inputTokens.total)
        assertEquals(12, result.usage.outputTokens.total)
    }

    @Test
    fun `an unreported count stays unknown rather than becoming zero`() = runTest {
        val model = ScriptedModel(listOf(finalAnswer("x")))

        val result = generateText(model, prompt)

        // Zero reads as "this was free", which is a different and wrong claim.
        assertEquals(null, result.usage.inputTokens.total)
    }

    @Test
    fun `the run reports the messages it appended so a caller can persist them`() = runTest {
        val model = ScriptedModel(
            listOf(
                reasoningWithSignature("think") + toolCall("c1", "t", "{}"),
                finalAnswer("done"),
            ),
        )

        val result = generateText(model, prompt, toolExecutor = echoTool, stopWhen = stepCountIs(5))

        // assistant, tool, assistant — and the first assistant still carries its signature, which is what
        // makes the persisted conversation replayable tomorrow.
        assertEquals(3, result.messages.size)
        val firstAssistant = result.messages[0] as ModelMessage.Assistant
        val reasoning = firstAssistant.content.first() as AssistantPart.Reasoning
        assertNotNull(reasoning.providerOptions?.get("scripted"))
    }
}
