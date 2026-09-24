package com.sabreware.aide.aisdk.providers.openaicompatible

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.InvalidPromptError
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning

/**
 * One prompt string, the stop sequence that keeps the model inside its own turn, and what could not be
 * carried across.
 */
internal data class CompletionPrompt(
    val prompt: String,
    val stopSequences: List<String>,
    val warnings: List<Warning>,
)

/**
 * Flattens the conversation into the single string the legacy Completions endpoint takes.
 *
 * Ported from the reference's `convertToOpenAICompletionPrompt`: a leading system message becomes a
 * preamble, each turn is written as a `user:` or `assistant:` block, and the prompt ends on an open
 * `assistant:` line for the model to continue. The `\nuser:` stop sequence is what keeps it from
 * continuing past its own turn and writing the user's next one.
 *
 * The refusals match the reference exactly: a system message anywhere but first, a tool turn, and a
 * tool call inside an assistant turn all throw, because none has a textual form that would not lie to
 * the model about what happened. Where the reference silently DROPS a part it cannot render — an
 * image, a generated file, a tool result — this warns instead, the rule `TODO.md` records for
 * Perplexity's tool drop: an attachment dropped in silence is an answer about a picture the model never
 * saw, and the caller has no way to find that out.
 */
internal fun Prompt.toCompletionPrompt(
    user: String = "user",
    assistant: String = "assistant",
): CompletionPrompt {
    val warnings = mutableListOf<Warning>()
    val text = StringBuilder()
    var messages: List<ModelMessage> = this
    (messages.firstOrNull() as? ModelMessage.System)?.let { system ->
        text.append(system.content).append("\n\n")
        messages = messages.drop(1)
    }

    messages.forEach { message ->
        when (message) {
            is ModelMessage.System -> throw InvalidPromptError(
                message = "Unexpected system message in prompt: ${message.content}",
                prompt = this,
            )

            is ModelMessage.User -> text.append(user).append(":\n")
                .append(message.content.joinUserText(warnings)).append("\n\n")

            is ModelMessage.Assistant -> text.append(assistant).append(":\n")
                .append(message.content.joinAssistantText(warnings)).append("\n\n")

            is ModelMessage.Tool -> throw UnsupportedFunctionalityError("tool messages")
        }
    }

    text.append(assistant).append(":\n")
    return CompletionPrompt(
        prompt = text.toString(),
        stopSequences = listOf("\n$user:"),
        warnings = warnings,
    )
}

private fun List<UserPart>.joinUserText(warnings: MutableList<Warning>): String = buildString {
    this@joinUserText.forEach { part ->
        when (part) {
            is UserPart.Text -> append(part.text)
            is UserPart.File -> warnings += droppedPart("a ${part.mediaType} attachment")
        }
    }
}

private fun List<AssistantPart>.joinAssistantText(warnings: MutableList<Warning>): String = buildString {
    this@joinAssistantText.forEach { part ->
        when (part) {
            is AssistantPart.Text -> append(part.text)
            is AssistantPart.ToolCall -> throw UnsupportedFunctionalityError("tool-call messages")
            is AssistantPart.ToolResult -> warnings += droppedPart("a ${part.toolName} tool result")
            is AssistantPart.File -> warnings += droppedPart("a generated ${part.mediaType} file")
            is AssistantPart.ReasoningFile -> warnings += droppedPart("a generated ${part.mediaType} file")
            // Reasoning has nothing to replay on this wire — no id, no signature, and a model that
            // never wrote its thoughts into the prompt should not read them back as its own words. The
            // approval and custom parts are bookkeeping between the runtime and its host, not content.
            is AssistantPart.Reasoning, is AssistantPart.ApprovalRequest, is AssistantPart.Custom -> Unit
        }
    }
}

private fun droppedPart(what: String): Warning = Warning.Unsupported(
    feature = "non-text parts",
    details = "The legacy Completions API takes one prompt string; $what was dropped.",
)
