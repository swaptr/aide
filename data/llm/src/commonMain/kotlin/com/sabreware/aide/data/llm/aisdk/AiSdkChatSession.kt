package com.sabreware.aide.data.llm.aisdk

import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.runtime.RunEvent
import com.sabreware.aide.aisdk.runtime.Step
import com.sabreware.aide.aisdk.runtime.ToolExecutor
import com.sabreware.aide.aisdk.runtime.anyOf
import com.sabreware.aide.aisdk.runtime.isLoopFinished
import com.sabreware.aide.aisdk.runtime.stepCountIs
import com.sabreware.aide.aisdk.runtime.streamText
import com.sabreware.aide.core.common.media.AttachmentBytesReader
import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.ChatGenerationConfig
import com.sabreware.aide.core.domain.llm.ChatSession
import com.sabreware.aide.core.domain.llm.ChatStreamEvent
import com.sabreware.aide.core.domain.llm.ModelWarning
import com.sabreware.aide.core.domain.llm.ToolActivationState
import com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.data.llm.normalizeForWire
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * One chat session over any `:aisdk` [LanguageModel], with the multi-round tool loop run by
 * `:aisdk:runtime`'s [streamText].
 *
 * This replaces AIDE's own turn-runner and its wire codec. The loop, the per-round validation of what
 * the model asked for, and the replay of a completed round — reasoning blocks with their signatures, in
 * the order they were produced — are the runtime's; what stays here is the translation between the
 * runtime's [RunEvent]s and the neutral [ChatStreamEvent] union AIDE persists, plus the client-side
 * history the next turn is built from.
 *
 * **Blocks are delimited, not inferred.** A reasoning block's signed payload arrives on
 * [StreamPart.ReasoningEnd], and is forwarded as a [ChatStreamEvent.ThinkingDelta] with empty text and
 * the payload — which is precisely what [com.sabreware.aide.core.domain.usecase.ReasoningAccumulator]
 * reads as "this block is closed, and this is what signs it". Nothing here inspects the payload.
 *
 * **Tools are dispatched one at a time.** The runtime starts a round's calls concurrently; AIDE's
 * confirm gates and contact picker were only ever driven serially, and two dialogs at once is not
 * something the UI hosts, so the executor holds a mutex around each dispatch.
 *
 * **A round without a `Finish` part is a dropped connection, not a finished answer.** The assembler
 * reports it as the unified reason `other` with no raw string, which is indistinguishable from a vendor
 * that genuinely said `other`; the session therefore watches for the part itself and reports
 * [ChatStreamEvent.StopReason.Interrupted] when it never came.
 *
 * **Exactly one [ChatStreamEvent.Completed] ends a send, even a failed one.** A producer that throws
 * after sending it loses it: the failure cancels the channel's scope before the collector drains the
 * buffer. So the producer never throws — it sends the terminal event, then a failure signal, and the
 * collector's side of the flow is what rethrows, after the event has been delivered.
 */
class AiSdkChatSession(
    private val model: LanguageModel,
    private val tools: List<AideTool>,
    private val config: ChatGenerationConfig,
    private val activationState: ToolActivationState?,
    /** The spec's names of the sampler knobs this model rejects — see [buildCallOptions]. */
    private val disabledParams: Set<String>,
    initialMessages: List<AideMessage>,
    systemInstruction: String?,
    private val dispatcher: ToolDispatcher,
    private val readBytes: AttachmentBytesReader = AttachmentBytesReader { null },
    private val logTag: String = "aisdk",
    // Dispatchers.IO is JVM/Native-only (not in commonMain); the turn is suspend/non-blocking (Ktor +
    // suspend tool dispatch), so Default is the correct multiplatform offload dispatcher here.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ChatSession {

    private val history: MutableList<AideMessage> = mutableListOf<AideMessage>().apply {
        if (!systemInstruction.isNullOrBlank()) add(AideMessage.system(systemInstruction))
        addAll(initialMessages)
    }

    // Attachment bytes, read once per session rather than once per turn: the whole history is mapped
    // for every request, so a chat holding five images re-read five files on every send. Bounded by the
    // history the session already holds; a file that could not be read stays unread (null) rather
    // than being retried on every turn.
    private val attachmentBytes = HashMap<String, ByteArray?>()
    private val cachedReader = AttachmentBytesReader { path ->
        if (path in attachmentBytes) attachmentBytes[path] else readBytes.read(path).also { attachmentBytes[path] = it }
    }

    // The Job of the turn currently streaming, so [cancel] has something to act on. Written when the turn
    // starts and cleared when it unwinds; a session runs at most one turn at a time. It is a CHILD of the
    // producer rather than the producer itself: cancelling a channel coroutine cancels its channel and
    // drops the buffer, so the terminal event could never have been delivered. A SUPERVISOR child, so
    // that a round which fails does not cancel the producer either — the producer has to outlive the
    // failure to deliver `Completed(Error)` through a buffer the collector may still be draining.
    @Volatile
    private var activeTurn: Job? = null

    /** Serialises tool handlers — see the class KDoc. */
    private val toolMutex = Mutex()

    override fun send(
        userMessage: AideMessage,
        dispatchContext: ToolDispatcher.Context,
    ): Flow<ChatStreamEvent> = channelFlow<TurnSignal> {
        val turnJob = SupervisorJob(coroutineContext[Job])
        activeTurn = turnJob
        history += userMessage

        val turn = TurnState()
        val turnStart = TimeSource.Monotonic.markNow()
        // Built per send so the executor answers under THIS turn's context: the turn id is what routes a
        // confirm prompt to the surface that is showing this conversation.
        val functionTools = activeFunctionTools(tools, activationState).associateBy { it.name }
        val executor = ToolExecutor { call, _ -> execute(functionTools[call.toolName], call, turn, dispatchContext) }

        try {
            withContext(turnJob) {
                // Validate/repair the persisted, provider-neutral history ONCE before mapping it; the
                // runtime then checks the shapes the vendors reject before the first request goes out.
                val prompt = history.normalizeForWire().toAisdkPrompt(cachedReader)
                streamText(
                    model = model,
                    prompt = prompt,
                    options = buildCallOptions(prompt, config, tools, activationState, disabledParams),
                    toolExecutor = executor,
                    // No cap on the loop is what let a model alternating two rate-limited tools run
                    // forever: a RATE_LIMITED error result still counts as "more to do".
                    stopWhen = anyOf(isLoopFinished(), stepCountIs(MAX_ROUNDS)),
                ).collect { event -> handle(event, turn) }
            }
            AideLog.i(
                "AidePerf",
                "$logTag.session turn complete rounds=${turn.rounds} " +
                    "totalMs=${turnStart.elapsedNow().inWholeMilliseconds}",
            )
        } catch (ce: CancellationException) {
            // Reached both when [cancel] stopped the turn and when the collector went away. In the first
            // case the producer is still alive and the event is delivered before the cancellation; in the
            // second nobody is listening and the send fails, which is what runCatching is for.
            runCatching { emitEvent(ChatStreamEvent.Completed(ChatStreamEvent.StopReason.Cancelled)) }
            throw ce
        } catch (t: Throwable) {
            AideLog.w(
                "AidePerf",
                "$logTag.session turn error rounds=${turn.rounds}: ${t::class.simpleName}: ${t.message}",
                t,
            )
            // Both deliveries can only fail if the collector is already gone, in which case there is
            // nobody left to tell — never a reason to let a second exception replace the first.
            runCatching { emitEvent(ChatStreamEvent.Completed(ChatStreamEvent.StopReason.Error)) }
            runCatching { send(TurnSignal.Failure(t)) }
        } finally {
            // A completable child that is never completed would keep the producer open forever.
            turnJob.complete()
            activeTurn = null
        }
    }.flowOn(ioDispatcher).transform { signal ->
        when (signal) {
            is TurnSignal.Event -> emit(signal.event)
            is TurnSignal.Failure -> throw signal.error
        }
    }

    private suspend fun ProducerScope<TurnSignal>.emitEvent(event: ChatStreamEvent) {
        send(TurnSignal.Event(event))
    }

    private suspend fun ProducerScope<TurnSignal>.handle(event: RunEvent, turn: TurnState) {
        when (event) {
            is RunEvent.StepStart -> turn.startStep()
            is RunEvent.Part -> handlePart(event.part, turn)
            is RunEvent.ToolStart -> emitEvent(event.call.toStartedEvent(turn.parsedArgs(event.call)))
            // A call the runtime refused to run still gets a started event: the result that follows
            // needs a chip to land on and a persisted call to pair with.
            is RunEvent.InvalidToolCall -> emitEvent(event.call.toStartedEvent(turn.parsedArgs(event.call)))
            is RunEvent.ToolResult -> {
                val envelope = event.result.output.toEnvelopeJson()
                emitEvent(
                    ChatStreamEvent.ToolCallCompleted(
                        callId = event.result.toolCallId,
                        name = event.result.toolName,
                        resultJson = envelope.json,
                        error = envelope.error,
                    ),
                )
            }
            // A provider error mid-stream ends the turn, not just the round: the runtime would carry on
            // to a clean finish with truncated text, which is exactly the "looked finished" failure the
            // streaming transport was tuned against.
            is RunEvent.Error -> throw event.error
            is RunEvent.StepFinish -> finishStep(event.step, turn)
            is RunEvent.Finish -> {
                if (turn.cappedOnTools) {
                    AideLog.w(logTag, "tool loop stopped at the $MAX_ROUNDS-round cap with calls still pending")
                }
                emitEvent(
                    ChatStreamEvent.Completed(
                        stopReason = if (turn.interrupted) {
                            ChatStreamEvent.StopReason.Interrupted
                        } else {
                            event.result.finishReason.toStopReason()
                        },
                        usage = turn.usage(),
                        rawFinishReason = turn.rawFinishReason,
                        warnings = event.result.warnings.map(Warning::toModelWarning),
                    ),
                )
            }
            // A tool's exception already reached the model as its result; approvals are never configured
            // here, so the runtime raises none.
            is RunEvent.ToolError, is RunEvent.Approval, is RunEvent.ApprovalPending -> Unit
        }
    }

    private suspend fun ProducerScope<TurnSignal>.handlePart(part: StreamPart, turn: TurnState) {
        when (part) {
            is StreamPart.TextDelta -> if (part.delta.isNotEmpty()) emitEvent(ChatStreamEvent.TextDelta(part.delta))
            is StreamPart.ReasoningStart -> turn.openReasoning(part.id)
            is StreamPart.ReasoningDelta -> {
                turn.openReasoning(part.id)
                if (part.delta.isNotEmpty()) emitEvent(ChatStreamEvent.ThinkingDelta(part.delta))
            }
            // Where the signed payload lands. Handed downstream untouched with empty text, which is what
            // the accumulator reads as "block closed" — a block without one is closed by whatever follows.
            is StreamPart.ReasoningEnd -> {
                turn.closeReasoning(part.id)
                part.providerMetadata?.takeIf { it.isNotEmpty() }?.let {
                    emitEvent(ChatStreamEvent.ThinkingDelta(text = "", providerMetadata = it))
                }
            }
            is StreamPart.Finish -> turn.sawFinish = true
            // Tool calls reach the session through the runtime's ToolStart, after validation; files,
            // sources and custom blocks have no AidePart.
            else -> Unit
        }
    }

    private fun finishStep(step: Step, turn: TurnState) {
        turn.rounds++
        step.usage.inputTokens.total?.let { turn.promptTokens = it }
        step.usage.outputTokens.total?.let { turn.genTokens = (turn.genTokens ?: 0) + it }
        step.finishReason.raw?.let { turn.rawFinishReason = it }
        // The runtime never executes a tool after a round that ended without a finish reason, so a
        // round missing its `Finish` part is always the last one — and the answer may be truncated.
        if (!turn.sawFinish) turn.interrupted = true
        turn.cappedOnTools = turn.rounds >= MAX_ROUNDS && step.toolResults.isNotEmpty()
        val (durations, args) = turn.closeStep()
        history += step.toAideMessages(durations, args)
    }

    private suspend fun execute(
        tool: AideTool.Function?,
        call: Content.ToolCall,
        turn: TurnState,
        context: ToolDispatcher.Context,
    ): ToolOutput = toolMutex.withLock {
        dispatcher.dispatch(tool, call.toolName, turn.parsedArgs(call), context).toToolOutput()
    }

    override fun reset() {
        history.clear()
    }

    override fun cancel() {
        // A mechanism, not a comment. Cancelling the collector is still the normal path, but this is the
        // port's contract — "stop generating now" — and a caller that holds only the session has to be
        // able to honour it. Cancelling the turn's Job cancels the runtime's in-flight request and any
        // tool it is waiting on; the producer then reports the cancellation and ends the flow.
        activeTurn?.cancel(CancellationException("cancelled by the user"))
    }

    override fun close() {
        history.clear()
    }

    /**
     * Everything one turn accumulates across its rounds.
     *
     * Usage keeps the LAST round's prompt count and SUMS the generated counts — the prompt grows with
     * each round, so summing inputs would bill the conversation several times over. Reasoning blocks
     * are timed per id from the part that opened them to the part that closed them; the durations are
     * drained per step in OPEN order, which is the order the assembler lists the blocks — so a block's
     * slot is claimed the moment it opens and merely filled in when it closes, or a complete redacted
     * block arriving mid-way would be listed before the open block it interrupted.
     *
     * Tool arguments are parsed ONCE per call, when the runtime announces it, and read back by the
     * started event, the dispatcher and the history rebuild — three consumers of one JSON document.
     */
    private class TurnState {
        var promptTokens: Int? = null
        var genTokens: Int? = null
        var rawFinishReason: String? = null
        var sawFinish: Boolean = false
        var interrupted: Boolean = false
        var rounds: Int = 0
        var cappedOnTools: Boolean = false
        private val openReasoning = LinkedHashMap<String, TimeSource.Monotonic.ValueTimeMark>()
        private val reasoningDurations = LinkedHashMap<String, Long>()
        private val argsByCall = HashMap<String, JsonObject>()

        fun startStep() {
            sawFinish = false
        }

        fun openReasoning(id: String) {
            if (id !in reasoningDurations) {
                reasoningDurations[id] = 0L
                openReasoning[id] = TimeSource.Monotonic.markNow()
            }
        }

        fun closeReasoning(id: String) {
            val opened = openReasoning.remove(id)
            if (opened != null) {
                reasoningDurations[id] = opened.elapsedNow().inWholeMilliseconds
            } else if (id !in reasoningDurations) {
                // Closed without ever opening — a redacted block arrives complete, with no text.
                reasoningDurations[id] = 0L
            }
        }

        /** The call's arguments as a JSON object, parsed the first time they are asked for. */
        fun parsedArgs(call: Content.ToolCall): JsonObject =
            argsByCall.getOrPut(call.toolCallId) { call.input.parseArgsOrEmpty() }

        /** The finished step's reasoning durations (open order) and parsed calls; both reset for the next step. */
        fun closeStep(): Pair<List<Long>, Map<String, JsonObject>> {
            openReasoning.keys.toList().forEach(::closeReasoning)
            val durations = reasoningDurations.values.toList()
            reasoningDurations.clear()
            val args = argsByCall.toMap()
            argsByCall.clear()
            return durations to args
        }

        fun usage(): ChatStreamEvent.Usage? =
            if (promptTokens != null || genTokens != null) ChatStreamEvent.Usage(promptTokens, genTokens) else null
    }

    /** What the producer hands the collector: an event to deliver, or the failure to rethrow after them. */
    private sealed interface TurnSignal {
        class Event(val event: ChatStreamEvent) : TurnSignal
        class Failure(val error: Throwable) : TurnSignal
    }

    private companion object {
        /** Rounds per turn before the loop is stopped with calls still pending — see [send]. */
        const val MAX_ROUNDS = 32
    }
}

private fun Content.ToolCall.toStartedEvent(args: JsonObject): ChatStreamEvent.ToolCallStarted = ChatStreamEvent.ToolCallStarted(
    callId = toolCallId,
    name = toolName,
    args = args,
    // Gemini signs the call itself; replaying one without its signature is rejected with
    // `Function call is missing a thought_signature`.
    providerMetadata = providerMetadata,
)

/**
 * The spec's unified reason in AIDE's vocabulary. The vendor's verbatim string travels beside it on
 * [ChatStreamEvent.Completed.rawFinishReason]; only `stop_sequence` is read from it, because the spec
 * folds a stop sequence into a plain stop.
 */
private fun FinishReason.toStopReason(): ChatStreamEvent.StopReason = when (unified) {
    FinishReason.Unified.Stop ->
        if (raw == "stop_sequence") ChatStreamEvent.StopReason.StopSequence else ChatStreamEvent.StopReason.EndTurn
    FinishReason.Unified.Length -> ChatStreamEvent.StopReason.MaxTokens
    FinishReason.Unified.ToolCalls -> ChatStreamEvent.StopReason.ToolUse
    FinishReason.Unified.Error -> ChatStreamEvent.StopReason.Error
    // A content filter or an unmapped vendor reason still ended the turn cleanly.
    FinishReason.Unified.ContentFilter, FinishReason.Unified.Other -> ChatStreamEvent.StopReason.EndTurn
}

private fun Warning.toModelWarning(): ModelWarning = when (this) {
    is Warning.Unsupported -> ModelWarning.UnsupportedSetting(feature, details ?: "not supported by this model")
    is Warning.Compatibility -> ModelWarning.UnsupportedSetting(feature, details ?: "adjusted by the provider")
    is Warning.Deprecated -> ModelWarning.UnsupportedSetting(setting, message)
    is Warning.Other -> ModelWarning.Other(message)
}
