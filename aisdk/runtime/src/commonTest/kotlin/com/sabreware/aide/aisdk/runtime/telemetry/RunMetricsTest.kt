package com.sabreware.aide.aisdk.runtime.telemetry

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.runtime.RunEvent
import com.sabreware.aide.aisdk.runtime.ToolExecutor
import com.sabreware.aide.aisdk.runtime.stepCountIs
import com.sabreware.aide.aisdk.runtime.streamText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * That the loop is observable — which, before the pre-call event existed, it was not.
 *
 * Wall-clock durations are not asserted on: a unit test cannot say how long anything took without
 * becoming flaky. What is asserted is that each number was measured at all and attributed to the right
 * round or the right tool, which is the part a wrong implementation gets wrong.
 */
class RunMetricsTest {

    private val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi"))))

    @Test
    fun `every round is timed, and the first token is timed separately from the round`() = runTest {
        val recorder = RunMetricsRecorder()

        streamText(model = TwoRoundModel(), prompt = prompt, toolExecutor = EchoExecutor, stopWhen = stepCountIs(2))
            .recordingTo(recorder)
            .toList()

        val metrics = recorder.metrics.value
        assertEquals(listOf(0, 1), metrics.steps.map { it.stepIndex })
        assertTrue(metrics.steps.all { it.latencyMs != null }, "a round finished without a latency")
        // The first round streamed only a tool call, so it has no token to be first — a total latency
        // would have reported a number here and called it responsiveness.
        assertEquals(null, metrics.steps[0].timeToFirstTokenMs)
        assertNotNull(metrics.steps[1].timeToFirstTokenMs)
        assertNotNull(metrics.totalMs)
        assertEquals(Usage(inputTokens = Usage.InputTokens(total = 1)), metrics.steps[0].usage)
    }

    @Test
    fun `a tool is timed around its execution and attributed to its round`() = runTest {
        val recorder = RunMetricsRecorder()

        streamText(model = TwoRoundModel(), prompt = prompt, toolExecutor = EchoExecutor, stopWhen = stepCountIs(2))
            .recordingTo(recorder)
            .toList()

        val tool = recorder.metrics.value.tools.single()
        assertEquals("echo", tool.toolName)
        assertEquals(0, tool.stepIndex)
        assertNotNull(tool.durationMs)
        assertTrue(!tool.failed)
    }

    @Test
    fun `a tool that threw is recorded as failed, not as a call that returned`() = runTest {
        val recorder = RunMetricsRecorder()

        streamText(
            model = TwoRoundModel(),
            prompt = prompt,
            toolExecutor = ToolExecutor { _, _ -> error("no") },
            stopWhen = stepCountIs(2),
        ).recordingTo(recorder).toList()

        assertTrue(recorder.metrics.value.tools.single().failed)
    }

    @Test
    fun `the pre-call event carries the options the round actually sent`() = runTest {
        val events = streamText(model = TwoRoundModel(), prompt = prompt, options = CallOptions(prompt = prompt, temperature = 0.3))
            .toList()

        val start = events.filterIsInstance<RunEvent.StepStart>().single()
        assertEquals(0, start.stepIndex)
        assertEquals(0.3, start.options.temperature)
    }

    @Test
    fun `every pending tool is announced before any of them runs`() = runTest {
        val events = streamText(
            model = TwoRoundModel(),
            prompt = prompt,
            toolExecutor = EchoExecutor,
            stopWhen = stepCountIs(2),
        ).toList()

        val announced = events.indexOfFirst { it is RunEvent.ToolStart }
        val resulted = events.indexOfFirst { it is RunEvent.ToolResult }
        assertTrue(announced in 0..<resulted, "a tool result arrived before the call was announced")
    }
}

private val EchoExecutor = ToolExecutor { _, _ -> ToolOutput.Text("echoed") }

/** A tool-calling round followed by a text round, which is the shape every metric here is about. */
private class TwoRoundModel : LanguageModel {

    override val provider: String = "test"
    override val modelId: String = "loop-1"

    private var round = 0

    override suspend fun doGenerate(options: CallOptions): GenerateResult = GenerateResult(
        content = listOf(Content.Text("done")),
        finishReason = FinishReason(FinishReason.Unified.Stop),
        usage = Usage(),
    )

    override suspend fun doStream(options: CallOptions): StreamResult {
        val first = round++ == 0
        return StreamResult(
            stream = if (first) {
                flowOf(
                    StreamPart.StreamStart(),
                    StreamPart.ToolCallPart(
                        Content.ToolCall(toolCallId = "call-1", toolName = "echo", input = "{}"),
                    ),
                    StreamPart.Finish(
                        usage = Usage(inputTokens = Usage.InputTokens(total = 1)),
                        finishReason = FinishReason(FinishReason.Unified.ToolCalls),
                    ),
                )
            } else {
                flowOf(
                    StreamPart.StreamStart(),
                    StreamPart.TextStart("0"),
                    StreamPart.TextDelta("0", "done"),
                    StreamPart.TextEnd("0"),
                    StreamPart.Finish(
                        usage = Usage(inputTokens = Usage.InputTokens(total = 3)),
                        finishReason = FinishReason(FinishReason.Unified.Stop),
                    ),
                )
            },
        )
    }
}
