package com.sabreware.aide.data.llm.aisdk

import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.runtime.Step
import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.chat.AideRole
import kotlinx.serialization.json.JsonObject

/**
 * One completed round, in the shape AIDE persists and replays.
 *
 * The session keeps its own copy of the conversation as [AideMessage]s rather than a parallel spec
 * `Prompt`, so the next turn's request is built by the ONE mapping the transcript's replay also goes
 * through ([toAisdkPrompt]). Two representations of the same history would be two places for a
 * signature to go missing.
 *
 * Part ORDER is the contract: reasoning blocks keep their position ahead of the text and the calls they
 * preceded, because Anthropic verifies the replayed sequence and Gemini rejects a call whose thought
 * signature was moved. [Content.File], [Content.Source] and [Content.Custom] have no [AidePart] and are
 * dropped — today's behaviour, and nothing the app renders.
 *
 * [reasoningDurationsMs] is one entry per reasoning block in the order the blocks opened, which is the
 * order the assembler lists them; a missing entry reads as zero rather than failing the turn.
 * [parsedArgs] is what the session already parsed for the started event and the dispatcher, keyed by
 * call id; a call it does not know (never announced) is parsed here once.
 */
internal fun Step.toAideMessages(
    reasoningDurationsMs: List<Long>,
    parsedArgs: Map<String, JsonObject> = emptyMap(),
): List<AideMessage> {
    var reasoningIndex = 0
    val assistantParts = content.mapNotNull { part ->
        when (part) {
            is Content.Reasoning -> AidePart.Thinking(
                text = part.text,
                durationMs = reasoningDurationsMs.getOrElse(reasoningIndex++) { 0L },
                providerMetadata = part.providerMetadata,
            )
            is Content.Text -> AidePart.Text(part.text).takeIf { part.text.isNotEmpty() }
            // A provider-executed call was run by the vendor and answered inside the same turn; persisted
            // as a client call it would come back unanswered, be given a synthetic failure by the
            // normalizer and be replayed as a tool round the vendor never asked for.
            is Content.ToolCall -> if (part.providerExecuted) null else AidePart.ToolCall(
                callId = part.toolCallId,
                name = part.toolName,
                // The parsed object, or `{}` for an invalid call — what the event showed, and what the
                // vendor accepts on replay where the model's own half-streamed string would 400.
                argsJson = (parsedArgs[part.toolCallId] ?: part.input.parseArgsOrEmpty()).toString(),
                providerMetadata = part.providerMetadata,
            )
            else -> null
        }
    }
    return buildList {
        if (assistantParts.isNotEmpty()) add(AideMessage(AideRole.Model, assistantParts))
        // One result per Tool message: the invariant `normalizeForWire`'s fan-out establishes and the
        // per-vendor mappers rely on, kept here so the history never needs repairing on the way out.
        toolResults.forEach { result ->
            val envelope = result.output.toEnvelopeJson()
            add(
                AideMessage(
                    role = AideRole.Tool,
                    parts = listOf(
                        AidePart.ToolResponse(
                            name = result.toolName,
                            json = envelope.json,
                            callId = result.toolCallId,
                            error = envelope.error,
                        ),
                    ),
                ),
            )
        }
    }
}
