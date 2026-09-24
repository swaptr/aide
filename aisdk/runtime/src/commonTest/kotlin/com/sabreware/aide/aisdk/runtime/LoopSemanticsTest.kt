package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.LanguageModelMiddleware
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import com.sabreware.aide.aisdk.withMiddleware
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The loop's behaviour where a wrong answer is quiet.
 *
 * Everything here is a property nothing else pins: a run can pair the wrong result with the wrong call,
 * turn a user's cancel into a tool error and keep going, drop a denial the model was owed, or forget the
 * caller's tools between rounds — and in each case produce output that reads as a working conversation.
 */
class LoopSemanticsTest {

    /** Records the options of every round, and replays a scripted response per round. */
    private class ScriptedModel(private val rounds: List<List<StreamPart>>) : LanguageModel {
        override val provider: String = "scripted"
        override val modelId: String = "scripted-1"
        val seenOptions = mutableListOf<CallOptions>()

        override suspend fun doStream(options: CallOptions): StreamResult {
            val index = seenOptions.size
            seenOptions += options
            return StreamResult(rounds[index.coerceAtMost(rounds.lastIndex)].asFlow())
        }

        override suspend fun doGenerate(options: CallOptions): GenerateResult {
            val index = seenOptions.size
            seenOptions += options
            return assembleGenerateResult(rounds[index.coerceAtMost(rounds.lastIndex)].asFlow())
        }
    }

    private val prompt: Prompt = listOf(ModelMessage.User(listOf(UserPart.Text("go"))))

    private fun toolCall(id: String, name: String) =
        StreamPart.ToolCallPart(Content.ToolCall(id, name, "{}"))

    private fun finish(reason: FinishReason.Unified, usage: Usage = Usage()) =
        StreamPart.Finish(usage, FinishReason(reason))

    private fun answer(text: String) = listOf(
        StreamPart.TextStart("t"),
        StreamPart.TextDelta("t", text),
        StreamPart.TextEnd("t"),
        finish(FinishReason.Unified.Stop),
    )

    // ---- Several calls in one round --------------------------------------------------------------

    @Test
    fun `three calls in one round each get their own result, paired by id and in call order`() = runTest {
        val model = ScriptedModel(
            listOf(
                listOf(
                    toolCall("c1", "alpha"),
                    toolCall("c2", "beta"),
                    toolCall("c3", "gamma"),
                    finish(FinishReason.Unified.ToolCalls),
                ),
                answer("done"),
            ),
        )
        // The first tool finishes LAST. Results are awaited in call order rather than completion order,
        // so a refactor that collects them as they land reorders the tool turn and silently mispairs
        // every result with the call beside it.
        val gate = CompletableDeferred<Unit>()

        val result = generateText(
            model = model,
            prompt = prompt,
            toolExecutor = { call, _ ->
                if (call.toolCallId == "c1") gate.await() else gate.complete(Unit)
                ToolOutput.Text("out-${call.toolCallId}")
            },
            stopWhen = stepCountIs(5),
        )

        val toolTurn = assertIs<ModelMessage.Tool>(result.messages[1])
        val results = toolTurn.content.filterIsInstance<ToolPart.Result>()
        assertEquals(listOf("c1", "c2", "c3"), results.map { it.toolCallId })
        assertEquals(listOf("alpha", "beta", "gamma"), results.map { it.toolName })
        results.forEach { part ->
            assertEquals("out-${part.toolCallId}", assertIs<ToolOutput.Text>(part.output).value)
        }
        assertEquals(3, result.steps[0].toolResults.size)
    }

    // ---- Cancellation ------------------------------------------------------------------------------

    @Test
    fun `cancelling the run cancels the tool instead of turning it into an error result`() = runTest {
        val model = ScriptedModel(
            listOf(listOf(toolCall("c1", "slow"), finish(FinishReason.Unified.ToolCalls)), answer("done")),
        )
        val started = CompletableDeferred<Unit>()
        val toolCancelled = CompletableDeferred<Boolean>()
        val events = mutableListOf<RunEvent>()

        val collector = launch {
            streamText(
                model = model,
                prompt = prompt,
                toolExecutor = { _, _ ->
                    started.complete(Unit)
                    try {
                        CompletableDeferred<ToolOutput>().await()
                    } finally {
                        toolCancelled.complete(true)
                    }
                },
                stopWhen = stepCountIs(5),
            ).toList(events)
        }

        started.await()
        collector.cancel()

        // Swapping the two catch clauses in the tool executor would make this a ToolError result and let
        // the loop run another round: a user cancel that looks exactly like a tool that failed, and a
        // second billed request after the user asked for none.
        assertEquals(true, toolCancelled.await())
        assertTrue(events.none { it is RunEvent.ToolResult }, "a cancelled tool produced a result")
        assertTrue(events.none { it is RunEvent.Finish }, "a cancelled run reported a finish")
    }

    @Test
    fun `a tool that runs past its timeout is an error result and the run carries on`() = runTest {
        val model = ScriptedModel(
            listOf(listOf(toolCall("c1", "slow"), finish(FinishReason.Unified.ToolCalls)), answer("done")),
        )

        val result = generateText(
            model = model,
            prompt = prompt,
            toolExecutor = { _, _ -> CompletableDeferred<ToolOutput>().await() },
            stopWhen = stepCountIs(5),
            timeouts = RunTimeouts(toolMs = 50),
        )

        // The timeout is caught BEFORE the blanket cancellation clause it would otherwise fall into.
        // Reversed, a slow tool would cancel the whole run instead of reporting itself to the model.
        val output = result.steps[0].toolResults.single().output
        assertIs<ToolOutput.ErrorText>(output)
        assertEquals("done", result.text)
    }

    // ---- Approval ----------------------------------------------------------------------------------

    @Test
    fun `a denied tool is reported to the model even with no executor at all`() = runTest {
        val model = ScriptedModel(
            listOf(
                listOf(
                    toolCall("c1", "wipe"),
                    StreamPart.ToolApprovalRequestPart(Content.ToolApprovalRequest("ap1", "c1")),
                    finish(FinishReason.Unified.ToolCalls),
                ),
                answer("understood"),
            ),
        )

        // No executor and no handler: nothing can run, and absent a handler nothing is approved.
        val result = generateText(model, prompt, stopWhen = stepCountIs(5))

        val toolTurn = assertIs<ModelMessage.Tool>(result.messages[1])
        // The decision leads, because a vendor that raised the request looks for the response before
        // the result it gates.
        val decision = assertIs<ToolPart.ApprovalResponse>(toolTurn.content[0])
        assertEquals("ap1", decision.approvalId)
        assertEquals(false, decision.approved)
        // The sharp one: gating the result block on an executor produced NO result for a denied call, so
        // the model was never told it had been refused and reissued the identical call next round.
        val denial = assertIs<ToolPart.Result>(toolTurn.content[1])
        assertEquals("c1", denial.toolCallId)
        assertIs<ToolOutput.ExecutionDenied>(denial.output)
        assertEquals(2, result.steps.size, "the model must get a round in which to react to the refusal")
    }

    @Test
    fun `an approval request naming no call still records the decision`() = runTest {
        val model = ScriptedModel(
            listOf(
                listOf(
                    StreamPart.ToolApprovalRequestPart(Content.ToolApprovalRequest("ap1", "nonexistent")),
                    finish(FinishReason.Unified.Stop),
                ),
            ),
        )

        val result = generateText(model, prompt, approveTool = { _, _ -> true }, stopWhen = stepCountIs(3))

        assertEquals(1, result.steps.size)
        val decision = result.steps[0].approvalResponses.single()
        assertEquals(true, decision.approved)
        // Nothing to execute and nothing to deny, so the round produced no results and the loop ended
        // rather than looping on a request that will never resolve.
        assertTrue(result.steps[0].toolResults.isEmpty())
    }

    // ---- What the run reports ----------------------------------------------------------------------

    @Test
    fun `usage sub-fields sum across rounds, not only the two totals`() = runTest {
        fun round(more: Boolean, cacheRead: Int, cacheWrite: Int, reasoning: Int) = buildList {
            if (more) add(toolCall("c1", "t"))
            add(
                StreamPart.Finish(
                    Usage(
                        inputTokens = Usage.InputTokens(
                            total = 100,
                            noCache = 100 - cacheRead - cacheWrite,
                            cacheRead = cacheRead,
                            cacheWrite = cacheWrite,
                        ),
                        outputTokens = Usage.OutputTokens(total = 50, text = 50 - reasoning, reasoning = reasoning),
                    ),
                    FinishReason(if (more) FinishReason.Unified.ToolCalls else FinishReason.Unified.Stop),
                ),
            )
        }

        val model = ScriptedModel(listOf(round(true, 10, 5, 20), round(false, 40, 0, 0)))
        val result = generateText(
            model,
            prompt,
            toolExecutor = { _, _ -> ToolOutput.Text("ok") },
            stopWhen = stepCountIs(5),
        )

        // Cache tokens are what AIDE's cost display is priced off; a run that reports only the totals
        // cannot tell an expensive turn from a cached one.
        assertEquals(50, result.usage.inputTokens.cacheRead)
        assertEquals(5, result.usage.inputTokens.cacheWrite)
        assertEquals(145, result.usage.inputTokens.noCache)
        assertEquals(20, result.usage.outputTokens.reasoning)
        assertEquals(80, result.usage.outputTokens.text)
    }

    @Test
    fun `the run reports the LAST round's finish reason, not the first`() = runTest {
        val model = ScriptedModel(
            listOf(listOf(toolCall("c1", "t"), finish(FinishReason.Unified.ToolCalls)), answer("done")),
        )

        val result = generateText(
            model,
            prompt,
            toolExecutor = { _, _ -> ToolOutput.Text("ok") },
            stopWhen = stepCountIs(5),
        )

        assertEquals(FinishReason.Unified.Stop, result.finishReason.unified)
    }

    @Test
    fun `text is the last round and allText is every round`() = runTest {
        val model = ScriptedModel(
            listOf(
                listOf(
                    StreamPart.TextStart("t"),
                    StreamPart.TextDelta("t", "Looking. "),
                    StreamPart.TextEnd("t"),
                    toolCall("c1", "t"),
                    finish(FinishReason.Unified.ToolCalls),
                ),
                answer("Found it."),
            ),
        )

        val result = generateText(
            model,
            prompt,
            toolExecutor = { _, _ -> ToolOutput.Text("ok") },
            stopWhen = stepCountIs(5),
        )

        // `text` answers "what should I show the user"; `allText` answers "what did the model say".
        // generateObjectJson reads allText, so conflating them loses a JSON answer split across rounds.
        assertEquals("Found it.", result.text)
        assertEquals("Looking. Found it.", result.allText)
    }

    @Test
    fun `every round's warnings reach the caller as one list`() = runTest {
        val model = ScriptedModel(
            listOf(
                listOf(
                    StreamPart.StreamStart(
                        listOf(com.sabreware.aide.aisdk.Warning.Unsupported("topK", "not here")),
                    ),
                    toolCall("c1", "t"),
                    finish(FinishReason.Unified.ToolCalls),
                ),
                listOf(
                    StreamPart.StreamStart(
                        listOf(com.sabreware.aide.aisdk.Warning.Other("second round said something")),
                    ),
                ) + answer("done"),
            ),
        )

        val result = generateText(
            model,
            prompt,
            toolExecutor = { _, _ -> ToolOutput.Text("ok") },
            stopWhen = stepCountIs(5),
        )

        // A warning is about the CALL — "this model ignores topK" — and a caller that has to walk the
        // steps to find that out is a caller that does not.
        assertEquals(2, result.warnings.size)
    }

    // ---- What survives into round two --------------------------------------------------------------

    @Test
    fun `the caller's tools, choice, temperature and headers all survive into round two`() = runTest {
        // Round two calls a tool as well: the choice is `Required`, and a required choice that round two
        // answered in prose would now be the violation ToolChoiceEnforcementTest pins.
        val model = ScriptedModel(
            listOf(
                listOf(toolCall("c1", "alpha"), finish(FinishReason.Unified.ToolCalls)),
                listOf(toolCall("c2", "alpha"), finish(FinishReason.Unified.ToolCalls)),
            ),
        )
        val tools = listOf(Tool.Function("alpha", buildJsonObject { put("type", "object") }))
        val options = CallOptions(
            prompt = emptyList(),
            tools = tools,
            toolChoice = ToolChoice.Required,
            temperature = 0.25,
            headers = mapOf("x-trace" to "abc"),
        )

        generateText(
            model,
            prompt,
            options = options,
            toolExecutor = { _, _ -> ToolOutput.Text("ok") },
            stopWhen = stepCountIs(2),
        )

        val second = model.seenOptions[1]
        // Only the prompt changes between rounds. Anything else that resets turns round two into a call
        // the caller never configured — no tools to answer with, or a proxy token the gateway rejects.
        assertEquals(tools, second.tools)
        assertEquals(ToolChoice.Required, second.toolChoice)
        assertEquals(0.25, second.temperature)
        assertEquals(mapOf("x-trace" to "abc"), second.headers)
        assertTrue(second.prompt.size > model.seenOptions[0].prompt.size)
    }

    @Test
    fun `middleware sees every round, with the prompt as it has grown`() = runTest {
        val model = ScriptedModel(
            listOf(listOf(toolCall("c1", "alpha"), finish(FinishReason.Unified.ToolCalls)), answer("done")),
        )
        val seen = mutableListOf<Int>()
        val stamping = object : LanguageModelMiddleware {
            override suspend fun transformParams(
                type: LanguageModelMiddleware.CallType,
                params: CallOptions,
                model: LanguageModel,
            ): CallOptions {
                seen += params.prompt.size
                return params.copy(temperature = 0.1)
            }
        }

        generateText(
            model.withMiddleware(stamping),
            prompt,
            toolExecutor = { _, _ -> ToolOutput.Text("ok") },
            stopWhen = stepCountIs(5),
        )

        // The loop rebuilds the prompt per round, so a middleware that rewrites it must be re-applied
        // per round — a transform run once and cached would send round two the round-one prompt.
        assertEquals(listOf(1, 3), seen)
        assertTrue(model.seenOptions.all { it.temperature == 0.1 })
    }

    // ---- Errors ------------------------------------------------------------------------------------

    @Test
    fun `a mid-stream error costs its round, not the rounds before it`() = runTest {
        val model = ScriptedModel(
            listOf(
                listOf(toolCall("c1", "alpha"), finish(FinishReason.Unified.ToolCalls)),
                listOf(
                    StreamPart.TextStart("t"),
                    StreamPart.TextDelta("t", "partial"),
                    StreamPart.Error(IllegalStateException("the provider gave up")),
                ),
            ),
        )
        val events = mutableListOf<RunEvent>()

        streamText(
            model,
            prompt,
            toolExecutor = { _, _ -> ToolOutput.Text("ok") },
            stopWhen = stepCountIs(5),
        ).toList(events)

        val error = events.filterIsInstance<RunEvent.Error>().single()
        assertEquals(1, error.stepIndex)
        assertEquals("the provider gave up", error.error.message)
        // Throwing instead would unwind through the collector and take the completed first round — and
        // its tool result, which may have had effects — with it.
        val finished = events.filterIsInstance<RunEvent.Finish>().single().result
        assertEquals(2, finished.steps.size)
        assertEquals("partial", finished.text)
    }

    // ---- Stop conditions ---------------------------------------------------------------------------

    @Test
    fun `hasToolCall takes several names and fires on the round that calls one`() = runTest {
        val model = ScriptedModel(
            listOf(
                listOf(toolCall("c1", "lookup"), finish(FinishReason.Unified.ToolCalls)),
                listOf(toolCall("c2", "finalize"), finish(FinishReason.Unified.ToolCalls)),
                answer("done"),
            ),
        )

        val result = generateText(
            model,
            prompt,
            toolExecutor = { _, _ -> ToolOutput.Text("ok") },
            // Varargs rather than one name: "stop once the model calls submit OR finalize" is the shape
            // a real agent needs, and building it out of anyOf(hasToolCall(..), hasToolCall(..)) is a
            // sentence nobody writes correctly the first time.
            stopWhen = hasToolCall("submit", "finalize"),
        )

        assertEquals(2, result.steps.size)
    }

    @Test
    fun `hasToolCall does not fire on a round that called something else`() = runTest {
        val model = ScriptedModel(
            listOf(
                listOf(toolCall("c1", "lookup"), finish(FinishReason.Unified.ToolCalls)),
                listOf(toolCall("c2", "lookup"), finish(FinishReason.Unified.ToolCalls)),
                answer("done"),
            ),
        )

        val result = generateText(
            model,
            prompt,
            toolExecutor = { _, _ -> ToolOutput.Text("ok") },
            stopWhen = hasToolCall("submit"),
        )

        assertEquals(3, result.steps.size)
    }

    @Test
    fun `a suspending stop condition is awaited rather than sidestepped`() = runTest {
        val model = ScriptedModel(
            listOf(listOf(toolCall("c1", "t"), finish(FinishReason.Unified.ToolCalls)), answer("done")),
        )
        val consulted = CompletableDeferred<Unit>()

        val result = generateText(
            model,
            prompt,
            toolExecutor = { _, _ -> ToolOutput.Text("ok") },
            stopWhen = { steps ->
                yield()
                consulted.complete(Unit)
                steps.size >= 1
            },
        )

        assertTrue(consulted.isCompleted, "the stop condition was never asked")
        assertEquals(1, result.steps.size)
    }

    @Test
    fun `isLoopFinished runs until the model stops asking for tools`() = runTest {
        val model = ScriptedModel(
            listOf(
                listOf(toolCall("c1", "t"), finish(FinishReason.Unified.ToolCalls)),
                listOf(toolCall("c2", "t"), finish(FinishReason.Unified.ToolCalls)),
                answer("done"),
            ),
        )

        val result = generateText(
            model,
            prompt,
            toolExecutor = { _, _ -> ToolOutput.Text("ok") },
            stopWhen = isLoopFinished(),
        )

        assertEquals(3, result.steps.size)
    }

    // ---- Small surfaces nothing else reaches -------------------------------------------------------

    @Test
    fun `reasoning deltas and total usage can be read off the event stream`() = runTest {
        val model = ScriptedModel(
            listOf(
                listOf(
                    StreamPart.ReasoningStart("r"),
                    StreamPart.ReasoningDelta("r", "Think"),
                    StreamPart.ReasoningDelta("r", "ing."),
                    StreamPart.ReasoningEnd("r"),
                    StreamPart.Finish(
                        Usage(outputTokens = Usage.OutputTokens(total = 12)),
                        FinishReason(FinishReason.Unified.Stop),
                    ),
                ),
            ),
        )

        val events = streamText(model, prompt).toList()
        assertEquals("Thinking.", events.asFlow().reasoningDeltas().toList().joinToString(""))
        assertEquals(12, events.totalUsage().outputTokens.total)
        assertEquals("Thinking.", events.filterIsInstance<RunEvent.StepFinish>().single().step.reasoning)
    }

    @Test
    fun `a round with no results and no decisions appends no tool turn`() = runTest {
        val model = ScriptedModel(listOf(answer("nothing to do")))

        val result = generateText(model, prompt, stopWhen = stepCountIs(3))

        assertNull(result.steps[0].toToolMessage())
        assertEquals(1, result.messages.size, "an empty tool turn is a 400 on every vendor that sees it")
    }

    @Test
    fun `a run that outlives its total budget fails rather than returning a partial answer`() = runTest {
        val model = ScriptedModel(
            listOf(listOf(toolCall("c1", "slow"), finish(FinishReason.Unified.ToolCalls)), answer("done")),
        )

        // The budget covers the whole loop, so it surfaces as a failure rather than a short result: a
        // run that returned the rounds it managed would be indistinguishable from one the model ended,
        // and a caller would persist a truncated conversation as if it were complete.
        assertFailsWith<TimeoutCancellationException> {
            streamText(
                model,
                prompt,
                toolExecutor = { _, _ -> CompletableDeferred<ToolOutput>().await() },
                stopWhen = stepCountIs(5),
                timeouts = RunTimeouts(totalMs = 100),
            ).toList()
        }
    }

    @Test
    fun `a provider that goes quiet mid-stream fails on the gap, not on the total`() = runTest {
        val slowStream = flow {
            emit(StreamPart.TextStart("t"))
            emit(StreamPart.TextDelta("t", "still here"))
            delay(500)
            emit(StreamPart.TextDelta("t", "eventually"))
            emit(finish(FinishReason.Unified.Stop))
        }
        val model = object : LanguageModel {
            override val provider: String = "scripted"
            override val modelId: String = "scripted-1"
            override suspend fun doStream(options: CallOptions) = StreamResult(slowStream)
            override suspend fun doGenerate(options: CallOptions) = assembleGenerateResult(slowStream)
        }

        // The gap is measured rather than the total, so a model that legitimately thinks for a long time
        // is never cut off for being slow — only one that accepted the request and then stopped sending.
        assertFailsWith<TimeoutCancellationException> {
            streamText(model, prompt, timeouts = RunTimeouts(chunkMs = 100)).toList()
        }
    }

    @Test
    fun `a long first pause is allowed where a long gap is not`() = runTest {
        val thinkingFirst = flow {
            delay(400)
            emit(StreamPart.TextStart("t"))
            emit(StreamPart.TextDelta("t", "took a while"))
            emit(finish(FinishReason.Unified.Stop))
        }
        val model = object : LanguageModel {
            override val provider: String = "scripted"
            override val modelId: String = "scripted-1"
            override suspend fun doStream(options: CallOptions) = StreamResult(thinkingFirst)
            override suspend fun doGenerate(options: CallOptions) = assembleGenerateResult(thinkingFirst)
        }

        // Two limits rather than one, because the first gap is a different event: forty seconds before
        // the first token is a reasoning model working, and forty seconds mid-reply is a dead socket.
        val result = generateText(
            model,
            prompt,
            timeouts = RunTimeouts(firstChunkMs = 1_000, chunkMs = 100),
        )
        assertEquals("took a while", result.text)
    }
}
