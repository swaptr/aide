package com.sabreware.aide.aisdk.runtime.telemetry

import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.runtime.RunEvent
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

// ---------------------------------------------------------------------------------------------------
// Telemetry, as an operator over the run's own event flow.
//
// Not a second callback surface. The loop already says everything that happens, in order, on one flow;
// a parallel `onStepFinish`/`onToolCall` set would be a second thing to keep correct and a second thing
// to remember to fire, and would still not compose with anything. An operator that passes every event
// through untouched composes with every other operator, cannot drop an event by forgetting a case, and
// costs a run that does not use it exactly nothing.
// ---------------------------------------------------------------------------------------------------

/** How one round went. */
public data class StepMetrics(
    /** Which round this is, counted from zero. */
    val stepIndex: Int,
    /** From issuing the request to the round finishing, tools included. Null while it is still running. */
    val latencyMs: Long? = null,
    /**
     * From issuing the request to the first text or reasoning delta.
     *
     * The number a user actually experiences as "is it working", and the one a total latency hides: a
     * round that streams for eight seconds after answering in two feels nothing like one that thinks for
     * eight and answers in one. Null on a round that streamed no text — a tool-only round, or a
     * `generateText` call, which has no deltas to be first.
     */
    val timeToFirstTokenMs: Long? = null,
    /**
     * From issuing the request to the first output of ANY kind — text, reasoning, a tool-input delta, a
     * file, a complete tool call.
     *
     * Wider than [timeToFirstTokenMs] because it feeds the throughput split: a tool-only round produced
     * output too, and its generation rate is as measurable as prose. Null on a `generateText` round.
     */
    val timeToFirstOutputMs: Long? = null,
    /**
     * From issuing the request to the model's last word — the round minus its tools.
     *
     * A stream ends it at its terminal part; a non-streaming round at the moment its first tool is
     * announced, or at the round's end when it called none. Null while the model is still answering.
     */
    val responseTimeMs: Long? = null,
    /** What the round cost. Null until it finishes. */
    val usage: Usage? = null,
    /** Throughput, derived once the round finishes — see [StepPerformance]. */
    val performance: StepPerformance? = null,
)

/** How one tool call went. */
public data class ToolMetrics(
    /** The id pairing this call with its result. */
    val toolCallId: String,
    /** Which tool ran. */
    val toolName: String,
    /** The round the call was made in, counted from zero. */
    val stepIndex: Int,
    /** Measured around the execution, so concurrent tools do not inherit each other's waits. */
    val durationMs: Long?,
    /** Whether the tool threw — true even when the model was handed a formatted error output. */
    val failed: Boolean,
)

/**
 * A run's timings so far.
 *
 * "So far" is the point: this is published while the run is in flight, so a surface can show the
 * elapsed time of the round it is waiting on rather than only learning what it cost once it is over.
 */
public data class RunMetrics(
    /** One entry per round started, in order. */
    val steps: List<StepMetrics> = emptyList(),
    /** One entry per tool result, in completion-report order. */
    val tools: List<ToolMetrics> = emptyList(),
    /** From the first request to the run finishing. Null until it does. */
    val totalMs: Long? = null,
) {

    /** The first round's time to first token — the latency a user attributes to the whole run. */
    val timeToFirstTokenMs: Long? get() = steps.firstOrNull()?.timeToFirstTokenMs
}

/**
 * Times a run, and publishes what it has measured as it measures it.
 *
 * One recorder per run. It is fed by [recordingTo], which is the only thing that should call [record].
 */
public class RunMetricsRecorder {

    private val state = MutableStateFlow(RunMetrics())

    /** Timings so far. Collect it to watch a run; read `value` after it to report on one. */
    public val metrics: StateFlow<RunMetrics> = state.asStateFlow()

    private var runStart: TimeSource.Monotonic.ValueTimeMark? = null
    private val clocks = mutableMapOf<Int, StepClock>()
    private val failedCalls = mutableSetOf<String>()

    internal fun record(event: RunEvent) {
        when (event) {
            is RunEvent.StepStart -> startStep(event.stepIndex)
            is RunEvent.Part -> recordPart(event.part, event.stepIndex)
            // A non-streaming round has no terminal part; the model is done by the time a tool is
            // announced, and that is the closest observable moment to when it finished.
            is RunEvent.ToolStart -> responseEnded(event.stepIndex)
            // Recorded before the result, so a tool that both failed and produced an error output is
            // reported as failed rather than as a call that returned something.
            is RunEvent.ToolError -> failedCalls += event.call.toolCallId
            is RunEvent.ToolResult -> recordTool(event)
            is RunEvent.StepFinish -> finishStep(event)
            is RunEvent.Finish -> state.value = state.value.copy(totalMs = runStart?.elapsed())
            else -> Unit
        }
    }

    private fun startStep(stepIndex: Int) {
        if (runStart == null) runStart = TimeSource.Monotonic.markNow()
        clocks[stepIndex] = StepClock()
        state.value = state.value.copy(steps = state.value.steps + StepMetrics(stepIndex))
    }

    private fun recordPart(part: StreamPart, stepIndex: Int) {
        val clock = clocks[stepIndex] ?: return
        if (part is StreamPart.TextDelta || part is StreamPart.ReasoningDelta) {
            updateStep(stepIndex) { existing ->
                // Only the first: every later delta would overwrite the number with the time of the
                // last token, which is the opposite measurement.
                if (existing.timeToFirstTokenMs != null) existing else existing.copy(timeToFirstTokenMs = clock.elapsed())
            }
        }
        if (part.isOutputChunk()) {
            clock.output()
            updateStep(stepIndex) { existing ->
                if (existing.timeToFirstOutputMs != null) existing else existing.copy(timeToFirstOutputMs = clock.firstOutputMs)
            }
        }
        if (part is StreamPart.Finish) responseEnded(stepIndex)
    }

    private fun responseEnded(stepIndex: Int) {
        val clock = clocks[stepIndex] ?: return
        if (clock.responseTimeMs != null) return
        clock.responseEnded()
        updateStep(stepIndex) { it.copy(responseTimeMs = clock.responseTimeMs) }
    }

    private fun recordTool(event: RunEvent.ToolResult) {
        state.value = state.value.copy(
            tools = state.value.tools + ToolMetrics(
                toolCallId = event.result.toolCallId,
                toolName = event.result.toolName,
                stepIndex = event.stepIndex,
                durationMs = event.durationMs,
                failed = event.result.toolCallId in failedCalls,
            ),
        )
    }

    private fun finishStep(event: RunEvent.StepFinish) {
        // A round that called no tools and streamed no terminal part ends its response here.
        responseEnded(event.stepIndex)
        val clock = clocks[event.stepIndex]
        updateStep(event.stepIndex) { existing ->
            existing.copy(
                latencyMs = clock?.elapsed(),
                usage = event.step.usage,
                performance = clock?.let {
                    stepPerformance(event.step.usage, it.responseTimeMs ?: it.elapsed(), it.firstOutputMs, it.gapsMs)
                },
            )
        }
    }

    private fun updateStep(stepIndex: Int, transform: (StepMetrics) -> StepMetrics) {
        val current = state.value
        val existing = current.steps.firstOrNull { it.stepIndex == stepIndex } ?: return
        val updated = transform(existing)
        // A transform that changed nothing publishes nothing: this runs per streamed delta, and every
        // publish copies the step list for every subscriber.
        if (updated === existing) return
        state.value = current.copy(steps = current.steps.map { if (it.stepIndex == stepIndex) updated else it })
    }
}

/**
 * One round's stopwatch: when it started, when its output arrived, and when the model went quiet.
 *
 * The gaps are collected here rather than derived from event timestamps because the events carry
 * none — the loop forwards parts as they arrive, and the only clock is the one reading them.
 */
private class StepClock {

    private val start = TimeSource.Monotonic.markNow()
    private var previousOutput: TimeSource.Monotonic.ValueTimeMark? = null

    var firstOutputMs: Long? = null
        private set
    var responseTimeMs: Long? = null
        private set
    val gapsMs = mutableListOf<Long>()

    fun elapsed(): Long = start.elapsed()

    fun output() {
        val previous = previousOutput
        if (previous == null) firstOutputMs = elapsed() else gapsMs += previous.elapsed()
        previousOutput = TimeSource.Monotonic.markNow()
    }

    fun responseEnded() {
        responseTimeMs = elapsed()
    }
}

/**
 * Passes every event through untouched, timing the run into [recorder] as it goes.
 *
 * Placed anywhere in a chain of operators, because it changes nothing: `streamText(…).recordingTo(r)`
 * measures the loop, and putting it after a filter measures whatever is left, which is occasionally
 * what a caller wants and never a surprise.
 */
public fun Flow<RunEvent>.recordingTo(recorder: RunMetricsRecorder): Flow<RunEvent> = flow {
    collect { event ->
        recorder.record(event)
        emit(event)
    }
}

private fun TimeSource.Monotonic.ValueTimeMark.elapsed(): Long = elapsedNow().inWholeMilliseconds
