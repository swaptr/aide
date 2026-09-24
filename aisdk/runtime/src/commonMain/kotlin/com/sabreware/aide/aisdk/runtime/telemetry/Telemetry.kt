package com.sabreware.aide.aisdk.runtime.telemetry

import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.runtime.RunEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

// ---------------------------------------------------------------------------------------------------
// The telemetry registry, built on the operator model RunMetrics.kt already establishes.
//
// The reference registers integrations in a GLOBAL mutable array (`globalThis.AI_SDK_TELEMETRY_
// INTEGRATIONS`) and dispatches ~15 named callbacks to them. Two things about that do not survive the
// port. A process-wide mutable list makes two runs in one process unable to report to different
// consumers, and makes a test's telemetry leak into the next test. And a callback per event type is a
// second surface that has to stay in sync with the loop — the exact duplication the note at the top of
// RunMetrics.kt refuses.
//
// So the registry here is a VALUE holding contributed consumers, matching the repo's own registry
// convention (a registry collects whatever was bound), and it is fed by one operator that passes every
// event through untouched. Spans are DERIVED from the event pairs the loop already emits rather than
// published by a parallel channel, which is what makes it impossible for a span to be missing because
// somebody forgot to open one.
// ---------------------------------------------------------------------------------------------------

/** What a [TelemetrySpan] measures. */
public enum class TelemetrySpanKind {

    /** The whole run, from its first request to its last event. */
    Run,

    /** One round: a model call plus the tools it triggered. */
    Step,

    /** One tool execution, timed around the call itself. */
    ToolExecution,
}

/**
 * One unit of work with a beginning and an end.
 *
 * The reference's tracing channel publishes these from inside the loop; here they are read off the
 * event flow — [RunEvent.StepStart] to [RunEvent.StepFinish], [RunEvent.ToolStart] to
 * [RunEvent.ToolResult] — so a span cannot go unpublished because a code path forgot to emit one.
 */
public data class TelemetrySpan(
    val kind: TelemetrySpanKind,
    /** A stable name for grouping: `run`, `step`, or the tool's own name. */
    val name: String,
    /** The round this belongs to; null on the run span. */
    val stepIndex: Int? = null,
    /** The call this belongs to; null on anything but a tool span. */
    val toolCallId: String? = null,
)

/** How a span ended. */
public data class TelemetrySpanOutcome(
    /** Wall-clock duration, measured from the span's own start. */
    val durationMs: Long,
    /** What the work cost, where the span has a cost — a step does, a tool does not. */
    val usage: Usage? = null,
    /**
     * The failure, if there was one.
     *
     * A tool that threw ends its span with the exception even though the model was handed a formatted
     * error output: the model's view and the operator's view of the same event are different, and a
     * span that reported success because the run continued would hide every recovered failure.
     */
    val error: Throwable? = null,
)

/**
 * Something that wants to see a run happen.
 *
 * Both hooks default to doing nothing, so an exporter that only cares about spans implements one
 * method and an event logger implements the other. Suspending because the interesting implementations
 * do I/O — an OTLP export, a write to a queue — and a consumer forced to block would have to invent its
 * own dispatcher inside a coroutine that already has one.
 */
public interface TelemetryConsumer {

    /** Every event the run produced, in order, before it reaches the collector. */
    public suspend fun onEvent(event: RunEvent) {}

    /** A unit of work began. */
    public suspend fun onSpanStart(span: TelemetrySpan) {}

    /** A unit of work ended — see [TelemetrySpanOutcome] for what "ended" carries. */
    public suspend fun onSpanEnd(span: TelemetrySpan, outcome: TelemetrySpanOutcome) {}
}

/**
 * The consumers a run reports to.
 *
 * A value rather than a global: two runs in one process report to different registries, a test's
 * consumers cannot leak into the next test, and nothing has to be un-registered. Consumers are
 * CONTRIBUTED — a host builds the list from whatever it has wired, exactly as the repo's other
 * registries collect their bindings — so adding an exporter is a list entry rather than a call into a
 * mutable global.
 *
 * A consumer that throws is not allowed to take the run down with it. Telemetry is an observer; a
 * broken exporter should cost its own data and nothing else, and [failures] keeps what it threw so the
 * silence is inspectable rather than total.
 */
public class TelemetryRegistry(
    private val consumers: List<TelemetryConsumer> = emptyList(),
) {

    private val recordedFailures = mutableListOf<Throwable>()

    /** Whatever the consumers threw, in order. Empty on a healthy run. */
    public val failures: List<Throwable> get() = recordedFailures.toList()

    /** True when nothing is listening, which lets the operator skip its own bookkeeping entirely. */
    public val isEmpty: Boolean get() = consumers.isEmpty()

    internal suspend fun dispatch(action: suspend (TelemetryConsumer) -> Unit) {
        for (consumer in consumers) {
            try {
                action(consumer)
            } catch (e: CancellationException) {
                // Cancelling the run must actually cancel it; only a consumer's OWN failure is absorbed.
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                recordedFailures += e
            }
        }
    }
}

/**
 * Passes every event through untouched, reporting it and the spans it delimits to [registry].
 *
 * The same shape as [recordingTo], and composable with it: `streamText(…).recordingTo(m).reportingTo(r)`
 * measures and exports the same run without either operator knowing about the other.
 *
 * ```kotlin
 * streamText(model, prompt)
 *     .reportingTo(TelemetryRegistry(listOf(myExporter)))
 *     .collect { … }
 * ```
 */
public fun Flow<RunEvent>.reportingTo(registry: TelemetryRegistry): Flow<RunEvent> {
    if (registry.isEmpty) return this
    return flow {
        val spans = SpanTracker(registry)
        collect { event ->
            registry.dispatch { it.onEvent(event) }
            spans.observe(event)
            emit(event)
        }
    }
}

/**
 * Turns the run's events into span starts and ends.
 *
 * Every span is closed by the event that ends the work it measures, so a run that stops early — a
 * pending approval, a cancelled collector — simply leaves its open spans unclosed rather than reporting
 * a duration it did not measure. Inventing an end for work that never finished is worse than a missing
 * span: it lands in the exporter as a completed operation that never completed.
 */
private class SpanTracker(private val registry: TelemetryRegistry) {

    private var runStart: TimeSource.Monotonic.ValueTimeMark? = null
    private val stepStarts = mutableMapOf<Int, TimeSource.Monotonic.ValueTimeMark>()
    private val toolStarts = mutableMapOf<String, TimeSource.Monotonic.ValueTimeMark>()
    private val toolSpans = mutableMapOf<String, TelemetrySpan>()
    private val toolErrors = mutableMapOf<String, Throwable>()

    private val runSpan = TelemetrySpan(TelemetrySpanKind.Run, "run")

    suspend fun observe(event: RunEvent) {
        when (event) {
            is RunEvent.StepStart -> {
                if (runStart == null) {
                    runStart = TimeSource.Monotonic.markNow()
                    registry.dispatch { it.onSpanStart(runSpan) }
                }
                stepStarts[event.stepIndex] = TimeSource.Monotonic.markNow()
                registry.dispatch { it.onSpanStart(stepSpan(event.stepIndex)) }
            }

            is RunEvent.ToolStart -> {
                val span = TelemetrySpan(
                    kind = TelemetrySpanKind.ToolExecution,
                    name = event.call.toolName,
                    stepIndex = event.stepIndex,
                    toolCallId = event.call.toolCallId,
                )
                toolSpans[event.call.toolCallId] = span
                toolStarts[event.call.toolCallId] = TimeSource.Monotonic.markNow()
                registry.dispatch { it.onSpanStart(span) }
            }

            // Recorded before the result arrives, so a tool that threw and still produced an output
            // for the model closes its span as the failure it was.
            is RunEvent.ToolError -> toolErrors[event.call.toolCallId] = event.error

            is RunEvent.ToolResult -> closeTool(event)

            is RunEvent.StepFinish -> {
                val span = stepSpan(event.stepIndex)
                val outcome = TelemetrySpanOutcome(
                    durationMs = stepStarts.remove(event.stepIndex).elapsed(),
                    usage = event.step.usage,
                )
                registry.dispatch { it.onSpanEnd(span, outcome) }
            }

            is RunEvent.Finish -> {
                val start = runStart ?: return
                runStart = null
                registry.dispatch {
                    it.onSpanEnd(
                        runSpan,
                        TelemetrySpanOutcome(start.elapsed(), usage = event.result.usage),
                    )
                }
            }

            else -> Unit
        }
    }

    private suspend fun closeTool(event: RunEvent.ToolResult) {
        val id = event.result.toolCallId
        // A denied or invalid call gets a result without ever having started, and it has no span to
        // close — reporting one would put work in the exporter that never ran.
        val span = toolSpans.remove(id) ?: return
        val outcome = TelemetrySpanOutcome(
            // The loop's own measurement when it has one; it times around the execution, so
            // concurrent tools do not inherit each other's waits.
            durationMs = event.durationMs ?: toolStarts.remove(id).elapsed(),
            error = toolErrors.remove(id),
        )
        toolStarts.remove(id)
        registry.dispatch { it.onSpanEnd(span, outcome) }
    }

    private fun stepSpan(stepIndex: Int) =
        TelemetrySpan(TelemetrySpanKind.Step, "step", stepIndex = stepIndex)
}

private fun TimeSource.Monotonic.ValueTimeMark?.elapsed(): Long =
    this?.elapsedNow()?.inWholeMilliseconds ?: 0
