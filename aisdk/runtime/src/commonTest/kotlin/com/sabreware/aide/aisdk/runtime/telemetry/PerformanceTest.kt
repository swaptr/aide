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
import com.sabreware.aide.aisdk.runtime.generateText
import com.sabreware.aide.aisdk.runtime.stepCountIs
import com.sabreware.aide.aisdk.runtime.streamText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Throughput and chunk timing. The arithmetic is pinned exactly against the reference's test values;
 * the recorder is pinned on WHICH numbers exist for which kind of round, since a unit test cannot say
 * how long anything took without becoming flaky.
 */
class PerformanceTest {

    private val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi"))))

    // ---- arithmetic (calculate-tokens-per-second.test.ts) ------------------------------------------

    @Test
    fun `tokens per second is the plain rate`() {
        assertEquals(20.0, tokensPerSecond(tokens = 10, durationMs = 500))
    }

    @Test
    fun `an unknown count, an unknown duration or a zero duration is zero, not infinity`() {
        assertEquals(0.0, tokensPerSecond(tokens = null, durationMs = 500))
        assertEquals(0.0, tokensPerSecond(tokens = 10, durationMs = 0))
        assertEquals(0.0, tokensPerSecond(tokens = null, durationMs = 0))
        assertEquals(0.0, tokensPerSecond(tokens = 10, durationMs = null))
    }

    @Test
    fun `chunk timing uses nearest-rank percentiles over the sorted gaps`() {
        val timing = assertNotNull(outputChunkTiming(listOf(5, 1, 3, 2, 4)))

        assertEquals(1, timing.minMs)
        assertEquals(1, timing.p10Ms)
        assertEquals(3, timing.medianMs)
        assertEquals(3.0, timing.avgMs)
        assertEquals(5, timing.p90Ms)
        assertEquals(5, timing.maxMs)
    }

    @Test
    fun `no gaps is no timing, and one gap is every statistic at once`() {
        assertNull(outputChunkTiming(emptyList()))
        val one = assertNotNull(outputChunkTiming(listOf(7)))
        assertEquals(OutputChunkTiming(7, 7, 7, 7.0, 7, 7), one)
    }

    // ---- the recorder ------------------------------------------------------------------------------

    @Test
    fun `a streamed round has a first output, a response time, rates and gaps`() = runTest {
        val recorder = RunMetricsRecorder()

        streamText(model = ChattyModel(), prompt = prompt).recordingTo(recorder).toList()

        val step = recorder.metrics.value.steps.single()
        assertNotNull(step.timeToFirstOutputMs)
        assertNotNull(step.responseTimeMs)
        assertNotNull(step.latencyMs)
        assertTrue(step.responseTimeMs!! <= step.latencyMs!!, "the model outlived its own round")
        val performance = assertNotNull(step.performance)
        // Three deltas: two gaps, so there is a timing to report.
        assertNotNull(performance.timeBetweenOutputChunksMs)
        // Both split rates exist because the stream revealed the split.
        assertNotNull(performance.outputTokensPerSecond)
        assertNotNull(performance.inputTokensPerSecond)
        assertTrue(performance.effectiveOutputTokensPerSecond >= 0.0)
        assertTrue(performance.effectiveTotalTokensPerSecond >= 0.0)
    }

    @Test
    fun `a non-streaming round has the effective rates and nothing a stream would have revealed`() = runTest {
        val recorder = RunMetricsRecorder()

        generateText(model = ChattyModel(), prompt = prompt, onEvent = { recorder.record(it) })

        val step = recorder.metrics.value.steps.single()
        assertNull(step.timeToFirstOutputMs)
        assertNotNull(step.responseTimeMs)
        val performance = assertNotNull(step.performance)
        assertNull(performance.outputTokensPerSecond)
        assertNull(performance.inputTokensPerSecond)
        assertNull(performance.timeBetweenOutputChunksMs)
    }

    @Test
    fun `a tool-only round has a first output but no first token`() = runTest {
        val recorder = RunMetricsRecorder()

        streamText(
            model = ToolThenTextModel(),
            prompt = prompt,
            toolExecutor = { _, _ -> ToolOutput.Text("x") },
            stopWhen = stepCountIs(2),
        ).recordingTo(recorder).toList()

        val toolRound = recorder.metrics.value.steps[0]
        // A tool call IS output — its arguments were generated — even though no text was.
        assertNull(toolRound.timeToFirstTokenMs)
        assertNotNull(toolRound.timeToFirstOutputMs)
        // One output chunk has no gap to report.
        assertNull(assertNotNull(toolRound.performance).timeBetweenOutputChunksMs)
        assertNotNull(recorder.metrics.value.steps[1].timeToFirstTokenMs)
    }
}

/** One round, three text deltas, a usage worth dividing. */
private class ChattyModel : LanguageModel {

    override val provider: String = "test"
    override val modelId: String = "chatty-1"

    private val usage = Usage(Usage.InputTokens(total = 12), Usage.OutputTokens(total = 6))

    override suspend fun doGenerate(options: CallOptions): GenerateResult = GenerateResult(
        content = listOf(Content.Text("one two three")),
        finishReason = FinishReason(FinishReason.Unified.Stop),
        usage = usage,
    )

    override suspend fun doStream(options: CallOptions): StreamResult = StreamResult(
        flowOf(
            StreamPart.StreamStart(),
            StreamPart.TextStart("0"),
            StreamPart.TextDelta("0", "one "),
            StreamPart.TextDelta("0", "two "),
            StreamPart.TextDelta("0", "three"),
            StreamPart.TextEnd("0"),
            StreamPart.Finish(usage, FinishReason(FinishReason.Unified.Stop)),
        ),
    )
}

/** A tool-calling round, then a text round. */
private class ToolThenTextModel : LanguageModel {

    override val provider: String = "test"
    override val modelId: String = "loop-1"

    private var round = 0

    override suspend fun doGenerate(options: CallOptions): GenerateResult = GenerateResult(
        content = listOf(Content.Text("done")),
        finishReason = FinishReason(FinishReason.Unified.Stop),
        usage = Usage(),
    )

    override suspend fun doStream(options: CallOptions): StreamResult = StreamResult(
        if (round++ == 0) {
            flowOf(
                StreamPart.ToolCallPart(Content.ToolCall("call-1", "echo", "{}")),
                StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.ToolCalls)),
            )
        } else {
            flowOf(
                StreamPart.TextStart("0"),
                StreamPart.TextDelta("0", "done"),
                StreamPart.TextEnd("0"),
                StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.Stop)),
            )
        },
    )
}
