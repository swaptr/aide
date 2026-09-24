package com.sabreware.aide.data.llm

import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.chat.AideRole
import com.sabreware.aide.core.domain.llm.ToolEnvelope

/**
 * One list-level repair pass run ONCE before any provider mapper sees the history (A0). The IR is
 * persisted and provider-neutral — a chat created under one provider can be reloaded and resumed under
 * another — so the per-provider mappers must NOT each re-derive pairing / coalescing / id rules (the
 * source of the round-trip bugs). They map an already-validated history instead. This pass:
 *
 *  1. **reassigns globally-unique, stable tool-call ids** by pairing each `ToolResponse` to the nearest
 *     preceding un-answered `ToolCall`, in document order — fixes cross-turn id collisions and the
 *     null / tool-name id fallbacks (A3, A4, A5). Ids are deterministic (`call_0`, `call_1`, …) so the
 *     same history always normalizes identically and the wire request is internally consistent
 *     regardless of how the ids were originally minted (or whether they collided across a resume).
 *  2. **drops orphaned tool results** — a `ToolResponse` with no matching `ToolCall` (A2), and the now
 *     empty `Tool` message with it.
 *  3. **answers orphaned tool calls** — a `ToolCall` nothing responded to before the next turn gets a
 *     synthesized `NO_RESULT` failure. A turn cancelled or killed between "call started" and "result
 *     recorded" persists exactly this shape, and every vendor 400s on a call with no result; the
 *     runtime's own prompt validation refuses it before the request. The call is answered rather than
 *     deleted because deleting it can empty the assistant turn (another 400), and because an error the
 *     model can read tells it what happened.
 *  4. **fans out** a `Tool` message carrying more than one `ToolResponse` into one message each,
 *     establishing the "exactly one `ToolResponse` per `Tool` message" invariant the mappers rely on (A7).
 *  5. **coalesces consecutive same-role** User/Model/System messages (A6 — Anthropic strict
 *     alternation). `Tool` messages are left discrete (one result each) on purpose.
 */
fun List<AideMessage>.normalizeForWire(): List<AideMessage> =
    coalesceSameRole(fanOutToolResponses(reassignToolIds(this)))

/** The error code a synthesized result for an unanswered call carries. */
const val NO_RESULT_ERROR_CODE: String = "NO_RESULT"

private class PendingCall(val newId: String, val name: String)

private fun reassignToolIds(messages: List<AideMessage>): List<AideMessage> {
    var counter = 0
    // oldId -> the call emitted but not yet answered. Insertion-ordered so a null-id response
    // (legacy / non-OpenAI origin) can pair with the earliest open call.
    val pending = LinkedHashMap<String, PendingCall>()
    val out = mutableListOf<AideMessage>()

    for (msg in messages) {
        // Anything but a result ends the round: every call still open is answered right here, so the
        // results sit where the vendors require them — immediately after the turn that made the calls.
        if (msg.role != AideRole.Tool && pending.isNotEmpty()) {
            pending.values.forEach { out += synthesizedResult(it) }
            pending.clear()
        }
        when (msg.role) {
            AideRole.Model -> {
                val parts = msg.parts.map { part ->
                    if (part is AidePart.ToolCall) {
                        val newId = "call_${counter++}"
                        pending[part.callId] = PendingCall(newId, part.name)
                        part.copy(callId = newId)
                    } else {
                        part
                    }
                }
                out += msg.copy(parts = parts)
            }

            AideRole.Tool -> {
                val repaired = msg.parts.mapNotNull { part ->
                    if (part !is AidePart.ToolResponse) return@mapNotNull part
                    val newId = part.callId?.let { pending.remove(it)?.newId }
                        ?: pending.entries.firstOrNull()?.let { pending.remove(it.key); it.value.newId }
                    // No matching open call → orphaned result; drop it (never keep one side of the pair).
                    newId?.let { part.copy(callId = it) }
                }
                // Drop a Tool message left with no surviving tool result.
                if (repaired.any { it is AidePart.ToolResponse }) out += msg.copy(parts = repaired)
            }

            else -> out += msg
        }
    }
    pending.values.forEach { out += synthesizedResult(it) }
    return out
}

private fun synthesizedResult(call: PendingCall): AideMessage = AideMessage(
    role = AideRole.Tool,
    parts = listOf(
        AidePart.ToolResponse(
            name = call.name,
            json = ToolEnvelope.failure(
                NO_RESULT_ERROR_CODE,
                "the tool call was interrupted before a result was recorded",
            ).toString(),
            callId = call.newId,
            error = NO_RESULT_ERROR_CODE,
        ),
    ),
)

private fun fanOutToolResponses(messages: List<AideMessage>): List<AideMessage> = buildList {
    for (msg in messages) {
        val responses = msg.parts.filterIsInstance<AidePart.ToolResponse>()
        if (msg.role != AideRole.Tool || responses.size <= 1) {
            add(msg)
            continue
        }
        val others = msg.parts.filterNot { it is AidePart.ToolResponse }
        responses.forEachIndexed { index, response ->
            add(msg.copy(parts = if (index == 0) others + response else listOf(response)))
        }
    }
}

private fun coalesceSameRole(messages: List<AideMessage>): List<AideMessage> {
    val out = mutableListOf<AideMessage>()
    for (msg in messages) {
        val last = out.lastOrNull()
        // Tool stays discrete: fan-out just split it, and OpenAI/Ollama need one tool message per result.
        if (last != null && last.role == msg.role && msg.role != AideRole.Tool) {
            out[out.lastIndex] = last.copy(parts = last.parts + msg.parts)
        } else {
            out.add(msg)
        }
    }
    return out
}
