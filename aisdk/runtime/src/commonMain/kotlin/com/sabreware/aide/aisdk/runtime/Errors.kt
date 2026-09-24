package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.AiSdkError
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.Usage

/**
 * The model's response did not satisfy a tool choice the round enforced.
 *
 * Raised for [ToolChoice.Required] when the round produced no tool call at all, and for
 * [ToolChoice.Specific] when it produced no call to the named tool — whatever else it produced. A vendor
 * that was told "you must call X" and answered in prose has broken the contract the caller relied on, and
 * letting that response through as an ordinary text step is how a forced tool call silently becomes a
 * paragraph nobody executes.
 *
 * [content] is the round's content as the model returned it. A provider that serialized the call as text
 * or reasoning instead of a structured tool call leaves it recoverable there — a caller that wants to
 * salvage such a response inspects the content rather than the message.
 *
 * `generateText` throws it. `streamText` reports it as [RunEvent.Error] and ends the round with an `error`
 * finish, AFTER the completed call's usage and metadata have been recorded — and never retries it,
 * however [StreamRetries] is configured, because a model that declined a forced tool is not a transient
 * failure.
 */
public class ToolChoiceViolationError(
    /** The choice the response failed: [ToolChoice.Required] or a [ToolChoice.Specific]. */
    public val toolChoice: ToolChoice,
    /** Why the model stopped — usually `stop`, which is the whole problem. */
    public val finishReason: FinishReason,
    /**
     * What the refused call cost. Not on the reference's error — its telemetry saw the completed call
     * before the throw — but here the throw is the only report `generateText` makes, and a failed call
     * that loses its own usage is one nobody can account for.
     */
    public val usage: Usage,
    /** The provider that returned the response. */
    public val provider: String,
    /** The model that returned the response. */
    public val modelId: String,
    /** The round's content, verbatim — where a call serialized as text can be found. */
    public val content: List<Content>,
    message: String = toolChoiceViolationMessage(toolChoice),
) : AiSdkError("AI_ToolChoiceViolationError", message)

/** The reference's two messages, spelled the same way. */
private fun toolChoiceViolationMessage(toolChoice: ToolChoice): String = when (toolChoice) {
    is ToolChoice.Specific ->
        "Model response did not contain a call to the required tool '${toolChoice.toolName}'."
    else -> "Model response did not contain a tool call even though tool choice was required."
}

/**
 * The violation this round's result commits against the tool choice [options] enforced, or null when the
 * choice was satisfied or nothing was enforced.
 *
 * Every tool call in the content counts — provider-executed, and even one that fails validation later:
 * the model DID call a tool, which is what the choice demanded. `auto` and `none` enforce nothing.
 */
internal fun GenerateResult.toolChoiceViolation(
    model: LanguageModel,
    options: CallOptions,
): ToolChoiceViolationError? {
    val enforced = options.toolChoice
    if (enforced !is ToolChoice.Required && enforced !is ToolChoice.Specific) return null
    val satisfied = content.filterIsInstance<Content.ToolCall>().any { call ->
        enforced !is ToolChoice.Specific || call.toolName == enforced.toolName
    }
    if (satisfied) return null
    return ToolChoiceViolationError(enforced, finishReason, usage, model.provider, model.modelId, content)
}
