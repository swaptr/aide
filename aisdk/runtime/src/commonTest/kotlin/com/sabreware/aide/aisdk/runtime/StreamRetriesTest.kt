package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.ResponseInfo
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * The reference's `streamRetries` cases (`stream-text.test.ts`), translated onto [StreamRetries].
 *
 * What these pin: a provider error received AFTER the stream began re-runs the current round in
 * isolation — completed rounds stand, the failed attempt's tool calls are neither shown nor executed,
 * and the recorded step is the successful attempt's alone. Without the policy nothing changes: the
 * error costs the round, as `StreamErrorNormalizationTest` has always pinned.
 */
class StreamRetriesTest {

    /** Answers each `doStream` call from the next script; records the prompt each was given. */
    private class AttemptModel(private val answers: List<(Int) -> StreamResult>) : LanguageModel {
        override val provider: String = "scripted"
        override val modelId: String = "scripted-1"
        val seenPrompts = mutableListOf<Prompt>()
        val calls: Int get() = seenPrompts.size

        override suspend fun doStream(options: CallOptions): StreamResult {
            val index = seenPrompts.size
            seenPrompts += options.prompt
            return answers[index.coerceAtMost(answers.lastIndex)](index)
        }

        override suspend fun doGenerate(options: CallOptions): GenerateResult =
            assembleGenerateResult(doStream(options).stream)
    }

    private val prompt: Prompt = listOf(ModelMessage.User(listOf(UserPart.Text("go"))))

    private fun overloaded(message: String = "Overloaded") = APICallError(
        message = message,
        url = "https://api.example.com/v1/messages",
        statusCode = 529,
        isRetryable = true,
    )

    private fun parts(vararg parts: StreamPart): (Int) -> StreamResult = { StreamResult(parts.asList().asFlow()) }

    private fun failing(text: String = "partial", error: Throwable = overloaded()) =
        parts(StreamPart.TextStart("t"), StreamPart.TextDelta("t", text), StreamPart.Error(error))

    private fun answering(text: String, usage: Usage = Usage()) = parts(
        StreamPart.TextStart("t"),
        StreamPart.TextDelta("t", text),
        StreamPart.TextEnd("t"),
        StreamPart.Finish(usage, FinishReason(FinishReason.Unified.Stop)),
    )

    private fun calling(id: String, name: String = "calc") = parts(
        StreamPart.ToolCallPart(Content.ToolCall(id, name, "{}")),
        StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.ToolCalls)),
    )

    private fun List<RunEvent>.parts() = mapNotNull { (it as? RunEvent.Part)?.part }
    private fun List<RunEvent>.errors() = filterIsInstance<RunEvent.Error>().map { it.error }
    private fun List<RunEvent>.result() = filterIsInstance<RunEvent.Finish>().single().result

    @Test
    fun `an error after streaming began re-runs the round, and the step is the successful attempt's`() = runTest {
        val usage = Usage(Usage.InputTokens(total = 5), Usage.OutputTokens(total = 2))
        val model = AttemptModel(listOf(failing(), answering("ok", usage)))

        val events = streamText(model, prompt, streamRetries = StreamRetries(maxRetries = 1)).toList()

        assertEquals(2, model.calls)
        assertEquals(emptyList(), events.errors())
        val step = events.result().steps.single()
        assertEquals("ok", step.text)
        assertEquals(usage, step.usage)
        assertEquals(FinishReason.Unified.Stop, step.finishReason.unified)
        // What the collector saw: the failed attempt's parts stay, its open block is closed, and then the
        // retry streams cleanly. Only the text-start and finish parts were ever the round's.
        assertEquals(
            listOf(
                StreamPart.TextStart("t"),
                StreamPart.TextDelta("t", "partial"),
                StreamPart.TextEnd("t"),
                StreamPart.TextStart("t"),
                StreamPart.TextDelta("t", "ok"),
                StreamPart.TextEnd("t"),
                StreamPart.Finish(usage, FinishReason(FinishReason.Unified.Stop)),
            ),
            events.parts(),
        )
        assertEquals(1, events.filterIsInstance<RunEvent.StepStart>().size)
    }

    @Test
    fun `an open reasoning block of a failed attempt is closed before the retry's parts`() = runTest {
        val model = AttemptModel(
            listOf(
                parts(StreamPart.ReasoningStart("r"), StreamPart.ReasoningDelta("r", "thinking"), StreamPart.Error(overloaded())),
                answering("ok"),
            ),
        )

        val parts = streamText(model, prompt, streamRetries = StreamRetries(maxRetries = 1)).toList().parts()

        assertEquals(StreamPart.ReasoningEnd("r"), parts[2])
        assertEquals(StreamPart.TextStart("t"), parts[3])
    }

    @Test
    fun `request and response identity come from the recovered attempt`() = runTest {
        val model = AttemptModel(
            listOf(
                { failing()(0).copy(request = RequestInfo("attempt-1"), response = ResponseInfo(headers = mapOf("x-attempt" to "1"))) },
                { answering("ok")(1).copy(request = RequestInfo("attempt-2"), response = ResponseInfo(headers = mapOf("x-attempt" to "2"))) },
            ),
        )

        val step = streamText(
            model,
            prompt,
            streamRetries = StreamRetries(maxRetries = 1),
            include = RunInclude(requestBody = true),
        ).toList().result().steps.single()

        assertEquals("attempt-2", step.request?.body)
        assertEquals("2", step.response?.headers?.get("x-attempt"))
    }

    @Test
    fun `the final error is reported once the automatic retries are spent`() = runTest {
        val first = overloaded("first")
        val second = overloaded("second")
        val model = AttemptModel(listOf(failing("one", first), failing("two", second)))

        val events = streamText(model, prompt, streamRetries = StreamRetries(maxRetries = 1)).toList()

        assertEquals(2, model.calls)
        // Reported once, and it is the LAST attempt's error — the same instance, untouched.
        assertSame(second, events.errors().single())
        assertEquals("two", events.result().text)
    }

    @Test
    fun `onError sees every error, and may ask for one more retry once the automatic ones are spent`() = runTest {
        val first = overloaded("first")
        val second = overloaded("second")
        val model = AttemptModel(listOf(failing(error = first), failing(error = second), answering("ok")))
        val seen = mutableListOf<Throwable>()

        val events = streamText(
            model,
            prompt,
            streamRetries = StreamRetries(maxRetries = 1, onError = { seen += it; true }),
        ).toList()

        assertEquals(3, model.calls)
        assertEquals(listOf<Throwable>(first, second), seen)
        assertEquals(emptyList(), events.errors())
        assertEquals("ok", events.result().text)
    }

    @Test
    fun `a directed retry is granted once per round, with no automatic retries configured`() = runTest {
        val errors = List(3) { overloaded("attempt-$it") }
        val model = AttemptModel(errors.map { failing(error = it) })

        val events = streamText(model, prompt, streamRetries = StreamRetries(onError = { true })).toList()

        assertEquals(2, model.calls)
        assertSame(errors[1], events.errors().single())
    }

    @Test
    fun `an observer that declines only observes`() = runTest {
        val error = overloaded()
        val model = AttemptModel(listOf(failing(error = error)))
        val seen = mutableListOf<Throwable>()

        val events = streamText(model, prompt, streamRetries = StreamRetries(onError = { seen += it; false })).toList()

        assertEquals(1, model.calls)
        assertEquals(listOf<Throwable>(error), seen)
        assertSame(error, events.errors().single())
    }

    @Test
    fun `without a policy nothing is retried, and tool parts stream as they arrive`() = runTest {
        val call = Content.ToolCall("c1", "calc", "{}")
        val model = AttemptModel(listOf(parts(StreamPart.ToolCallPart(call), StreamPart.Error(overloaded()))))

        val events = streamText(model, prompt, toolExecutor = { _, _ -> ToolOutput.Text("ok") }).toList()

        assertEquals(1, model.calls)
        assertEquals(1, events.errors().size)
        assertTrue(events.parts().any { it == StreamPart.ToolCallPart(call) }, "the tool part was held back")
    }

    @Test
    fun `tool calls from a failed attempt are neither shown nor executed`() = runTest {
        val abandoned = Content.ToolCall("c1", "calc", "{}")
        val model = AttemptModel(
            listOf(
                parts(StreamPart.ToolCallPart(abandoned), StreamPart.Error(overloaded())),
                calling("c2"),
                answering("done"),
            ),
        )
        val executed = mutableListOf<String>()

        val events = streamText(
            model,
            prompt,
            toolExecutor = { call, _ -> executed += call.toolCallId; ToolOutput.Text("4") },
            stopWhen = stepCountIs(2),
            streamRetries = StreamRetries(maxRetries = 1),
        ).toList()

        assertEquals(listOf("c2"), executed)
        assertEquals(listOf("c2"), events.filterIsInstance<RunEvent.ToolStart>().map { it.call.toolCallId })
        assertTrue(events.parts().none { it == StreamPart.ToolCallPart(abandoned) }, "the abandoned call was shown")
        val steps = events.result().steps
        assertEquals(listOf("c2"), steps.first().toolCalls.map { it.toolCallId })
        // The next round was built from the recovered attempt alone.
        val toolTurn = model.seenPrompts[2].filterIsInstance<ModelMessage.Tool>().single()
        assertEquals(listOf("c2"), toolTurn.content.filterIsInstance<ToolPart.Result>().map { it.toolCallId })
    }

    @Test
    fun `completed rounds stand when a later round is retried`() = runTest {
        val model = AttemptModel(listOf(calling("c1"), failing(), answering("done")))

        val events = streamText(
            model,
            prompt,
            toolExecutor = { _, _ -> ToolOutput.Text("4") },
            stopWhen = stepCountIs(2),
            streamRetries = StreamRetries(maxRetries = 1),
        ).toList()

        assertEquals(3, model.calls)
        val steps = events.result().steps
        assertEquals(2, steps.size)
        assertEquals(listOf("c1"), steps[0].toolCalls.map { it.toolCallId })
        assertEquals("done", steps[1].text)
        // The retry was given the same conversation as the attempt it replaced: round one, its result and all.
        assertEquals(model.seenPrompts[1], model.seenPrompts[2])
        assertEquals(1, model.seenPrompts[2].filterIsInstance<ModelMessage.Tool>().size)
    }

    @Test
    fun `a retry whose request fails ends the round with that error, not the run`() = runTest {
        val requestError = overloaded("request failed")
        val model = AttemptModel(listOf(failing("partial"), { throw requestError }))

        val events = streamText(model, prompt, streamRetries = StreamRetries(maxRetries = 1)).toList()

        assertEquals(2, model.calls)
        assertSame(requestError, events.errors().single())
        val step = events.result().steps.single()
        assertEquals(FinishReason.Unified.Error, step.finishReason.unified)
        assertEquals("partial", step.text)
    }

    @Test
    fun `a negative retry count is refused`() {
        val error = assertFailsWith<InvalidArgumentError> { StreamRetries(maxRetries = -1) }

        assertEquals("streamRetries must be >= 0", error.message)
        assertEquals("streamRetries", error.argument)
    }
}
