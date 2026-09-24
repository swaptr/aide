package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ResponseInfo
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transformWhile

/**
 * Recovery for a provider error received AFTER a round's stream began — the failure a [RetryPolicy]
 * cannot reach, because the request it wraps already succeeded.
 *
 * A provider can accept a request, stream half an answer and then report an error part: an overload, a
 * dropped upstream connection, a verdict reached mid-generation. Without this the round ends there —
 * [RunEvent.Error] is reported and the run keeps what arrived — and the caller's only recovery is a
 * whole new run. With it, the CURRENT round is re-run in isolation: every completed round and its tool
 * results stand, the failed attempt's tool calls are discarded unexecuted, and the round's recorded
 * [Step] — content, usage, finish reason, request and response identity — comes from the successful
 * attempt alone.
 *
 * Text and reasoning the failed attempt already streamed cannot be retracted: those parts reached the
 * collector as they arrived and stay in what it saw, with the blocks they opened closed before the
 * retry's parts begin. They are excluded from the step, from structured-output parsing and from every
 * later round's prompt.
 *
 * Tool parts are held back until the round's stream finishes, so a call from an attempt that then fails
 * is never shown or executed. Absent (the default) nothing is held and nothing is retried, which keeps
 * incremental tool streaming for a caller that only observes errors.
 *
 * A [ToolChoiceViolationError] is never retried: a model that declined a forced tool is not a transient
 * failure. Nor is a retry attempt whose REQUEST fails: that ends the round with the request's error, as
 * [RunEvent.Error], and the run continues with what the last attempt produced.
 *
 * ```kotlin
 * streamText(model, prompt, streamRetries = StreamRetries(maxRetries = 2))
 * streamText(model, prompt, streamRetries = StreamRetries(onError = { error -> error is APICallError }))
 * ```
 *
 * @param maxRetries automatic re-runs of the current round, each costing a fresh model call. `0` leaves
 *   recovery entirely to [onError].
 * @param onError sees every provider error received after streaming began — the retried ones and the
 *   final one — and, once the automatic retries are spent, may answer `true` to ask for ONE more; asked
 *   at most once per round, and its answer is ignored while an automatic retry is still available.
 *   `false` merely observes. The final, unrecovered error is also reported as [RunEvent.Error], exactly
 *   as without this policy.
 */
public data class StreamRetries(
    val maxRetries: Int = 0,
    val onError: (suspend (Throwable) -> Boolean)? = null,
) {

    init {
        if (maxRetries < 0) throw InvalidArgumentError("streamRetries must be >= 0", "streamRetries")
    }
}

/**
 * One streamed round: the model call, its parts forwarded to the run's collector as they arrive, and the
 * same stream assembled into the round's result — re-run under [retries] when the provider reports an
 * error part.
 *
 * The receiver is the run's collector. The assembler is what carries end-of-block metadata onto the parts,
 * so the loop never has to know about signatures — it only has to not drop what it is handed.
 */
internal suspend fun FlowCollector<RunEvent>.streamRound(
    model: LanguageModel,
    options: CallOptions,
    stepIndex: Int,
    timeouts: RunTimeouts,
    retries: StreamRetries?,
): GenerateResult {
    val budget = RetryBudget(retries)
    var streamed = model.doStream(options)
    while (true) {
        val attempt = RoundAttempt(budget, stepIndex, this)
        val assembled = assembleGenerateResult(
            stream = attempt.gate(streamed.stream.withChunkTimeouts(timeouts.firstChunkMs, timeouts.chunkMs)),
            // A provider error the policy did not recover ends the round early; it does not end the run.
            // Throwing here would unwind through the collector and take every completed round with it.
            onError = { error -> emit(RunEvent.Error(error, stepIndex)) },
        )
        if (!attempt.retrying) return assembled.withEnvelope(streamed)

        // The collector saw the failed attempt's block starts; their ends are owed before the retry's
        // parts begin. Nothing else of the attempt survives — the next assembly starts empty.
        attempt.closeOpenBlocks()
        streamed = try {
            model.doStream(options)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The retry never got a stream. The reference ends the round with the request's error rather
            // than the run; the step keeps what the last attempt streamed, and says how it ended.
            emit(RunEvent.Error(e, stepIndex))
            return assembled.copy(finishReason = FinishReason(FinishReason.Unified.Error)).withEnvelope(streamed)
        }
    }
}

/**
 * The envelope holds what a stream cannot: the request body, and the response headers `postSse` reports
 * once before the first event. Only the id, model and timestamp reach the assembler.
 */
private fun GenerateResult.withEnvelope(streamed: StreamResult): GenerateResult = copy(
    request = request ?: streamed.request,
    response = mergeResponse(streamed.response, response),
)

/** What the stream reported about itself, plus what its envelope knew and it could not. */
private fun mergeResponse(envelope: ResponseInfo?, streamed: ResponseInfo?): ResponseInfo? = when {
    envelope == null -> streamed
    streamed == null -> envelope
    else -> envelope.copy(metadata = streamed.metadata)
}

/**
 * One round's retry allowance — the reference's two per-step counters.
 *
 * Automatic retries go first; the callback-directed one is granted only once they are spent, and only
 * once. The observer is told about every error either way, before the decision, so it sees the retried
 * ones too.
 */
private class RetryBudget(private val policy: StreamRetries?) {

    private var automatic = 0
    private var directed = 0

    /** Whether tool parts are held back — true whenever a retry is possible at all. */
    val holdsToolParts: Boolean = policy != null && (policy.maxRetries > 0 || policy.onError != null)

    /** @return true to re-run the round because of [error]. */
    suspend fun recover(error: Throwable): Boolean {
        if (policy == null) return false
        val requested = policy.onError?.invoke(error) ?: false
        return when {
            automatic < policy.maxRetries -> {
                automatic++
                true
            }
            requested && directed < 1 -> {
                directed++
                true
            }
            else -> false
        }
    }
}

/**
 * One attempt at a round: forwards each admitted part to the run's collector and to the assembler, holds
 * tool parts back while a retry is possible, and stops the stream the moment a retry is decided.
 *
 * Holding starts at the first tool part and then keeps everything after it, so held parts never overtake
 * the ones behind them; the hold ends at the round's finish, at the final error, or at the stream's end.
 */
private class RoundAttempt(
    private val budget: RetryBudget,
    private val stepIndex: Int,
    private val run: FlowCollector<RunEvent>,
) {

    var retrying: Boolean = false
        private set
    private val held = mutableListOf<StreamPart>()
    private val openText = LinkedHashSet<String>()
    private val openReasoning = LinkedHashSet<String>()

    fun gate(stream: Flow<StreamPart>): Flow<StreamPart> = stream
        .transformWhile { part -> admit(part) }
        // A stream that ends without a finish part still owes the assembler whatever was held.
        .onCompletion { cause -> if (cause == null && !retrying) flushHeld() }

    /** @return false to stop the stream: a retry has been decided. */
    private suspend fun FlowCollector<StreamPart>.admit(part: StreamPart): Boolean {
        when {
            part is StreamPart.Error -> {
                if (budget.recover(part.error)) {
                    retrying = true
                    return false
                }
                flushHeld()
                forward(part)
            }
            part is StreamPart.Finish -> {
                flushHeld()
                forward(part)
            }
            budget.holdsToolParts && (part.isToolPart() || held.isNotEmpty()) -> held += part
            else -> forward(part)
        }
        return true
    }

    private suspend fun FlowCollector<StreamPart>.flushHeld() {
        for (part in held) forward(part)
        held.clear()
    }

    private suspend fun FlowCollector<StreamPart>.forward(part: StreamPart) {
        when (part) {
            is StreamPart.TextStart -> openText += part.id
            is StreamPart.TextEnd -> openText -= part.id
            is StreamPart.ReasoningStart -> openReasoning += part.id
            is StreamPart.ReasoningEnd -> openReasoning -= part.id
            else -> Unit
        }
        run.emit(RunEvent.Part(part, stepIndex))
        emit(part)
    }

    /** Ends, for the collector, every block the attempt opened and never closed. */
    suspend fun closeOpenBlocks() {
        for (id in openText) run.emit(RunEvent.Part(StreamPart.TextEnd(id), stepIndex))
        for (id in openReasoning) run.emit(RunEvent.Part(StreamPart.ReasoningEnd(id), stepIndex))
    }
}

/** The parts the reference buffers: everything about a tool call, its approval, and its result. */
private fun StreamPart.isToolPart(): Boolean = when (this) {
    is StreamPart.ToolInputStart,
    is StreamPart.ToolInputDelta,
    is StreamPart.ToolInputEnd,
    is StreamPart.ToolCallPart,
    is StreamPart.ToolApprovalRequestPart,
    is StreamPart.ToolResultPart,
    -> true
    else -> false
}
