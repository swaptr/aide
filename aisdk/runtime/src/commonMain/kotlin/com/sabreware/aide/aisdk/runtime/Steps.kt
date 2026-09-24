package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.ResponseInfo
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.getErrorMessage
import com.sabreware.aide.aisdk.util.parseJsonElementOrNull
import kotlinx.serialization.json.JsonObject

/**
 * What a tool can see beyond its own arguments.
 *
 * The reference hands its tools the conversation, an abort signal and a caller-supplied context
 * object. The first is here; the second is coroutine cancellation, which a Kotlin tool already
 * observes for free; the third is deliberately absent — a Kotlin executor is a closure, and anything a
 * JS config object would carry is captured where the executor is built, with types intact.
 */
public data class ToolCallContext(
    /**
     * The messages the model was shown in the round that produced this call — the same list the
     * request carried, [StepPlan.messages] rewrites included. A tool that needs the user's actual
     * words — a search tool deriving a query, a memory tool deciding what to store — reads them here
     * instead of being handed only the model's paraphrase in its arguments.
     */
    val messages: Prompt,
    /**
     * The round the call was made in, counted from zero.
     *
     * Negative for a call executed while RESUMING a stored approval: that work happens before the
     * run's first round, so it belongs to no round of this run.
     */
    val stepIndex: Int,
)

/** Runs one tool call and returns what it produced. */
public fun interface ToolExecutor {

    /**
     * @param context where the run stands — see [ToolCallContext]. A tool that needs none ignores it.
     * @return the tool's output, or an error output. Throwing is also allowed and is converted by
     *   [ToolErrorFormatter] — a tool that crashes should not end the conversation.
     */
    public suspend fun execute(call: Content.ToolCall, context: ToolCallContext): ToolOutput
}

/**
 * Fixes a tool call the model got wrong.
 *
 * Invoked when the call fails validation — an unknown tool name, input that is not JSON, or input that
 * omits a required property — and never on a call that passed. A hook that runs on every call is one that
 * will eventually rewrite a correct one, so the trigger is the failure rather than the opportunity.
 *
 * Nothing this hook does can end the run. Returning null, returning a call that fails validation a second
 * time, and throwing all reach the same place: the call is marked [Content.ToolCall.invalid], replayed so
 * the turn stays well-formed, and never executed. That default is the point — a repair hook is an
 * optional recovery mechanism, and one that could execute a call it failed to fix, or take the
 * conversation down when it threw, would be a new failure mode bought with an optimisation.
 *
 * @return the corrected call, or null to let the failure reach the model as an error result — which is
 *   often the better outcome, since the model can read it and try something else.
 */
public fun interface ToolCallRepair {

    public suspend fun repair(call: Content.ToolCall, error: Throwable): Content.ToolCall?
}

/**
 * Decides whether a provider-requested tool may run.
 *
 * Denial is an outcome rather than an error: the model is told it was refused and can choose another
 * path. Absent a handler nothing is approved, because defaulting to "yes" would make a provider able to
 * run tools the host never agreed to.
 */
public fun interface ApprovalHandler {

    /** @return true to let the tool run; [call] is the matching call, when one was found. */
    public suspend fun approve(request: Content.ToolApprovalRequest, call: Content.ToolCall?): Boolean
}

/**
 * Turns a tool's exception into the output the model sees.
 *
 * A hook rather than a fixed rule because the two audiences want different things from the same failure.
 * The default is prose, which every vendor accepts and every model reads; a caller whose tools raise
 * errors with codes worth acting on returns [ToolOutput.ErrorJson] instead, and the model gets something
 * it can branch on rather than a sentence it has to parse. The raw throwable reaches the caller either
 * way, on [RunEvent.ToolError].
 */
public fun interface ToolErrorFormatter {

    /** @return the output replayed to the model in place of the result [error] prevented. */
    public fun format(call: Content.ToolCall, error: Throwable): ToolOutput
}

/** Prose, which is what every vendor accepts and every model can read. */
internal val DefaultToolErrorFormatter: ToolErrorFormatter = ToolErrorFormatter { call, error ->
    ToolOutput.ErrorText("Tool '${call.toolName}' failed: ${getErrorMessage(error)}")
}

/** One round: a model call and whatever tools it triggered. */
public data class Step(
    /** What the model produced this round, in order. */
    val content: List<Content>,
    /** Why this round's generation ended — `stop`, `tool-calls`, a length cap, a filter. */
    val finishReason: FinishReason,
    /** What this round cost. [RunResult.usage] is the sum across rounds. */
    val usage: Usage,
    /** What the model ignored or changed about this round's call. */
    val warnings: List<Warning> = emptyList(),
    /** Results for the tool calls in [content]. Empty on a round that called none. */
    val toolResults: List<ToolPart.Result> = emptyList(),
    /** Decisions on the approval requests in [content], which travel on the wire beside the results. */
    val approvalResponses: List<ToolPart.ApprovalResponse> = emptyList(),
    /**
     * Response-level provider payload for this round.
     *
     * Anthropic's cache-token companions and `service_tier`, OpenAI's per-step system fingerprint. Kept
     * per step rather than only on the run, because they differ round to round and a run that reports
     * only the last one cannot answer what an earlier round cost.
     */
    val providerMetadata: ProviderMetadata? = null,
    /**
     * The request this round actually sent. Its body is kept only under [RunInclude.requestBody] —
     * the bug report that needs it turns the switch on; every other run sheds it.
     */
    val request: RequestInfo? = null,
    /**
     * This round's response identity and headers — the id a vendor support ticket asks for. The body,
     * where a provider reports one, is kept only under [RunInclude.responseBody].
     */
    val response: ResponseInfo? = null,
    /**
     * The run this round belongs to — see [RunResult.callId]. Null on a step built outside a run: a
     * hand-assembled replay or a test fixture; a batch item built from a stored result names its
     * request instead. The loop always sets it.
     */
    val callId: String? = null,
    /** This round's index within its run, counted from zero — what [RunEvent.StepFinish.stepIndex] carries. */
    val stepNumber: Int = 0,
) {

    /** This round's text parts, joined. */
    val text: String get() = content.filterIsInstance<Content.Text>().joinToString("") { it.text }

    /** This round's reasoning parts, joined — display text only; replay reads [content]. */
    val reasoning: String
        get() = content.filterIsInstance<Content.Reasoning>().joinToString("\n") { it.text }

    /** Every tool call this round made, valid or not, provider-executed or not. */
    val toolCalls: List<Content.ToolCall> get() = content.filterIsInstance<Content.ToolCall>()
}

/** Everything a run produced. */
public data class RunResult(
    /**
     * One id per run, minted before the first request and stamped on every [Step] and every callback.
     *
     * The key that joins a run's records across logs: a step, a metrics row, an abort report and the
     * final result all say which run they belong to, so a host tracing several concurrent runs through
     * one logger tells them apart without inventing a correlation scheme of its own.
     */
    val callId: String,
    /** Every round, in order. */
    val steps: List<Step>,
    /** Usage summed across every round; an unreported count stays null rather than reading as zero. */
    val usage: Usage,
    /** The last round's finish reason — how the run as a whole ended. */
    val finishReason: FinishReason,
    /** The messages appended to the prompt across the run — assistant turns and tool turns, in order. */
    val messages: List<ModelMessage>,
    /**
     * The approval requests the run ended still waiting on — empty on a run that finished its work.
     *
     * Non-empty means the run STOPPED rather than completed: it holds tool calls nothing was allowed
     * to execute, and the caller's next move is to show these to whoever decides, persist the
     * conversation, and resume with the answers — see [ToolApprovals].
     */
    val pendingApprovals: List<Content.ToolApprovalRequest> = emptyList(),
) {

    /**
     * The LAST round's text: the answer. Earlier rounds' text is what the model said while it was
     * still working — *"let me check that file"* around a tool call — and a caller rendering the
     * result wants the conclusion, not the narration.
     */
    val text: String get() = steps.lastOrNull()?.text.orEmpty()

    /**
     * Every round's text, concatenated. For the caller that needs everything the model wrote —
     * [generateOutput] reads this, because a structured answer can span rounds when a tool call
     * interrupts it mid-document.
     */
    val allText: String get() = steps.joinToString("") { it.text }

    /**
     * Every warning any round produced.
     *
     * Flattened rather than left per step because a warning is about the CALL — *"this model ignores
     * topK"* — and a caller that has to walk the steps to find that out is a caller that does not.
     */
    val warnings: List<Warning> get() = steps.flatMap { it.warnings }

    /** The last round's response payload, which is the one that produced [text]. */
    val providerMetadata: ProviderMetadata? get() = steps.lastOrNull()?.providerMetadata

    /** The last round's request; per-round requests are on [steps]. */
    val request: RequestInfo? get() = steps.lastOrNull()?.request

    /** The last round's response identity; per-round responses are on [steps]. */
    val response: ResponseInfo? get() = steps.lastOrNull()?.response

}

/** What a run emits while it runs. */
public sealed interface RunEvent {

    /**
     * A round is about to call the model, with the options it will be called with.
     *
     * The only event emitted BEFORE a request, and the reason the two numbers that matter — per-step
     * latency and time to first token — are measurable at all. Without it the earliest observable
     * moment of a round is its first token, which is the thing being measured.
     *
     * [options] is what the round actually sends, after [PrepareStep] and any narrowing, so a caller
     * logging a run records the request that was made rather than the one that was configured.
     */
    public data class StepStart(val stepIndex: Int, val options: CallOptions) : RunEvent

    /** A part from the model, forwarded untouched. */
    public data class Part(val part: StreamPart, val stepIndex: Int) : RunEvent

    /** A tool is about to run. Emitted for every call in a round before any of them starts. */
    public data class ToolStart(val call: Content.ToolCall, val stepIndex: Int) : RunEvent

    /**
     * A tool finished. Emitted as each one completes, not batched at the end of the round.
     *
     * [durationMs] is measured around the execution itself, so tools running concurrently each report
     * their own time. Deriving it from when this event arrives would charge every tool in a batch the
     * duration of the slowest one before it, because results are reported in call order. It is null for
     * a result nothing executed: a denied call, or one that failed validation.
     */
    public data class ToolResult(
        val result: ToolPart.Result,
        val stepIndex: Int,
        val durationMs: Long? = null,
    ) : RunEvent

    /**
     * A tool threw, with the exception intact.
     *
     * The model gets whatever [ToolErrorFormatter] made of it; this event is how the caller gets the
     * cause. Stringifying at the boundary and emitting nothing else is how a structured failure — an API
     * error carrying a code — becomes prose nobody can inspect.
     */
    public data class ToolError(
        val call: Content.ToolCall,
        val error: Throwable,
        val stepIndex: Int,
    ) : RunEvent

    /**
     * A call the model made that will not be run: an unknown tool, unparseable input, a missing required
     * property, or a repair that did not fix it. Replayed to the model, never executed.
     */
    public data class InvalidToolCall(
        val call: Content.ToolCall,
        val error: Throwable,
        val stepIndex: Int,
    ) : RunEvent

    /** A tool may not run until someone decides, and got an answer — from the provider flow's handler,
     * from policy (the request's `isAutomatic` says which), or from the client gate's handler. */
    public data class Approval(
        val request: Content.ToolApprovalRequest,
        val approved: Boolean,
        val stepIndex: Int,
    ) : RunEvent

    /**
     * A call is waiting on a decision nothing in-process can make. The run ends after this round; the
     * caller shows [request] to whoever decides and resumes with the answer on a later invocation.
     */
    public data class ApprovalPending(
        val request: Content.ToolApprovalRequest,
        val call: Content.ToolCall,
        val stepIndex: Int,
    ) : RunEvent

    /**
     * The provider reported an error mid-stream.
     *
     * An event rather than an exception, because a round that fails after three successful rounds should
     * cost the caller that round and not the conversation. The run continues with whatever the round
     * produced before the error.
     */
    public data class Error(val error: Throwable, val stepIndex: Int) : RunEvent

    /** A round completed, including its tool results. */
    public data class StepFinish(val step: Step, val stepIndex: Int) : RunEvent

    /** The run completed. Exactly one, last. */
    public data class Finish(val result: RunResult) : RunEvent
}

/**
 * Whether to run another round.
 *
 * Returning true STOPS. Modelled as a predicate over the steps so far rather than a step count, because
 * the useful conditions are not all counts — "stop once the model called `submit`" is as common as
 * "stop after five rounds".
 *
 * Suspending because the interesting conditions ask something: *"stop once the database says the task is
 * done"* is not expressible against a blocking predicate, and a caller forced to block a coroutine
 * dispatcher to answer it has been handed a worse problem than the one it solved.
 */
public fun interface StopCondition {

    /** @return true to STOP after the round [steps] ends with. */
    public suspend fun shouldStop(steps: List<Step>): Boolean
}

/** Stop once [count] rounds have run. */
public fun stepCountIs(count: Int): StopCondition = StopCondition { it.size >= count }

/**
 * Stop once the LAST round called one of [names].
 *
 * The last round, not any round: a condition that scans the whole run fires again on every subsequent
 * round, so "stop after the model calls `submit`" would also stop a run that called `submit` first and
 * still has work queued behind it.
 */
public fun hasToolCall(vararg names: String): StopCondition = StopCondition { steps ->
    steps.lastOrNull()?.toolCalls.orEmpty().any { it.toolName in names }
}

/**
 * Never stop early — run until the model stops asking for tools.
 *
 * Spelled as its own condition because the alternative, `stepCountIs(Int.MAX_VALUE)`, reads as a bound
 * that was chosen when it is really a bound that was declined.
 */
public fun isLoopFinished(): StopCondition = StopCondition { false }

/** Stop when any of [conditions] says to. */
public fun anyOf(vararg conditions: StopCondition): StopCondition = StopCondition { steps ->
    conditions.any { it.shouldStop(steps) }
}

/**
 * Turns a completed step into the assistant turn that gets replayed next round.
 *
 * **This function is where a run is made correct or broken.** Every reasoning part keeps its
 * `providerMetadata` — carried across as `providerOptions` — and every part keeps its position. Anthropic
 * requires a replayed turn to begin with its thinking block and rejects a modified signature outright;
 * Gemini requires thought blocks resent exactly as received. Rebuilding this turn from display text, or
 * filtering it to "the parts we understand", is precisely the bug that every LLM framework currently has
 * an open issue for.
 *
 * Three things are deliberately NOT replayed, each because sending it is a 400:
 *
 * - **An empty text block.** A model that opens a text block, emits nothing and switches to `tool_use` is
 *   the commonest shape in a tool-calling turn, and Anthropic answers a zero-length text block with
 *   *"text content blocks must be non-empty"*.
 * - **A client-executed tool result.** Those belong to the tool turn; replaying one here as well sends it
 *   to the model twice.
 * - **Unparseable input on an invalid call.** The call itself must be replayed — the turn is malformed
 *   without it — but its arguments are by definition the thing the model got wrong, so they go as `{}`.
 */
public fun Step.toAssistantMessage(): ModelMessage.Assistant = ModelMessage.Assistant(
    content = content.mapNotNull { part ->
        when (part) {
            is Content.Text ->
                part.text.takeIf { it.isNotEmpty() }
                    ?.let { AssistantPart.Text(it, providerOptions = part.providerMetadata) }
            is Content.Reasoning -> AssistantPart.Reasoning(
                text = part.text,
                // The signature. Losing it here undoes every precaution the provider took.
                providerOptions = part.providerMetadata,
            )
            is Content.File -> AssistantPart.File(
                data = part.data,
                mediaType = part.mediaType,
                providerOptions = part.providerMetadata,
            )
            is Content.ReasoningFile -> AssistantPart.ReasoningFile(
                data = part.data,
                mediaType = part.mediaType,
                providerOptions = part.providerMetadata,
            )
            is Content.Custom -> AssistantPart.Custom(
                kind = part.kind,
                providerOptions = part.providerMetadata,
            )
            is Content.ToolCall -> AssistantPart.ToolCall(
                toolCallId = part.toolCallId,
                toolName = part.toolName,
                input = part.replayableInput(),
                providerExecuted = part.providerExecuted,
                providerOptions = part.providerMetadata,
            )
            is Content.ToolResult ->
                part.takeIf { it.providerExecuted }?.let {
                    AssistantPart.ToolResult(
                        toolCallId = it.toolCallId,
                        toolName = it.toolName,
                        output = it.output,
                        providerOptions = it.providerMetadata,
                    )
                }
            // Replayed so the exchange survives storage: a request that only ever existed in the
            // response types leaves a stored conversation holding a call, no result, and no record
            // that anything was asked — indistinguishable from a truncated turn. Providers that have
            // no wire form for it skip it.
            is Content.ToolApprovalRequest -> AssistantPart.ApprovalRequest(
                approvalId = part.approvalId,
                toolCallId = part.toolCallId,
                reason = part.reason,
                isAutomatic = part.isAutomatic,
                signature = part.signature,
                providerOptions = part.providerMetadata,
            )
            // A source is response-only.
            else -> null
        }
    },
)

/**
 * The tool turn that answers this step's calls and its approval requests.
 *
 * Approval decisions lead, because a vendor that raised the request is looking for the response to it
 * before the result it gates.
 */
public fun Step.toToolMessage(): ModelMessage.Tool? =
    (approvalResponses + toolResults).takeIf { it.isNotEmpty() }?.let { ModelMessage.Tool(it) }

/** `{}` for an invalid call whose arguments are not a JSON object — see [toAssistantMessage]. */
private fun Content.ToolCall.replayableInput(): String =
    if (invalid && parseJsonElementOrNull(input) !is JsonObject) "{}" else input

/** Sums usage across rounds, treating an unreported count as unknown rather than as zero. */
internal fun List<Usage>.sum(): Usage = Usage(
    inputTokens = Usage.InputTokens(
        total = sumOrNull { it.inputTokens.total },
        noCache = sumOrNull { it.inputTokens.noCache },
        cacheRead = sumOrNull { it.inputTokens.cacheRead },
        cacheWrite = sumOrNull { it.inputTokens.cacheWrite },
    ),
    outputTokens = Usage.OutputTokens(
        total = sumOrNull { it.outputTokens.total },
        text = sumOrNull { it.outputTokens.text },
        reasoning = sumOrNull { it.outputTokens.reasoning },
    ),
)

/**
 * Null when NO round reported the figure.
 *
 * A run where one provider reports tokens and another does not should show the partial total, not zero —
 * zero reads as "this was free".
 */
private fun List<Usage>.sumOrNull(select: (Usage) -> Int?): Int? {
    val values = mapNotNull(select)
    return if (values.isEmpty()) null else values.sum()
}
