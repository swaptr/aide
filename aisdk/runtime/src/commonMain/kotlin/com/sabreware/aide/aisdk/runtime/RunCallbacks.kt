package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Prompt
import kotlin.coroutines.cancellation.CancellationException

/**
 * What a run knows about itself before its first request.
 *
 * Handed to [RunCallbacks.onStart] after the prompt has been standardized and the options validated, so
 * what it carries is what the first round will actually send — not what the caller configured.
 */
public data class RunStart(
    /** The id every [Step] of this run carries — see [RunResult.callId]. */
    val callId: String,
    /** The model the run was configured with; [PrepareStep] may still swap one in per round. */
    val model: LanguageModel,
    /** The conversation after [standardizePrompt], instructions folded in, assets downloaded. */
    val prompt: Prompt,
    /** The run's options after validation; each round's own prompt is rebuilt from [prompt]. */
    val options: CallOptions,
)

/**
 * A run ended before it finished: the collector cancelled, or a total or per-step budget expired.
 *
 * [steps] holds every round that COMPLETED. The tool results in them may have had effects, and a caller
 * that persists partial progress wants exactly this list — the round that was in flight is not here, and
 * its parts already reached the collector as they arrived.
 */
public data class RunAbort(
    /** The id the abandoned run carried — see [RunResult.callId]. */
    val callId: String,
    /** Every round that finished before the run was abandoned, in order. */
    val steps: List<Step>,
    /**
     * What ended the run: the collector's own cancellation, or a
     * [kotlinx.coroutines.TimeoutCancellationException] from [RunTimeouts.totalMs] or [RunTimeouts.stepMs].
     */
    val cause: CancellationException,
)

/**
 * The two moments the event flow cannot carry.
 *
 * Everything else a run does is a [RunEvent], and the note at the top of `RunMetrics.kt` is why a second
 * callback surface is refused. These two are the exceptions, each for a structural reason. [onStart]
 * fires before the flow has produced anything, and a consumer attaching an operator cannot be sure of
 * seeing a first event before the request is already out. [onAbort] fires AFTER the collector has gone
 * away, when emitting is impossible — a cancelled flow can only be told about, never told through.
 *
 * [onAbort] runs on the run's own coroutine under `NonCancellable`, so a hook that persists partial steps
 * completes its write, and the cancellation is rethrown untouched afterwards: the run still stops, and
 * structured concurrency still sees the cancel it asked for.
 */
public data class RunCallbacks(
    /** Before any model call, once per run. */
    val onStart: (suspend (RunStart) -> Unit)? = null,
    /** The run was abandoned — see [RunAbort] for what "abandoned" covers and what it carries. */
    val onAbort: (suspend (RunAbort) -> Unit)? = null,
)
