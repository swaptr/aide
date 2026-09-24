package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.ResponseInfo
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * The two hooks the event flow cannot carry, and the run identity that threads through everything.
 *
 * The property that matters for [RunCallbacks.onAbort] is the one a wrong implementation gets quietly
 * wrong: the hook must see the rounds that completed, and the cancellation must STILL land afterwards.
 * A hook that swallowed it would turn a user's cancel into a run that keeps billing.
 */
class RunCallbacksTest {

    private class ScriptedModel(
        private val rounds: List<List<StreamPart>>,
        private val request: RequestInfo? = null,
        private val response: ResponseInfo? = null,
    ) : LanguageModel {
        override val provider: String = "scripted"
        override val modelId: String = "scripted-1"
        val seenPrompts = mutableListOf<Prompt>()

        override suspend fun doStream(options: CallOptions): StreamResult {
            val index = seenPrompts.size
            seenPrompts += options.prompt
            return StreamResult(rounds[index.coerceAtMost(rounds.lastIndex)].asFlow(), request, response)
        }

        override suspend fun doGenerate(options: CallOptions): GenerateResult {
            val index = seenPrompts.size
            seenPrompts += options.prompt
            return assembleGenerateResult(rounds[index.coerceAtMost(rounds.lastIndex)].asFlow())
                .copy(request = request, response = response)
        }
    }

    private val prompt: Prompt = listOf(ModelMessage.User(listOf(UserPart.Text("go"))))

    private fun toolRound(id: String, name: String) = listOf(
        StreamPart.ToolCallPart(Content.ToolCall(id, name, "{}")),
        StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.ToolCalls)),
    )

    private fun answer(text: String) = listOf(
        StreamPart.TextStart("t"),
        StreamPart.TextDelta("t", text),
        StreamPart.TextEnd("t"),
        StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.Stop)),
    )

    private val echo = ToolExecutor { _, _ -> ToolOutput.Text("ok") }

    // ---- onStart -----------------------------------------------------------------------------------

    @Test
    fun `onStart fires once, before the first model call, with what the first round will send`() = runTest {
        val model = ScriptedModel(listOf(toolRound("c1", "t"), answer("done")))
        val starts = mutableListOf<RunStart>()
        var promptsSeenAtStart = -1

        val result = generateText(
            model = model,
            prompt = prompt,
            instructions = "be terse",
            toolExecutor = echo,
            stopWhen = stepCountIs(5),
            callbacks = RunCallbacks(
                onStart = { start ->
                    starts += start
                    promptsSeenAtStart = model.seenPrompts.size
                },
            ),
        )

        val start = starts.single()
        assertEquals(0, promptsSeenAtStart, "onStart fired after a model call")
        // Standardized, not raw: the hook sees the instructions already folded in.
        assertEquals(ModelMessage.System("be terse"), start.prompt.first())
        assertEquals(model, start.model)
        assertEquals(result.callId, start.callId)
    }

    // ---- callId / stepNumber -----------------------------------------------------------------------

    @Test
    fun `every step and the result carry the run's one id, and each step its number`() = runTest {
        val model = ScriptedModel(listOf(toolRound("c1", "t"), answer("done")))

        val events = streamText(model, prompt, toolExecutor = echo, stopWhen = stepCountIs(5)).toList()

        val result = events.filterIsInstance<RunEvent.Finish>().single().result
        assertTrue(result.callId.startsWith("call_"), result.callId)
        assertEquals(listOf(result.callId, result.callId), result.steps.map { it.callId })
        assertEquals(listOf(0, 1), result.steps.map { it.stepNumber })
        events.filterIsInstance<RunEvent.StepFinish>().forEach { assertEquals(it.stepIndex, it.step.stepNumber) }
    }

    @Test
    fun `two runs get two ids`() = runTest {
        val model = ScriptedModel(listOf(answer("x")))

        val first = generateText(model, prompt).callId
        val second = generateText(model, prompt).callId

        assertTrue(first != second, "runs shared an id: $first")
    }

    // ---- onAbort -----------------------------------------------------------------------------------

    @Test
    fun `onAbort gets the completed rounds when the collector cancels, and the cancel still lands`() = runTest {
        val model = ScriptedModel(listOf(toolRound("c1", "fast"), toolRound("c2", "slow"), answer("never")))
        val slowStarted = CompletableDeferred<Unit>()
        val aborted = CompletableDeferred<RunAbort>()
        val events = mutableListOf<RunEvent>()

        val collector = launch {
            streamText(
                model = model,
                prompt = prompt,
                toolExecutor = { call, _ ->
                    if (call.toolName == "slow") {
                        slowStarted.complete(Unit)
                        CompletableDeferred<ToolOutput>().await()
                    }
                    ToolOutput.Text("ok")
                },
                stopWhen = stepCountIs(5),
                callbacks = RunCallbacks(onAbort = { aborted.complete(it) }),
            ).toList(events)
        }

        slowStarted.await()
        collector.cancel()
        collector.join()

        val abort = aborted.await()
        // The first round finished — its tool ran and may have had effects — and is what a caller
        // persisting partial progress wants. The second was in flight and is not a step yet.
        assertEquals(1, abort.steps.size)
        assertEquals("fast", abort.steps.single().toolCalls.single().toolName)
        assertEquals(abort.steps.single().callId, abort.callId)
        assertIs<CancellationException>(abort.cause)
        // The hook observed the cancel; it did not absorb it.
        assertTrue(collector.isCancelled, "the collector completed normally after a cancel")
        assertTrue(events.none { it is RunEvent.Finish }, "a cancelled run reported a finish")
    }

    @Test
    fun `onAbort fires when the total budget expires, and the timeout still propagates`() = runTest {
        val model = ScriptedModel(listOf(toolRound("c1", "slow"), answer("never")))
        val aborts = mutableListOf<RunAbort>()

        assertFailsWith<TimeoutCancellationException> {
            streamText(
                model = model,
                prompt = prompt,
                toolExecutor = { _, _ -> CompletableDeferred<ToolOutput>().await() },
                stopWhen = stepCountIs(5),
                timeouts = RunTimeouts(totalMs = 100),
                callbacks = RunCallbacks(onAbort = { aborts += it }),
            ).toList()
        }

        val abort = aborts.single()
        assertEquals(0, abort.steps.size)
        assertIs<TimeoutCancellationException>(abort.cause)
    }

    @Test
    fun `onAbort fires for generateText as well`() = runTest {
        val model = ScriptedModel(listOf(toolRound("c1", "slow"), answer("never")))
        val started = CompletableDeferred<Unit>()
        val aborted = CompletableDeferred<RunAbort>()

        val job = launch {
            generateText(
                model = model,
                prompt = prompt,
                toolExecutor = { _, _ ->
                    started.complete(Unit)
                    CompletableDeferred<ToolOutput>().await()
                },
                stopWhen = stepCountIs(5),
                callbacks = RunCallbacks(onAbort = { aborted.complete(it) }),
            )
        }

        started.await()
        job.cancel()
        job.join()

        assertEquals(0, aborted.await().steps.size)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `onAbort carries the caller's own reason for cancelling`() = runTest {
        val model = ScriptedModel(listOf(toolRound("c1", "slow"), answer("never")))
        val slowStarted = CompletableDeferred<Unit>()
        val aborted = CompletableDeferred<RunAbort>()

        val collector = launch {
            streamText(
                model = model,
                prompt = prompt,
                toolExecutor = { _, _ ->
                    slowStarted.complete(Unit)
                    CompletableDeferred<ToolOutput>().await()
                },
                stopWhen = stepCountIs(5),
                callbacks = RunCallbacks(onAbort = { aborted.complete(it) }),
            ).toList()
        }
        slowStarted.await()
        collector.cancel(CancellationException("user stopped"))
        collector.join()

        // The reference's abort event exposes the signal's reason; here the reason IS the cause — the
        // exception the caller cancelled with, not a generic "job was cancelled" minted on the way.
        assertEquals("user stopped", aborted.await().cause.message)
    }

    @Test
    fun `a run that finishes never reports an abort`() = runTest {
        val model = ScriptedModel(listOf(answer("done")))
        var aborts = 0

        generateText(model, prompt, callbacks = RunCallbacks(onAbort = { aborts++ }))

        assertEquals(0, aborts)
    }

    // ---- include -----------------------------------------------------------------------------------

    @Test
    fun `request and response bodies are shed unless asked for`() = runTest {
        val model = ScriptedModel(
            listOf(answer("done")),
            request = RequestInfo(body = """{"model":"x"}"""),
            response = ResponseInfo(headers = mapOf("x-request-id" to "r1"), body = "{...}"),
        )

        val shed = generateText(model, prompt).steps.single()
        val kept = generateText(model, prompt, include = RunInclude(requestBody = true, responseBody = true)).steps.single()
        val streamed = streamText(model, prompt).toList().filterIsInstance<RunEvent.Finish>().single().result.steps.single()

        // The envelope survives — headers are the id a support ticket asks for — only the bodies go.
        assertNotNull(shed.request)
        assertNull(shed.request?.body)
        assertEquals("r1", shed.response?.headers?.get("x-request-id"))
        assertNull(shed.response?.body)
        assertNull(streamed.request?.body)
        assertNull(streamed.response?.body)

        assertEquals("""{"model":"x"}""", kept.request?.body)
        assertEquals("{...}", kept.response?.body)
    }

    @Test
    fun `rawChunks reaches the options every round sends`() = runTest {
        val model = ScriptedModel(listOf(answer("done")))

        val off = streamText(model, prompt).toList().filterIsInstance<RunEvent.StepStart>().single()
        val on = streamText(model, prompt, include = RunInclude(rawChunks = true)).toList()
            .filterIsInstance<RunEvent.StepStart>().single()

        assertEquals(false, off.options.includeRawChunks)
        assertEquals(true, on.options.includeRawChunks)
    }
}
