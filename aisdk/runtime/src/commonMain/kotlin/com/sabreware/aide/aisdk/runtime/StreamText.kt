package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.IdGenerator
import com.sabreware.aide.aisdk.util.RetryPolicy
import com.sabreware.aide.aisdk.util.withRetry
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * The multi-round tool loop, over any [LanguageModel].
 *
 * One loop serves every vendor because it speaks only the spec: it never learns a provider's name, and a
 * new provider needs no change here. That is the whole reason the spec exists.
 *
 * Each round calls the model, forwards its parts untouched, runs whatever tools it asked for, appends the
 * assistant turn and the tool turn to the prompt, and goes again — until [stopWhen] says otherwise or the
 * model stops asking for tools.
 *
 * The correctness property that matters is in [Step.toAssistantMessage]: a replayed assistant turn keeps
 * its reasoning parts, their `providerMetadata`, and their order. Anthropic rejects a modified signature
 * outright; Gemini requires thought blocks resent exactly as received. Everything else here is
 * bookkeeping around that one rule.
 *
 * [prompt] is put through [standardizePrompt] before the first round, so a caller hands over whatever its
 * storage maps to and the vendor-shape rules — one tool turn per round, no mid-conversation system turn,
 * no unanswered tool call — are checked here rather than rediscovered as a 400.
 *
 * The returned flow is cold and single-shot. Cancelling the collector cancels the in-flight request,
 * which is why nothing takes an abort parameter.
 *
 * ```kotlin
 * val model = AnthropicProvider(client, apiKey).languageModel("claude-opus-4-5")
 * streamText(
 *     model = model,
 *     prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Say hi")))),
 * ).textDeltas().collect(::print)
 * ```
 *
 * @param model the model every round calls, unless [PrepareStep] swaps one in for a round.
 * @param prompt the conversation so far; validated by [standardizePrompt] before the first round.
 * @param options every other call setting; its own `prompt` is rebuilt each round from [prompt] plus the
 *   appended turns, so only the rest of it is read.
 * @param toolExecutor runs the client-side tool calls. Null means no call is ever executed.
 * @param stopWhen when to stop looping; the default is a single round.
 * @param repairToolCall one chance to fix a call that failed validation — see [ToolCallRepair].
 * @param approveTool answers provider approval requests. Absent, nothing is approved.
 * @param onToolError turns a thrown tool exception into the output the model sees.
 * @param prepareStep per-round overrides — model, tools, sampling; see [PrepareStep].
 * @param timeouts the run, step, stream-gap and tool limits; all unlimited by default.
 * @param retry loop-level retries. [RetryPolicy.None] because every provider already retries inside its
 *   transport; a second default here would compound to up to nine attempts per call.
 * @param streamRetries recovery for a provider error received AFTER a round's stream began — the failure
 *   [retry] cannot reach. Null, the default, retries nothing: the error costs the round and is reported
 *   as [RunEvent.Error]. See [StreamRetries].
 * @param instructions becomes the leading system turn, via [standardizePrompt].
 * @param downloadAssets fetches URL attachments the model cannot fetch itself; null sends URLs as they are.
 * @param toolApprovals the client-side approval gate — policy and signing secret; see [ToolApprovals].
 * @param toolOrder the order tools are sent in: listed names first, the rest alphabetically after — see
 *   [StepPlan.toolOrder], which overrides this for one round. Null keeps the caller's own order.
 * @param allowSystemInMessages whether [prompt] may carry its own system turns — see [standardizePrompt].
 * @param include what each [Step] retains beyond its content — see [RunInclude].
 * @param callbacks the two hooks the event flow cannot carry — see [RunCallbacks].
 * @param logWarnings where each round's warnings go — see [WarningLogger].
 */
@Suppress("LongParameterList")
public fun streamText(
    model: LanguageModel,
    prompt: Prompt,
    options: CallOptions = CallOptions(prompt = prompt),
    toolExecutor: ToolExecutor? = null,
    stopWhen: StopCondition = stepCountIs(1),
    repairToolCall: ToolCallRepair? = null,
    approveTool: ApprovalHandler? = null,
    onToolError: ToolErrorFormatter = DefaultToolErrorFormatter,
    prepareStep: PrepareStep? = null,
    timeouts: RunTimeouts = RunTimeouts(),
    // None at THIS layer is not "no retries": ProviderHttp retries retryable failures (2 by default)
    // inside every provider. A second default here would compound to up to nine attempts per call.
    retry: RetryPolicy = RetryPolicy.None,
    streamRetries: StreamRetries? = null,
    instructions: String? = null,
    downloadAssets: AssetDownloader? = null,
    toolApprovals: ToolApprovals? = null,
    toolOrder: List<String>? = null,
    allowSystemInMessages: Boolean = true,
    include: RunInclude = RunInclude(),
    callbacks: RunCallbacks = RunCallbacks(),
    logWarnings: WarningLogger = WarningLogger.Console,
): Flow<RunEvent> = runLoop(
    LoopConfig(
        model, prompt, options, toolExecutor, stopWhen, repairToolCall, approveTool, onToolError,
        prepareStep, timeouts, retry, instructions, downloadAssets, toolApprovals,
        toolOrder, allowSystemInMessages, include, callbacks, logWarnings,
    ),
) { stepModel, callOptions, stepIndex ->
    // Forward every part as it arrives, and assemble the same stream into content — re-run under
    // `streamRetries` when the provider reports an error part; see StreamRetries.kt.
    val result = streamRound(stepModel, callOptions, stepIndex, timeouts, streamRetries)
    result.toolChoiceViolation(stepModel, callOptions)?.let { violation ->
        // Reported after the completed call was assembled, exactly as an error part arriving after the
        // finish would be: the round keeps what it cost, and ends with an error finish. Never retried.
        emit(RunEvent.Error(violation, stepIndex))
        return@runLoop result.copy(
            finishReason = FinishReason(FinishReason.Unified.Error, result.finishReason.raw),
        )
    }
    result
}

/**
 * Runs the whole loop and returns the result.
 *
 * Calls [LanguageModel.doGenerate], where [streamText] calls `doStream`. Sharing the loop and branching
 * only at the model call is what keeps one control flow to keep correct while still exercising both
 * halves of the specification: a provider whose two paths differ, and every middleware's `wrapGenerate`,
 * are unreachable from a runtime that only ever streams.
 *
 * ```kotlin
 * val model = AnthropicProvider(client, apiKey).languageModel("claude-opus-4-5")
 * val result = generateText(
 *     model = model,
 *     prompt = listOf(ModelMessage.User(listOf(UserPart.Text("What is 2 + 2?")))),
 * )
 * println(result.text)
 * ```
 *
 * @param model the model every round calls, unless [PrepareStep] swaps one in for a round.
 * @param prompt the conversation so far; validated by [standardizePrompt] before the first round.
 * @param options every other call setting; its own `prompt` is rebuilt each round from [prompt] plus the
 *   appended turns, so only the rest of it is read.
 * @param toolExecutor runs the client-side tool calls. Null means no call is ever executed.
 * @param stopWhen when to stop looping; the default is a single round.
 * @param repairToolCall one chance to fix a call that failed validation — see [ToolCallRepair].
 * @param approveTool answers provider approval requests. Absent, nothing is approved.
 * @param onToolError turns a thrown tool exception into the output the model sees.
 * @param prepareStep per-round overrides — model, tools, sampling; see [PrepareStep].
 * @param timeouts the run, step and tool limits; all unlimited by default. The stream-gap limits do not
 *   apply here — there are no chunks to time.
 * @param retry loop-level retries. [RetryPolicy.None] because every provider already retries inside its
 *   transport; a second default here would compound to up to nine attempts per call.
 * @param instructions becomes the leading system turn, via [standardizePrompt].
 * @param downloadAssets fetches URL attachments the model cannot fetch itself; null sends URLs as they are.
 * @param onEvent sees every [RunEvent] as it happens — step starts, tool results, approvals. The one
 *   observation hook: this entry point collapses the flow [streamText] exposes, and without it a
 *   non-streaming caller learns nothing about a run until it is over. One hook taking the same event
 *   union rather than an `onStepFinish`/`onToolCall` callback set, so observing here and observing the
 *   stream are the same code.
 * @param toolApprovals the client-side approval gate — policy and signing secret; see [ToolApprovals].
 * @param toolOrder the order tools are sent in: listed names first, the rest alphabetically after — see
 *   [StepPlan.toolOrder], which overrides this for one round. Null keeps the caller's own order.
 * @param allowSystemInMessages whether [prompt] may carry its own system turns — see [standardizePrompt].
 * @param include what each [Step] retains beyond its content — see [RunInclude].
 * @param callbacks the two hooks the event flow cannot carry — see [RunCallbacks].
 * @param logWarnings where each round's warnings go — see [WarningLogger]. Also told, once and up front,
 *   about a stream-gap timeout this entry point cannot honour.
 */
@Suppress("LongParameterList")
public suspend fun generateText(
    model: LanguageModel,
    prompt: Prompt,
    options: CallOptions = CallOptions(prompt = prompt),
    toolExecutor: ToolExecutor? = null,
    stopWhen: StopCondition = stepCountIs(1),
    repairToolCall: ToolCallRepair? = null,
    approveTool: ApprovalHandler? = null,
    onToolError: ToolErrorFormatter = DefaultToolErrorFormatter,
    prepareStep: PrepareStep? = null,
    timeouts: RunTimeouts = RunTimeouts(),
    // None at THIS layer is not "no retries": ProviderHttp retries retryable failures (2 by default)
    // inside every provider. A second default here would compound to up to nine attempts per call.
    retry: RetryPolicy = RetryPolicy.None,
    instructions: String? = null,
    downloadAssets: AssetDownloader? = null,
    onEvent: (suspend (RunEvent) -> Unit)? = null,
    toolApprovals: ToolApprovals? = null,
    toolOrder: List<String>? = null,
    allowSystemInMessages: Boolean = true,
    include: RunInclude = RunInclude(),
    callbacks: RunCallbacks = RunCallbacks(),
    logWarnings: WarningLogger = WarningLogger.Console,
): RunResult {
    // Said once, before the run, rather than silently ignored: a caller who set a chunk gap on a
    // non-streaming call believes they are protected against a quiet provider, and are not.
    logWarnings.logIfAny(timeouts.streamOnlyWarnings(), model.provider, model.modelId)
    return runLoop(
        LoopConfig(
            model, prompt, options, toolExecutor, stopWhen, repairToolCall, approveTool, onToolError,
            prepareStep, timeouts, retry, instructions, downloadAssets, toolApprovals,
            toolOrder, allowSystemInMessages, include, callbacks, logWarnings,
        ),
    ) { stepModel, callOptions, _ ->
        stepModel.doGenerate(callOptions).also { result ->
            // A response that ignored a forced tool choice is a failed call, not a text step.
            result.toolChoiceViolation(stepModel, callOptions)?.let { throw it }
        }
    }
        .onEach { event -> onEvent?.invoke(event) }
        // `last()` rather than `toList()`: buffering the run to reach its final event materialises every
        // text delta of every round before the caller sees anything.
        .filterIsInstance<RunEvent.Finish>()
        .last()
        .result
}

/** The two limits only a stream can honour, as the warnings the reference logs for them. */
private fun RunTimeouts.streamOnlyWarnings(): List<Warning> = listOfNotNull(
    firstChunkMs?.let {
        Warning.Unsupported(
            feature = "timeout.firstChunkMs",
            details = "The firstChunkMs timeout is only supported by streaming functions.",
        )
    },
    chunkMs?.let {
        Warning.Unsupported(
            feature = "timeout.chunkMs",
            details = "The chunkMs timeout is only supported by streaming functions.",
        )
    },
)

/** Everything both entry points configure, so the loop takes one parameter instead of eleven. */
private class LoopConfig(
    val model: LanguageModel,
    val prompt: Prompt,
    val options: CallOptions,
    val toolExecutor: ToolExecutor?,
    val stopWhen: StopCondition,
    val repairToolCall: ToolCallRepair?,
    val approveTool: ApprovalHandler?,
    val onToolError: ToolErrorFormatter,
    val prepareStep: PrepareStep?,
    val timeouts: RunTimeouts,
    val retry: RetryPolicy,
    val instructions: String?,
    val downloadAssets: AssetDownloader?,
    val toolApprovals: ToolApprovals?,
    val toolOrder: List<String>?,
    val allowSystemInMessages: Boolean,
    val include: RunInclude,
    val callbacks: RunCallbacks,
    val logWarnings: WarningLogger,
    val generateApprovalId: () -> String = IdGenerator("apr_")::next,
    val generateCallId: () -> String = IdGenerator("call_")::next,
)

/** One round's model call. The receiver is the run's collector, so a stream can forward its parts. */
private typealias ModelCall =
    suspend FlowCollector<RunEvent>.(LanguageModel, CallOptions, Int) -> GenerateResult

@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun runLoop(config: LoopConfig, call: ModelCall): Flow<RunEvent> = flow {
    val callId = config.generateCallId()
    val steps = mutableListOf<Step>()
    val appended = mutableListOf<ModelMessage>()
    val stillPending = mutableListOf<Content.ToolApprovalRequest>()

    // Validated once, at entry, rather than per round: every turn the loop appends is one it built
    // itself from a Step, so the shapes standardizePrompt rejects can only come from the caller. A
    // per-round re-check would spend the same work on the same messages to reach the same answer.
    // The one exception is a StepPlan rewrite, which is caller input again and re-validated below.
    val currentBase = standardizePrompt(
        config.prompt,
        config.instructions,
        config.options.tools.deferredToolNames(),
        config.allowSystemInMessages,
    ).let { validated ->
        config.downloadAssets?.let { validated.withDownloadedAssets(config.model.supportedUrls(), it) }
            ?: validated
    }
    val baseOptions = config.options.validated()
        .let { if (config.include.rawChunks) it.copy(includeRawChunks = true) else it }

    // After standardization and validation, so the hook sees what the first round will send — and
    // before the resume below, because a resumed tool is this run's work too.
    config.callbacks.onStart?.invoke(RunStart(callId, config.model, currentBase, baseOptions))

    // RESUME. A conversation can arrive holding decisions made after the run that asked for them had
    // already ended — a human confirmed a write, minutes later, in a process that no longer exists.
    // Those calls are executed BEFORE the first model call, because the model has nothing to add: it
    // asked, it was answered, and what it is owed is the result.
    val stored = applyStoredApprovals(
        prompt = currentBase,
        executor = config.toolExecutor,
        approvals = config.toolApprovals,
        approveTool = config.approveTool,
        onToolError = config.onToolError,
        timeouts = config.timeouts,
        emit = { event -> emit(event) },
    )
    if (stored.results.isNotEmpty()) appended += ModelMessage.Tool(stored.results)

    // Re-entering with a decision still genuinely out changes nothing: the model already asked, and
    // calling it again would replay an assistant turn whose tool call has no result — the 400 every
    // vendor answers that with. So the run reports the same pending requests and stops, which makes
    // re-entry idempotent rather than destructive.
    if (stored.stillPending.isNotEmpty()) {
        emit(
            RunEvent.Finish(
                RunResult(
                    callId = callId,
                    steps = emptyList(),
                    usage = Usage(),
                    finishReason = FinishReason(FinishReason.Unified.Other),
                    messages = appended,
                    pendingApprovals = stored.stillPending.map { it.first },
                ),
            ),
        )
        return@flow
    }

    val finishReason = try {
        withOptionalTimeout(config.timeouts.totalMs) {
            runRounds(config, call, callId, baseOptions, currentBase, steps, appended, stillPending)
        }
    } catch (e: CancellationException) {
        // The collector is gone, or a budget expired; either way nothing can be emitted any more, and
        // the hook is the only way the completed rounds reach whoever wanted to persist them. Under
        // NonCancellable so the persist finishes; rethrown so the run actually stops.
        config.callbacks.onAbort?.let { onAbort ->
            withContext(NonCancellable) { onAbort(RunAbort(callId, steps.toList(), e)) }
        }
        throw e
    }

    emit(
        RunEvent.Finish(
            RunResult(
                callId = callId,
                steps = steps,
                usage = steps.map { it.usage }.sum(),
                finishReason = finishReason,
                messages = appended,
                pendingApprovals = stillPending.toList(),
            ),
        ),
    )
}

/**
 * The rounds themselves, from the first model call to the last tool result.
 *
 * Split out of [runLoop] only so the abort handling around it is one `try` and every round keeps the one
 * control flow this file has always had. [base] arrives validated and downloaded; [appended] may already
 * hold a resumed tool turn. The receiver is the run's collector, so a round can emit.
 *
 * @return the last round's finish reason — how the run as a whole ended.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
private suspend fun FlowCollector<RunEvent>.runRounds(
    config: LoopConfig,
    call: ModelCall,
    callId: String,
    baseOptions: CallOptions,
    base: Prompt,
    steps: MutableList<Step>,
    appended: MutableList<ModelMessage>,
    stillPending: MutableList<Content.ToolApprovalRequest>,
): FinishReason {
    var currentBase = base
    var finishReason = FinishReason(FinishReason.Unified.Other)
    while (true) {
        val stepIndex = steps.size
        val plan = config.prepareStep?.prepare(StepContext(stepIndex, steps, currentBase + appended))

        // A rewrite replaces the conversation FROM THIS ROUND ON: the base becomes the plan's
        // messages (or the current conversation, when only the instructions change), the appended
        // turns fold into it, and later rounds append to the replacement. Downloaded assets are
        // not re-resolved — a rewrite derives from history whose URLs were already settled.
        if (plan != null && (plan.messages != null || plan.instructions != null)) {
            val replacement = plan.messages ?: (currentBase + appended)
            val body =
                if (plan.instructions == null) {
                    replacement
                } else {
                    replacement.dropWhile { it is ModelMessage.System }
                }
            currentBase = standardizePrompt(body, plan.instructions, config.options.tools.deferredToolNames())
            appended.clear()
        }

        val messages = currentBase + appended
        val stepModel = plan?.model ?: config.model
        val callOptions = plan.applyTo(baseOptions.copy(prompt = messages), config.toolOrder)
        emit(RunEvent.StepStart(stepIndex, callOptions))

        val result = config.include.shed(
            withOptionalTimeout(config.timeouts.stepMs) {
                withRetry(config.retry) { call(stepModel, callOptions, stepIndex) }
            },
        )

        // Validation rewrites the calls inside the content, so an invalid one is replayed FLAGGED
        // rather than replayed as the model wrote it — see Step.toAssistantMessage.
        val invalidCallErrors = mutableMapOf<String, Throwable>()
        val checked = result.content.associate { part ->
            if (part !is Content.ToolCall) {
                part to null
            } else {
                val outcome = validateToolCall(part, callOptions.tools, config.repairToolCall)
                outcome.error?.let {
                    invalidCallErrors[outcome.call.toolCallId] = it
                    emit(RunEvent.InvalidToolCall(outcome.call, it, stepIndex))
                }
                part to outcome.call
            }
        }
        val content = result.content.map { part -> checked[part] ?: part }

        val toolCalls = content.filterIsInstance<Content.ToolCall>()
            // A provider-executed call already ran on the vendor's servers; running it again here
            // would duplicate its effects.
            .filterNot { it.providerExecuted }

        // A provider that asks before running its own tool. Denial is an OUTCOME, not an error: the
        // model is told it was refused and can choose something else.
        val approvalResponses = mutableListOf<ToolPart.ApprovalResponse>()
        val denied = mutableSetOf<String>()
        for (request in content.filterIsInstance<Content.ToolApprovalRequest>()) {
            val target = toolCalls.firstOrNull { it.toolCallId == request.toolCallId }
            val approved = config.approveTool?.approve(request, target) ?: false
            if (!approved) denied += request.toolCallId
            approvalResponses += ToolPart.ApprovalResponse(request.approvalId, approved)
            emit(RunEvent.Approval(request, approved, stepIndex))
        }

        val toolResults = mutableListOf<ToolPart.Result>()

        // A denial is reported whether or not anything can execute. Gating this on an executor is how
        // a refused tool produces no result at all and the model is never told it was refused.
        for (denial in toolCalls.filter { it.toolCallId in denied }) {
            val part = ToolPart.Result(
                toolCallId = denial.toolCallId,
                toolName = denial.toolName,
                output = ToolOutput.ExecutionDenied("Execution of '${denial.toolName}' was not approved."),
            )
            toolResults += part
            emit(RunEvent.ToolResult(part, stepIndex))
        }

        // An invalid call is reported for the same reason a denied one is: the model asked for
        // something and is owed an answer. Silence leaves it to infer a failure from a missing
        // result, and the usual inference is to issue the identical broken call again.
        for (bad in toolCalls.filter { it.invalid && it.toolCallId !in denied }) {
            val part = ToolPart.Result(
                toolCallId = bad.toolCallId,
                toolName = bad.toolName,
                output = ToolOutput.ErrorText(
                    invalidCallErrors[bad.toolCallId]?.let { com.sabreware.aide.aisdk.getErrorMessage(it) }
                        ?: "The call to '${bad.toolName}' could not be validated.",
                ),
            )
            toolResults += part
            emit(RunEvent.ToolResult(part, stepIndex))
        }

        // The CLIENT-side gate, resolved before anything runs. The provider flow above answers
        // questions the VENDOR raised; this raises the runtime's own — from the caller's policy
        // first, the tool's declaration second — and both share one response vocabulary, so a
        // transcript reads the same whoever asked.
        val toolContext = ToolCallContext(messages = callOptions.prompt, stepIndex = stepIndex)
        val gateRecords = mutableListOf<Content.ToolApprovalRequest>()
        val pendingApprovals = mutableListOf<Content.ToolApprovalRequest>()
        val gateAllowed = mutableSetOf<String>()
        for (toolCall in toolCalls.filter { it.toolCallId !in denied && !it.invalid }) {
            val status = resolveToolApproval(toolCall, callOptions.tools, config.toolApprovals, toolContext)
            if (status is ToolApprovalStatus.NotApplicable) {
                gateAllowed += toolCall.toolCallId
                continue
            }
            val approvalId = config.generateApprovalId()
            val signature = config.toolApprovals?.secret?.let { signToolApproval(it, approvalId, toolCall) }
            fun request(reason: String?, automatic: Boolean?) = Content.ToolApprovalRequest(
                approvalId = approvalId,
                toolCallId = toolCall.toolCallId,
                reason = reason,
                isAutomatic = automatic,
                signature = signature,
                // Minted HERE, not by a vendor. The tag is what keeps this exchange off the wire:
                // a provider handed a locally generated approval id would name a pending item its
                // API never created. See RUNTIME_APPROVAL_NAMESPACE.
                providerMetadata = RuntimeApprovalTag,
            )
            suspend fun record(request: Content.ToolApprovalRequest, approved: Boolean, reason: String?) {
                gateRecords += request
                approvalResponses += ToolPart.ApprovalResponse(
                    approvalId = request.approvalId,
                    approved = approved,
                    reason = reason,
                    providerOptions = RuntimeApprovalTag,
                )
                emit(RunEvent.Approval(request, approved, stepIndex))
                if (approved) {
                    gateAllowed += toolCall.toolCallId
                } else {
                    val part = ToolPart.Result(
                        toolCallId = toolCall.toolCallId,
                        toolName = toolCall.toolName,
                        output = ToolOutput.ExecutionDenied(
                            reason ?: "Execution of '${toolCall.toolName}' was not approved.",
                        ),
                    )
                    toolResults += part
                    emit(RunEvent.ToolResult(part, stepIndex))
                }
            }
            when (status) {
                // The pair is recorded even though policy decided alone — see
                // Content.ToolApprovalRequest.isAutomatic: the transcript shows the gate ran.
                is ToolApprovalStatus.Approved -> record(request(status.reason, automatic = true), true, status.reason)
                is ToolApprovalStatus.Denied -> record(request(status.reason, automatic = true), false, status.reason)
                is ToolApprovalStatus.UserApproval -> {
                    val ask = request(status.reason, automatic = null)
                    val handler = config.approveTool
                    if (handler != null) {
                        record(ask, handler.approve(ask, toolCall), null)
                    } else {
                        // Nothing in-process can answer, so the call stays unexecuted and the run
                        // ends after this round with the request on the step — the cross-call
                        // resume path answers it on a later invocation.
                        pendingApprovals += ask
                        stillPending += ask
                        emit(RunEvent.ApprovalPending(ask, toolCall, stepIndex))
                    }
                }
                is ToolApprovalStatus.NotApplicable -> Unit
            }
        }

        val executable = toolCalls.filter { it.toolCallId in gateAllowed }
        // A run truncated at `length` mid-`tool_use` left a PARTIAL argument string behind. It may
        // still parse; it is still not what the model meant to send.
        if (config.toolExecutor != null && result.finishReason.allowsToolExecution()) {
            // Announced before any of them starts, so a surface can show every pending call at once
            // rather than one appearing each time an earlier one finishes.
            for (toolCall in executable) emit(RunEvent.ToolStart(toolCall, stepIndex))
            // Started together, awaited in call order: two independent three-second tools take three
            // seconds, and each result still pairs with its call by position.
            val running = coroutineScope {
                executable.map { toolCall ->
                    async {
                        runToolCatching(
                            config.toolExecutor,
                            toolCall,
                            toolContext,
                            config.onToolError,
                            config.timeouts.toolTimeoutMs(toolCall.toolName),
                        )
                    }
                }
            }
            for ((index, job) in running.withIndex()) {
                val outcome = job.await()
                val toolCall = executable[index]
                // Emitted here rather than inside the `async`: a flow may only be collected from the
                // coroutine that collects it, and emitting from a child violates that outright.
                outcome.error?.let { emit(RunEvent.ToolError(toolCall, it, stepIndex)) }
                val part = ToolPart.Result(
                    toolCallId = toolCall.toolCallId,
                    toolName = toolCall.toolName,
                    output = outcome.output,
                )
                toolResults += part
                emit(RunEvent.ToolResult(part, stepIndex, outcome.durationMs))
            }
        }

        val step = Step(
            // The gate's request/response pairs and anything still pending ride on the step, after
            // the calls they gate — a transcript that omits the asking shows tools running with no
            // visible reason some did not.
            content = content + gateRecords + pendingApprovals,
            finishReason = result.finishReason,
            usage = result.usage,
            warnings = result.warnings,
            toolResults = toolResults,
            approvalResponses = approvalResponses,
            providerMetadata = result.providerMetadata,
            request = result.request,
            response = result.response,
            callId = callId,
            stepNumber = stepIndex,
        )
        steps += step
        finishReason = step.finishReason
        emit(RunEvent.StepFinish(step, stepIndex))
        config.logWarnings.logIfAny(step.warnings, stepModel.provider, stepModel.modelId)

        val assistantTurn = step.toAssistantMessage()
        if (assistantTurn.content.isNotEmpty()) appended += assistantTurn
        step.toToolMessage()?.let { appended += it }

        // A round holding calls nothing may run yet cannot loop: the model's turn is unanswered
        // until someone decides, and that someone is outside this process.
        if (pendingApprovals.isNotEmpty()) break

        val moreToDo = toolResults.isNotEmpty()
        if (!moreToDo || config.stopWhen.shouldStop(steps)) break
    }
    return finishReason
}

/**
 * Whether a round that ended this way may have its tool calls run.
 *
 * `stop` and `tool-calls` are the two reasons that mean the model finished saying what it wanted. Every
 * other reason — a length cap, a content filter, an error — means generation was cut off, and a
 * `tool_use` block cut off mid-arguments is not an instruction anyone agreed to carry out.
 */
private fun FinishReason.allowsToolExecution(): Boolean =
    unified == FinishReason.Unified.Stop || unified == FinishReason.Unified.ToolCalls

/**
 * One tool's outcome: what the model will see, the exception behind it if there was one, and how long
 * it took.
 *
 * The duration is taken here, around the execution, because the tools of a round run concurrently and
 * are awaited in call order — timing them from the outside charges each one every earlier tool's wait.
 */
private class ToolOutcome(val output: ToolOutput, val error: Throwable?, val durationMs: Long)

/**
 * A tool that throws becomes an error result rather than ending the run.
 *
 * The model can read an error and try something else; an exception thrown out of the loop takes the whole
 * conversation with it. A tool that ran past [timeoutMs] is the same kind of event and gets the same
 * treatment — which is why the timeout is caught before the blanket cancellation clause it would
 * otherwise fall into. Cancellation of the RUN stays exempt: a cancelled run must actually stop.
 */
private suspend fun runToolCatching(
    executor: ToolExecutor,
    call: Content.ToolCall,
    context: ToolCallContext,
    onToolError: ToolErrorFormatter,
    timeoutMs: Long?,
): ToolOutcome {
    val started = TimeSource.Monotonic.markNow()
    return try {
        ToolOutcome(withOptionalTimeout(timeoutMs) { executor.execute(call, context) }, null, started.elapsed())
    } catch (e: TimeoutCancellationException) {
        ToolOutcome(onToolError.format(call, e), e, started.elapsed())
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
        ToolOutcome(onToolError.format(call, e), e, started.elapsed())
    }
}

private fun TimeSource.Monotonic.ValueTimeMark.elapsed(): Long = elapsedNow().inWholeMilliseconds

/** Just the text deltas, for a caller that only wants to render the answer as it arrives. */
public fun Flow<RunEvent>.textDeltas(): Flow<String> = flow {
    collect { event ->
        val part = (event as? RunEvent.Part)?.part
        if (part is StreamPart.TextDelta) emit(part.delta)
    }
}

/** Just the reasoning deltas. */
public fun Flow<RunEvent>.reasoningDeltas(): Flow<String> = flow {
    collect { event ->
        val part = (event as? RunEvent.Part)?.part
        if (part is StreamPart.ReasoningDelta) emit(part.delta)
    }
}

/** Usage across every round, or zero-state if the run never finished. */
public fun List<RunEvent>.totalUsage(): Usage =
    filterIsInstance<RunEvent.Finish>().lastOrNull()?.result?.usage ?: Usage()
