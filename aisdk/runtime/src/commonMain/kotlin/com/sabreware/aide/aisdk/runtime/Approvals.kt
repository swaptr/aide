package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.RUNTIME_APPROVAL_NAMESPACE
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.util.parseJsonElementOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Buffer
import okio.ByteString.Companion.toByteString

/**
 * What the approval gate decided about one tool call, BEFORE anything runs.
 *
 * Distinct from [ApprovalHandler], which answers a question something else raised: this is the raising.
 * A status of [UserApproval] turns into a [Content.ToolApprovalRequest] the handler — or, absent one,
 * the caller on a later invocation — answers; [Approved] and [Denied] are policy answering its own
 * question, recorded as an automatic request/response pair so the transcript still shows the gate was
 * consulted.
 */
public sealed interface ToolApprovalStatus {

    /** The tool needs no approval. The default, and the whole gate's cost for tools that opt out. */
    public data object NotApplicable : ToolApprovalStatus

    /** Policy says yes — run it, and record that policy said so. */
    public data class Approved(val reason: String? = null) : ToolApprovalStatus

    /** Policy says no — the model is told, with the reason, and can try something else. */
    public data class Denied(val reason: String? = null) : ToolApprovalStatus

    /** A human (or the host's [ApprovalHandler]) has to decide. [reason] is shown to the approver. */
    public data class UserApproval(val reason: String? = null) : ToolApprovalStatus
}

/**
 * Decides, per call, whether a tool may run — the caller-side half of the approval flow.
 *
 * Takes precedence over the tool's own [Tool.Function.needsApproval], because the caller composing the
 * run knows things the tool's author did not: which surface this is, whose data is in scope, what the
 * user already agreed to. Returning null defers to the tool's declaration.
 */
public fun interface ToolApprovalPolicy {

    /** @return the decision for [call], or null to fall through to the tool's own declaration. */
    public suspend fun statusFor(call: Content.ToolCall, context: ToolCallContext): ToolApprovalStatus?
}

/** A policy from a per-tool table — the common case, spelled without a `when` at every call site. */
public fun toolApprovalPolicy(byName: Map<String, ToolApprovalStatus>): ToolApprovalPolicy =
    ToolApprovalPolicy { call, _ -> byName[call.toolName] }

/**
 * The approval configuration of one run.
 *
 * @param policy the caller's per-call decision — see [ToolApprovalPolicy]. Null defers entirely to
 *   each tool's [Tool.Function.needsApproval].
 * @param secret when set, every approval request the run raises carries an HMAC-SHA256 signature
 *   binding it to its call's id, name and input — see [Content.ToolApprovalRequest.signature]. Set it
 *   when approvals cross a persistence boundary; leave it null for in-process handlers, which answer
 *   against the live object.
 */
public data class ToolApprovals(
    val policy: ToolApprovalPolicy? = null,
    val secret: ByteArray? = null,
) {

    override fun equals(other: Any?): Boolean = this === other ||
        (other is ToolApprovals && policy == other.policy && secret.contentEqualsNullable(other.secret))

    override fun hashCode(): Int = 31 * (policy?.hashCode() ?: 0) + (secret?.contentHashCode() ?: 0)
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null || other == null) this === other else contentEquals(other)

/**
 * Resolves the gate for one call: the caller's policy first, the tool's own declaration second,
 * nothing by default. The precedence is the reference's, for the reference's reason — the caller
 * composing the run outranks the tool's author.
 */
internal suspend fun resolveToolApproval(
    call: Content.ToolCall,
    tools: List<Tool>?,
    approvals: ToolApprovals?,
    context: ToolCallContext,
): ToolApprovalStatus {
    approvals?.policy?.statusFor(call, context)?.let { return it }
    val declared = tools.orEmpty()
        .filterIsInstance<Tool.Function>()
        .firstOrNull { it.name == call.toolName }
        ?.needsApproval == true
    return if (declared) ToolApprovalStatus.UserApproval() else ToolApprovalStatus.NotApplicable
}

/**
 * The marker every runtime-minted approval request and response carries.
 *
 * Empty payload on purpose: the KEY is the whole signal — "this exchange belongs to the runtime and its
 * gate, not to a vendor's protocol" — and a provider only has to ask whether the key is present.
 */
internal val RuntimeApprovalTag: ProviderMetadata =
    mapOf(RUNTIME_APPROVAL_NAMESPACE to JsonObject(emptyMap()))

// ---------------------------------------------------------------------------------------------------
// Signing. The approval and the call it authorizes travel through storage the runtime does not
// control; the signature is what stops a persisted "yes" from being re-attached to a different call.
// ---------------------------------------------------------------------------------------------------

private const val SIGNATURE_DOMAIN = "ai-sdk-tool-approval-v1"

/** HMAC-SHA256 over the domain-separated (approvalId, toolCallId, toolName, input-digest) tuple. */
internal fun signToolApproval(secret: ByteArray, approvalId: String, call: Content.ToolCall): String {
    val digest = canonicalDigest(call.input)
    // A JSON array keeps the field boundaries unambiguous whatever the fields contain — the
    // newline-joined form this replaces upstream was forgeable across a field containing '\n'.
    val payload = JsonArray(
        listOf(
            JsonPrimitive(SIGNATURE_DOMAIN),
            JsonPrimitive(approvalId),
            JsonPrimitive(call.toolCallId),
            JsonPrimitive(call.toolName),
            JsonPrimitive(digest),
        ),
    ).toString()
    val mac = payload.encodeToByteArray().toByteString().hmacSha256(secret.toByteString())
    return mac.base64Url().trimEnd('=')
}

/** Whether [signature] is the one [signToolApproval] would produce for this call. */
internal fun verifyToolApproval(
    secret: ByteArray,
    approvalId: String,
    call: Content.ToolCall,
    signature: String?,
): Boolean = signature != null && signature == signToolApproval(secret, approvalId, call)

/** SHA-256 of the canonical JSON of [input], base64url — invalid JSON hashes as the raw string. */
private fun canonicalDigest(input: String): String {
    val canonical = parseJsonElementOrNull(input)
        ?.let { canonicalJson(it) }
        ?: input
    val buffer = Buffer().writeUtf8(canonical)
    return buffer.readByteString().sha256().base64Url().trimEnd('=')
}

/**
 * Deterministic JSON: object keys sorted, so two structurally equal inputs digest identically
 * whatever order a parser or a copy happened to put their keys in.
 */
internal fun canonicalJson(element: JsonElement): String = when (element) {
    is JsonNull -> "null"
    is JsonPrimitive -> element.toString()
    is JsonArray -> element.joinToString(",", "[", "]") { canonicalJson(it) }
    is JsonObject -> element.entries
        .sortedBy { it.key }
        .joinToString(",", "{", "}") { (key, value) ->
            "${JsonPrimitive(key)}:${canonicalJson(value)}"
        }
}

// ---------------------------------------------------------------------------------------------------
// Resume. The half of the approval flow that spans processes: a decision made against a stored
// conversation, applied by a runtime that never saw the round which asked.
// ---------------------------------------------------------------------------------------------------

/**
 * Every approval exchange a conversation carries, indexed once.
 *
 * One walk, three questions. The alternative — a walker per question — was three passes rebuilding the
 * same four maps to ask slightly different things of them, which is how the answers drift apart.
 *
 * All of it is resolved ACROSS messages, never within one: a request lives in an assistant turn and its
 * answer in a later tool turn, so per-message matching finds neither half.
 */
internal class ApprovalIndex(private val prompt: Prompt) {

    private val requests = mutableListOf<AssistantPart.ApprovalRequest>()
    private val calls = mutableMapOf<String, Content.ToolCall>()
    private val responses = mutableListOf<ToolPart.ApprovalResponse>()
    private val answered = mutableSetOf<String>()
    private val settled = mutableSetOf<String>()

    /** The index in [prompt] of the turn each request was raised in — how "the caller moved on" is told. */
    private val requestPositions = mutableMapOf<String, Int>()
    private val lastUserTurn: Int

    init {
        var lastUser = -1
        prompt.forEachIndexed { index, message ->
            when (message) {
                is ModelMessage.User -> lastUser = index
                is ModelMessage.Assistant -> message.content.forEach { part ->
                    when (part) {
                        is AssistantPart.ApprovalRequest -> {
                            requests += part
                            requestPositions[part.approvalId] = index
                        }
                        is AssistantPart.ToolCall -> calls[part.toolCallId] = Content.ToolCall(
                            toolCallId = part.toolCallId,
                            toolName = part.toolName,
                            input = part.input,
                            providerExecuted = part.providerExecuted,
                            providerMetadata = part.providerOptions,
                        )
                        else -> Unit
                    }
                }
                is ModelMessage.Tool -> message.content.forEach { part ->
                    when (part) {
                        is ToolPart.ApprovalResponse -> {
                            responses += part
                            answered += part.approvalId
                        }
                        is ToolPart.Result -> settled += part.toolCallId
                    }
                }
                is ModelMessage.System -> Unit
            }
        }
        lastUserTurn = lastUser
    }

    /** True when this conversation carries no approval exchange at all — the overwhelmingly common case. */
    val isEmpty: Boolean get() = requests.isEmpty()

    /**
     * Answers whose calls nothing has run yet, paired with the call each settles.
     *
     * A call that already has a result is skipped whatever its answer says: re-running it would repeat
     * an effect the user has already seen, which is the one failure a confirm-before-writing flow must
     * not have.
     */
    fun answeredAndUnrun(): List<CollectedApproval> = responses.mapNotNull { response ->
        val request = requests.firstOrNull { it.approvalId == response.approvalId } ?: return@mapNotNull null
        val call = calls[request.toolCallId] ?: return@mapNotNull null
        if (request.toolCallId in settled) null else CollectedApproval(request, response, call)
    }

    /**
     * Requests nobody has answered and whose calls nothing has run.
     *
     * [PendingApproval.abandoned] marks the ones the conversation has moved past: a user turn arrived
     * after the question was asked, which means the human answered by doing something else. Reporting
     * those as still-pending forever is how a run dead-ends — the new message would never be processed.
     */
    fun unanswered(): List<PendingApproval> = requests
        .filterNot { it.approvalId in answered || it.toolCallId in settled }
        .mapNotNull { request ->
            calls[request.toolCallId]?.let { call ->
                PendingApproval(
                    request = Content.ToolApprovalRequest(
                        approvalId = request.approvalId,
                        toolCallId = request.toolCallId,
                        reason = request.reason,
                        isAutomatic = request.isAutomatic,
                        signature = request.signature,
                        providerMetadata = request.providerOptions,
                    ),
                    call = call,
                    abandoned = lastUserTurn > (requestPositions[request.approvalId] ?: Int.MAX_VALUE),
                )
            }
        }
}

/** One approval decision found in a stored conversation, paired with the call it settles. */
internal class CollectedApproval(
    val request: AssistantPart.ApprovalRequest,
    val response: ToolPart.ApprovalResponse,
    val call: Content.ToolCall,
)

/** One question a stored conversation is still carrying. */
internal class PendingApproval(
    val request: Content.ToolApprovalRequest,
    val call: Content.ToolCall,
    /** The conversation moved on without answering — see [ApprovalIndex.unanswered]. */
    val abandoned: Boolean,
)

/**
 * The step index resume reports its events under.
 *
 * Negative on purpose. This work happens BEFORE the run's first round, so numbering it `0` would make
 * its tool results indistinguishable from the first real round's — a consumer grouping events by step
 * would file work from a previous run's question under a round that has not happened yet.
 */
internal const val RESUME_STEP_INDEX: Int = -1

/** What applying a conversation's stored approvals produced. */
internal class StoredApprovalOutcome(
    /** The tool turn to append before the first model call. Empty when nothing was settled. */
    val results: List<ToolPart>,
    /** Questions still out. Non-empty means the run must stop again rather than call the model. */
    val stillPending: List<Pair<Content.ToolApprovalRequest, Content.ToolCall>>,
)

/**
 * Applies the decisions a stored conversation arrived with, and reports what is still outstanding.
 *
 * Runs before the first model call: the model asked and was answered, so what it is owed next is the
 * result, not another turn of its own. A denial produces a result too — silence would leave the model to
 * infer a failure from a missing answer, and the usual inference is to ask again.
 *
 * Four outcomes per exchange, and the last two are what keep a run from dead-ending:
 *
 * - **Answered yes** — the call runs now.
 * - **Answered no** — an [ToolOutput.ExecutionDenied] result carrying the reason.
 * - **Unanswered, but this run has an [ApprovalHandler]** — the handler is asked. A host that stored a
 *   pending question and came back with a handler wired should be consulted, not handed its own
 *   question back forever.
 * - **Unanswered, and the conversation moved on** — a user turn arrived after the question, so the
 *   human answered by doing something else. The call is denied and the run proceeds to the new message.
 *
 * Anything left after that is genuinely still out, and the run stops with it.
 *
 * When the run carries a signing secret, a request whose signature does not verify against its call is
 * DENIED rather than executed. That is the point of signing: between the ask and the answer the
 * conversation sat in storage this library does not control, and a yes that has been moved onto a
 * different call is exactly what the signature detects.
 */
@Suppress("LongParameterList")
internal suspend fun applyStoredApprovals(
    prompt: Prompt,
    executor: ToolExecutor?,
    approvals: ToolApprovals?,
    approveTool: ApprovalHandler?,
    onToolError: ToolErrorFormatter,
    timeouts: RunTimeouts,
    emit: suspend (RunEvent) -> Unit,
): StoredApprovalOutcome {
    val index = ApprovalIndex(prompt)
    if (index.isEmpty) return StoredApprovalOutcome(emptyList(), emptyList())

    val context = ToolCallContext(messages = prompt, stepIndex = RESUME_STEP_INDEX)
    val results = mutableListOf<ToolPart>()
    val stillPending = mutableListOf<Pair<Content.ToolApprovalRequest, Content.ToolCall>>()

    suspend fun settle(request: Content.ToolApprovalRequest, call: Content.ToolCall, approved: Boolean, reason: String?) {
        emit(RunEvent.Approval(request, approved, stepIndex = RESUME_STEP_INDEX))
        if (approved && executor != null) {
            emit(RunEvent.ToolStart(call, stepIndex = RESUME_STEP_INDEX))
            val outcome = runResumedTool(executor, call, context, onToolError, timeouts.toolTimeoutMs(call.toolName))
            outcome.error?.let { emit(RunEvent.ToolError(call, it, stepIndex = RESUME_STEP_INDEX)) }
            val part = ToolPart.Result(call.toolCallId, call.toolName, outcome.output)
            results += part
            emit(RunEvent.ToolResult(part, stepIndex = RESUME_STEP_INDEX, durationMs = outcome.durationMs))
        } else {
            val part = ToolPart.Result(
                toolCallId = call.toolCallId,
                toolName = call.toolName,
                output = ToolOutput.ExecutionDenied(
                    reason ?: if (approved) {
                        "No executor is available to run '${call.toolName}'."
                    } else {
                        "Execution of '${call.toolName}' was not approved."
                    },
                ),
            )
            results += part
            emit(RunEvent.ToolResult(part, stepIndex = RESUME_STEP_INDEX))
        }
    }

    for (approval in index.answeredAndUnrun()) {
        val forged = approvals?.secret?.let {
            !verifyToolApproval(it, approval.request.approvalId, approval.call, approval.request.signature)
        } ?: false
        settle(
            request = Content.ToolApprovalRequest(
                approvalId = approval.request.approvalId,
                toolCallId = approval.request.toolCallId,
                reason = approval.request.reason,
                isAutomatic = approval.request.isAutomatic,
                signature = approval.request.signature,
                providerMetadata = approval.request.providerOptions,
            ),
            call = approval.call,
            approved = approval.response.approved && !forged,
            reason = when {
                forged -> "The approval for '${approval.call.toolName}' does not match the call it names."
                !approval.response.approved -> approval.response.reason
                else -> null
            },
        )
    }

    for (pending in index.unanswered()) {
        when {
            pending.abandoned -> settle(
                request = pending.request,
                call = pending.call,
                approved = false,
                reason = "The conversation moved on before '${pending.call.toolName}' was approved.",
            )
            approveTool != null ->
                settle(pending.request, pending.call, approveTool.approve(pending.request, pending.call), null)
            else -> {
                stillPending += pending.request to pending.call
                emit(RunEvent.ApprovalPending(pending.request, pending.call, stepIndex = RESUME_STEP_INDEX))
            }
        }
    }

    return StoredApprovalOutcome(results, stillPending)
}

/** A resumed tool gets the same treatment an in-round one does: a throw becomes a result. */
private suspend fun runResumedTool(
    executor: ToolExecutor,
    call: Content.ToolCall,
    context: ToolCallContext,
    onToolError: ToolErrorFormatter,
    timeoutMs: Long?,
): ResumedOutcome {
    val started = TimeSource.Monotonic.markNow()
    return try {
        ResumedOutcome(
            withOptionalTimeout(timeoutMs) { executor.execute(call, context) },
            null,
            started.elapsedNow().inWholeMilliseconds,
        )
    } catch (e: TimeoutCancellationException) {
        ResumedOutcome(onToolError.format(call, e), e, started.elapsedNow().inWholeMilliseconds)
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
        ResumedOutcome(onToolError.format(call, e), e, started.elapsedNow().inWholeMilliseconds)
    }
}

private class ResumedOutcome(val output: ToolOutput, val error: Throwable?, val durationMs: Long)
