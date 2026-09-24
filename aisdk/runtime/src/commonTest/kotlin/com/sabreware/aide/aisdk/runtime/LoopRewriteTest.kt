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
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

/**
 * The three run-control additions, tested where they bite: the prompt the NEXT round actually receives
 * ([StepPlan.messages]/[StepPlan.instructions]), what a tool is allowed to see ([ToolCallContext]), and
 * what a non-streaming caller can observe (`generateText(onEvent = …)`).
 */
class LoopRewriteTest {

    private class ScriptedModel(private val rounds: List<List<StreamPart>>) : LanguageModel {
        override val provider: String = "scripted"
        override val modelId: String = "scripted-1"
        val seenPrompts = mutableListOf<Prompt>()

        override suspend fun doStream(options: CallOptions): StreamResult {
            val index = seenPrompts.size
            seenPrompts += options.prompt
            return StreamResult(rounds[index.coerceAtMost(rounds.lastIndex)].asFlow())
        }

        override suspend fun doGenerate(options: CallOptions): GenerateResult {
            val index = seenPrompts.size
            seenPrompts += options.prompt
            return assembleGenerateResult(rounds[index.coerceAtMost(rounds.lastIndex)].asFlow())
        }
    }

    private fun toolRound(id: String) = listOf(
        StreamPart.ToolCallPart(Content.ToolCall(id, "probe", "{}")),
        StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.ToolCalls, raw = "tool_use")),
    )

    private fun textRound(text: String) = listOf(
        StreamPart.TextStart("t0"),
        StreamPart.TextDelta("t0", text),
        StreamPart.TextEnd("t0"),
        StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.Stop, raw = "end_turn")),
    )

    private val prompt: Prompt = listOf(ModelMessage.User(listOf(UserPart.Text("go"))))

    @Test
    fun `a messages rewrite replaces the conversation and carries forward`() = runTest {
        val model = ScriptedModel(listOf(toolRound("c1"), textRound("done")))
        val compacted: Prompt = listOf(ModelMessage.User(listOf(UserPart.Text("summary of everything so far"))))

        streamText(
            model = model,
            prompt = prompt,
            toolExecutor = { _, _ -> ToolOutput.Text("ok") },
            stopWhen = stepCountIs(5),
            prepareStep = { context ->
                // Rewrite between round 0 and round 1, the compaction moment.
                if (context.stepIndex == 1) StepPlan(messages = compacted) else null
            },
        ).toList()

        // Round 1 saw ONLY the rewrite — the original user turn and round 0's appended turns are gone.
        assertEquals(compacted, model.seenPrompts[1])
    }

    @Test
    fun `an instructions override replaces the leading system turn for the rest of the run`() = runTest {
        val model = ScriptedModel(listOf(toolRound("c1"), textRound("done")))

        streamText(
            model = model,
            prompt = prompt,
            instructions = "phase one",
            toolExecutor = { _, _ -> ToolOutput.Text("ok") },
            stopWhen = stepCountIs(5),
            prepareStep = { context ->
                if (context.stepIndex == 1) StepPlan(instructions = "phase two") else null
            },
        ).toList()

        assertEquals("phase one", (model.seenPrompts[0].first() as ModelMessage.System).content)
        val second = model.seenPrompts[1]
        assertEquals("phase two", (second.first() as ModelMessage.System).content)
        // Exactly one system turn — the override REPLACED, not stacked.
        assertEquals(1, second.count { it is ModelMessage.System })
        // And round 0's appended turns survived an instructions-only override.
        assertTrue(second.any { it is ModelMessage.Tool })
    }

    @Test
    fun `a tool sees the round's messages and index, not just its arguments`() = runTest {
        val model = ScriptedModel(listOf(toolRound("c1"), textRound("done")))
        var seen: ToolCallContext? = null

        streamText(
            model = model,
            prompt = prompt,
            toolExecutor = { _, context ->
                seen = context
                ToolOutput.Text("ok")
            },
            stopWhen = stepCountIs(5),
        ).toList()

        val context = assertNotNull(seen)
        assertEquals(0, context.stepIndex)
        // The same list the request carried — the user's actual words are reachable from a tool.
        assertEquals(model.seenPrompts[0], context.messages)
    }

    @Test
    fun `generateText onEvent sees the run's events in order`() = runTest {
        val model = ScriptedModel(listOf(toolRound("c1"), textRound("done")))
        val events = mutableListOf<RunEvent>()

        val result = generateText(
            model = model,
            prompt = prompt,
            toolExecutor = { _, _ -> ToolOutput.Text("ok") },
            stopWhen = stepCountIs(5),
            onEvent = { events += it },
        )

        assertEquals("done", result.text)
        assertTrue(events.filterIsInstance<RunEvent.ToolResult>().isNotEmpty())
        assertEquals(2, events.filterIsInstance<RunEvent.StepFinish>().size)
        assertTrue(events.last() is RunEvent.Finish)
    }

    @Test
    fun `a prepareCall plan narrows an agent for one invocation only`() = runTest {
        val model = ScriptedModel(listOf(textRound("hi"), textRound("hi")))
        val agent = com.sabreware.aide.aisdk.runtime.agent.Agent(
            name = "templated",
            model = model,
            instructions = "default instructions",
            prepareCall = { options ->
                if (options == null) {
                    null
                } else {
                    com.sabreware.aide.aisdk.runtime.agent.AgentCallPlan(
                        instructions = "for ${(options as JsonPrimitive).content}",
                    )
                }
            },
        )

        agent.generate(prompt, callOptions = JsonPrimitive("alice"))
        agent.generate(prompt)

        assertEquals("for alice", (model.seenPrompts[0].first() as ModelMessage.System).content)
        assertEquals("default instructions", (model.seenPrompts[1].first() as ModelMessage.System).content)
    }
}
