package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ToolPart

/**
 * How much reasoning [pruneMessages] removes from assistant turns.
 *
 * Reasoning is the safest thing to shed and the first that should go: vendors require it replayed only
 * while the tool round it belongs to is still open, and a signed thinking block from three turns ago is
 * dead weight the model re-reads on every call.
 */
public enum class PruneReasoning {

    /** Remove nothing. */
    None,

    /** Remove every reasoning part from every assistant turn. */
    All,

    /**
     * Remove reasoning from every assistant turn except the LAST ONE.
     *
     * The newest assistant turn is the one whose tool round may still be open — Anthropic rejects a
     * `tool_result` whose assistant turn lost its thinking block — so the newest reasoning must survive
     * any prune that runs mid-round.
     *
     * The exemption follows the last ASSISTANT turn, not the last message by position. Mid-run a
     * conversation ends with the TOOL turn that answers the round, so a positional rule exempts a
     * message holding no reasoning at all and prunes the signed block sitting right behind it — which
     * is the exact 400 this whole library exists to prevent. (The reference uses the positional rule
     * and does not hit it, because it prunes finished conversations, which end with the answer.)
     */
    BeforeLastMessage,
}

/**
 * One tool-content pruning rule for [pruneMessages].
 *
 * A list of these composes: each rule names how many trailing messages are exempt and, optionally,
 * which tools it applies to — so "drop every old `search` exchange but keep the last two messages'"
 * and "drop every `scratchpad` call everywhere" are two entries, not one contorted predicate.
 */
public data class PruneToolCalls(
    /**
     * How many trailing messages keep their tool content. Null exempts nothing — every matching call,
     * result, and approval exchange goes, wherever it appears.
     *
     * Exemption is by CALL, not by position: a call whose id is visible in the kept window keeps its
     * every part, including the parts of the exchange that sit outside the window — dropping half of a
     * call/result pair produces the unanswered-call shape every vendor rejects.
     */
    val keepLastMessages: Int? = null,
    /** Only calls to these tools are pruned. Null prunes calls to every tool. */
    val tools: List<String>? = null,
)

/**
 * Sheds prompt weight a long run no longer needs: old reasoning, settled tool exchanges, and the empty
 * turns pruning leaves behind.
 *
 * This is the compaction primitive — [StepPlan.messages] is how a [PrepareStep] applies its output
 * mid-run, so a long-running agent can trim its own context between rounds instead of growing until the
 * vendor's window ends the conversation for it.
 *
 * Pruning is id-consistent by construction: a tool call, its result, and the approval exchange that
 * gated it are removed or kept TOGETHER — dropping half of a pair produces the unanswered-call shape
 * every vendor rejects. The pairing is resolved across the WHOLE conversation rather than per message,
 * because a request lives in an assistant turn and its answer in a later tool turn: matching them
 * within one message finds neither half, keeps responses whose requests were pruned, and hands the
 * vendor an orphan. (The reference shipped exactly that bug and fixed it the same way.)
 *
 * @param messages the conversation to prune. Returned untouched when nothing matches.
 * @param reasoning how much reasoning to remove — see [PruneReasoning].
 * @param toolCalls the tool-content rules, applied in order — see [PruneToolCalls]. Empty prunes none.
 * @param keepEmptyMessages whether a message emptied by pruning survives. The default removes it,
 *   because an empty message is a 400 at every vendor; keeping is for a caller that post-processes.
 */
public fun pruneMessages(
    messages: Prompt,
    reasoning: PruneReasoning = PruneReasoning.None,
    toolCalls: List<PruneToolCalls> = emptyList(),
    keepEmptyMessages: Boolean = false,
): Prompt {
    var result = messages.pruneReasoning(reasoning)
    for (rule in toolCalls) result = result.pruneToolContent(rule)
    return if (keepEmptyMessages) result else result.filterNot(ModelMessage::isEmptied)
}

private fun Prompt.pruneReasoning(mode: PruneReasoning): Prompt {
    if (mode == PruneReasoning.None) return this
    // The last ASSISTANT turn, found by walking back past the tool turn a mid-run prompt ends with.
    val newestAssistant = indexOfLast { it is ModelMessage.Assistant }
    return mapIndexed { index, message ->
        val exempt = mode == PruneReasoning.BeforeLastMessage && index == newestAssistant
        if (message !is ModelMessage.Assistant || exempt) {
            message
        } else {
            message.copy(content = message.content.filterNot { it is AssistantPart.Reasoning })
        }
    }
}

private fun Prompt.pruneToolContent(rule: PruneToolCalls): Prompt {
    val keep = rule.keepLastMessages
    val keptCallIds = mutableSetOf<String>()
    val keptApprovalIds = mutableSetOf<String>()
    if (keep != null) {
        for (message in takeLast(keep)) {
            keptCallIds += message.toolCallIds()
            keptApprovalIds += message.approvalIds()
        }
    }

    // Resolved across the whole conversation: a request lives in an assistant turn and its answer in a
    // later tool turn, so matching them within one message finds neither half. (The reference shipped
    // the per-message version, orphaned approval responses with it, and fixed it the same way.)
    val approvalCalls = mutableMapOf<String, String>()
    val callNames = mutableMapOf<String, String>()
    for (message in this) {
        when (message) {
            is ModelMessage.Assistant -> message.content.forEach { part ->
                when (part) {
                    is AssistantPart.ApprovalRequest -> approvalCalls[part.approvalId] = part.toolCallId
                    is AssistantPart.ToolCall -> callNames[part.toolCallId] = part.toolName
                    is AssistantPart.ToolResult -> callNames[part.toolCallId] = part.toolName
                    else -> Unit
                }
            }
            is ModelMessage.Tool -> message.content.filterIsInstance<ToolPart.Result>()
                .forEach { callNames[it.toolCallId] = it.toolName }
            else -> Unit
        }
    }

    return mapIndexed { index, message ->
        val inKeptWindow = keep != null && index >= size - keep
        if (inKeptWindow) return@mapIndexed message
        when (message) {
            is ModelMessage.Assistant -> message.copy(
                content = message.content.filter { part ->
                    when (part) {
                        is AssistantPart.ToolCall ->
                            keptExchange(part.toolCallId, part.toolName, keptCallIds, rule)
                        is AssistantPart.ToolResult ->
                            keptExchange(part.toolCallId, part.toolName, keptCallIds, rule)
                        // Goes with the call it gates. Keeping it while its call is pruned leaves an
                        // approval about nothing, which every reader of the pair then has to tolerate.
                        is AssistantPart.ApprovalRequest ->
                            keptExchange(part.toolCallId, callNames[part.toolCallId], keptCallIds, rule)
                        else -> true
                    }
                },
            )
            is ModelMessage.Tool -> message.copy(
                content = message.content.filter { part ->
                    when (part) {
                        is ToolPart.Result ->
                            keptExchange(part.toolCallId, part.toolName, keptCallIds, rule)
                        // Resolved through its request to the call, and from there to the tool name —
                        // globally, because the response and its request live in different messages.
                        is ToolPart.ApprovalResponse -> {
                            val callId = approvalCalls[part.approvalId]
                            when {
                                part.approvalId in keptApprovalIds -> true
                                callId == null -> true
                                else -> keptExchange(callId, callNames[callId], keptCallIds, rule)
                            }
                        }
                    }
                },
            )
            else -> message
        }
    }
}

/**
 * Kept if its exchange is visible in the kept window, or the rule names tools and this is not one.
 *
 * A null [toolName] — a part whose call cannot be resolved — is KEPT: pruning what cannot be attributed
 * is how half an exchange goes missing.
 */
private fun keptExchange(
    id: String,
    toolName: String?,
    keptIds: Set<String>,
    rule: PruneToolCalls,
): Boolean = id in keptIds || (rule.tools != null && (toolName == null || toolName !in rule.tools))

private fun ModelMessage.toolCallIds(): List<String> = when (this) {
    is ModelMessage.Assistant -> content.mapNotNull {
        when (it) {
            is AssistantPart.ToolCall -> it.toolCallId
            is AssistantPart.ToolResult -> it.toolCallId
            else -> null
        }
    }
    is ModelMessage.Tool -> content.filterIsInstance<ToolPart.Result>().map { it.toolCallId }
    else -> emptyList()
}

private fun ModelMessage.approvalIds(): List<String> = when (this) {
    is ModelMessage.Tool -> content.filterIsInstance<ToolPart.ApprovalResponse>().map { it.approvalId }
    is ModelMessage.Assistant ->
        content.filterIsInstance<AssistantPart.ApprovalRequest>().map { it.approvalId }
    else -> emptyList()
}

/** Whether pruning left this message with nothing to say. A system turn's content is its text. */
private fun ModelMessage.isEmptied(): Boolean = when (this) {
    is ModelMessage.Assistant -> content.isEmpty()
    is ModelMessage.Tool -> content.isEmpty()
    is ModelMessage.User -> content.isEmpty()
    is ModelMessage.System -> false
}
