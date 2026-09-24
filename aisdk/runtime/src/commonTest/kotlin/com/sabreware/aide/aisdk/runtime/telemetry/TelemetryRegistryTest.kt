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
 * The registry, tested for the properties the reference's global array cannot have: two runs report to
 * different consumers, and a broken exporter costs its own data rather than the run.
 */
class TelemetryRegistryTest {

    private val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi"))))

    /** Records everything, so a test can assert on order as well as content. */
    private class Recording : TelemetryConsumer {
        val events = mutableListOf<RunEvent>()
        val started = mutableListOf<TelemetrySpan>()
        val ended = mutableListOf<Pair<TelemetrySpan, TelemetrySpanOutcome>>()

        override suspend fun onEvent(event: RunEvent) { events += event }
        override suspend fun onSpanStart(span: TelemetrySpan) { started += span }
        override suspend fun onSpanEnd(span: TelemetrySpan, outcome: TelemetrySpanOutcome) {
            ended += span to outcome
        }
    }

    @Test
    fun `every event reaches the consumer, and the flow is unchanged`() = runTest {
        val consumer = Recording()

        val passed = streamText(
            model = SpanFixtureModel(),
            prompt = prompt,
            toolExecutor = SpanEchoExecutor,
            stopWhen = stepCountIs(2),
        ).reportingTo(TelemetryRegistry(listOf(consumer))).toList()

        // An operator that changed the stream would be a second thing to keep correct.
        assertEquals(passed.size, consumer.events.size)
        assertTrue(passed.last() is RunEvent.Finish)
    }

    @Test
    fun `a run opens and closes one span per round, plus the run itself`() = runTest {
        val consumer = Recording()

        streamText(
            model = SpanFixtureModel(),
            prompt = prompt,
            toolExecutor = SpanEchoExecutor,
            stopWhen = stepCountIs(2),
        ).reportingTo(TelemetryRegistry(listOf(consumer))).toList()

        assertEquals(
            listOf(TelemetrySpanKind.Run, TelemetrySpanKind.Step, TelemetrySpanKind.ToolExecution, TelemetrySpanKind.Step),
            consumer.started.map { it.kind },
        )
        val steps = consumer.ended.filter { it.first.kind == TelemetrySpanKind.Step }
        assertEquals(listOf(0, 1), steps.map { it.first.stepIndex })
        // A step span carries what the round cost; the run span carries the total.
        assertNotNull(steps.first().second.usage)
        val run = consumer.ended.single { it.first.kind == TelemetrySpanKind.Run }
        assertNotNull(run.second.usage)
    }

    @Test
    fun `a tool span is named for its tool and closed with the failure that ended it`() = runTest {
        val consumer = Recording()

        streamText(
            model = SpanFixtureModel(),
            prompt = prompt,
            toolExecutor = ToolExecutor { _, _ -> error("disk on fire") },
            stopWhen = stepCountIs(2),
        ).reportingTo(TelemetryRegistry(listOf(consumer))).toList()

        val (span, outcome) = consumer.ended.single { it.first.kind == TelemetrySpanKind.ToolExecution }
        assertEquals("echo", span.name)
        assertEquals(0, span.stepIndex)
        // The model was handed a formatted error output and the run continued; the span still says
        // the tool threw, because a span that reported success would hide every recovered failure.
        assertEquals("disk on fire", outcome.error?.message)
    }

    @Test
    fun `a consumer that throws costs its own data and not the run`() = runTest {
        val broken = object : TelemetryConsumer {
            override suspend fun onEvent(event: RunEvent) = error("exporter down")
        }
        val healthy = Recording()
        val registry = TelemetryRegistry(listOf(broken, healthy))

        val events = streamText(model = SpanFixtureModel(), prompt = prompt).reportingTo(registry).toList()

        assertTrue(events.last() is RunEvent.Finish)
        // The healthy consumer still saw everything: one broken exporter does not silence the others.
        assertEquals(events.size, healthy.events.size)
        assertTrue(registry.failures.isNotEmpty())
    }

    @Test
    fun `two runs report to their own registries`() = runTest {
        val first = Recording()
        val second = Recording()

        streamText(model = SpanFixtureModel(), prompt = prompt).reportingTo(TelemetryRegistry(listOf(first))).toList()
        streamText(model = SpanFixtureModel(), prompt = prompt).reportingTo(TelemetryRegistry(listOf(second))).toList()

        // The property a process-wide registry cannot have, and the reason this one is a value.
        assertTrue(first.events.isNotEmpty())
        assertEquals(first.events.size, second.events.size)
        assertTrue(first.started.none { it in second.started.filter { s -> s.kind == TelemetrySpanKind.ToolExecution } })
    }

    @Test
    fun `an empty registry leaves the flow entirely alone`() = runTest {
        val events = streamText(model = SpanFixtureModel(), prompt = prompt)
            .reportingTo(TelemetryRegistry())
            .toList()

        assertTrue(events.last() is RunEvent.Finish)
    }

    @Test
    fun `metrics and telemetry compose over the same run`() = runTest {
        val recorder = RunMetricsRecorder()
        val consumer = Recording()

        streamText(model = SpanFixtureModel(), prompt = prompt, toolExecutor = SpanEchoExecutor, stopWhen = stepCountIs(2))
            .recordingTo(recorder)
            .reportingTo(TelemetryRegistry(listOf(consumer)))
            .toList()

        assertEquals(2, recorder.metrics.value.steps.size)
        assertEquals(2, consumer.ended.count { it.first.kind == TelemetrySpanKind.Step })
    }
}

private val SpanEchoExecutor = ToolExecutor { _, _ -> ToolOutput.Text("echoed") }

/** A tool-calling round followed by a text round — the shape every span here is about. */
private class SpanFixtureModel : LanguageModel {

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
                    StreamPart.ToolCallPart(Content.ToolCall("c1", "echo", "{}")),
                    StreamPart.Finish(
                        Usage(inputTokens = Usage.InputTokens(total = 1)),
                        FinishReason(FinishReason.Unified.ToolCalls),
                    ),
                )
            } else {
                flowOf(
                    StreamPart.TextStart("t0"),
                    StreamPart.TextDelta("t0", "done"),
                    StreamPart.TextEnd("t0"),
                    StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.Stop)),
                )
            },
        )
    }
}
