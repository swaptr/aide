package com.sabreware.aide.data.llm

import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.runtime.standardizePrompt
import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.chat.AideRole
import com.sabreware.aide.data.llm.aisdk.toAisdkPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Contract lock for [normalizeForWire] — the single list-level repair pass (A0) every mapper maps after. */
class MessageNormalizerTest {

    private fun model(vararg parts: AidePart) = AideMessage(AideRole.Model, parts.toList())
    private fun tool(vararg parts: AidePart) = AideMessage(AideRole.Tool, parts.toList())
    private fun call(id: String, name: String) = AidePart.ToolCall(id, name, "{}")
    private fun response(name: String, id: String?) = AidePart.ToolResponse(name = name, json = "{}", callId = id)

    private fun List<AideMessage>.callIds() =
        filter { it.role == AideRole.Model }.flatMap { it.parts.filterIsInstance<AidePart.ToolCall>() }.map { it.callId }

    private fun List<AideMessage>.responseIds() =
        filter { it.role == AideRole.Tool }.flatMap { it.parts.filterIsInstance<AidePart.ToolResponse>() }.map { it.callId }

    private fun List<AideMessage>.responses() =
        filter { it.role == AideRole.Tool }.flatMap { it.parts.filterIsInstance<AidePart.ToolResponse>() }

    @Test
    fun crossTurnIdCollision_isReassignedToUniquePairedIds() {
        // Both turns minted the same `tc-1` (the per-turn counter reset bug, A3).
        val out = listOf(
            model(AidePart.Text("a"), call("tc-1", "f")),
            tool(response("f", "tc-1")),
            model(call("tc-1", "g")),
            tool(response("g", "tc-1")),
        ).normalizeForWire()

        val callIds = out.callIds()
        assertEquals(2, callIds.size)
        assertEquals(callIds.size, callIds.toSet().size, "ids must be unique within the chat")
        // every response re-points at exactly its paired call's new id
        assertEquals(callIds.toSet(), out.responseIds().toSet())
    }

    @Test
    fun orphanToolResponse_withNoPrecedingCall_isDropped() {
        val out = listOf(
            AideMessage.user("hi"),
            tool(response("f", "ghost")),
        ).normalizeForWire()

        // the orphan tool message is gone entirely (never keep one side of the pair — A2/A4)
        assertTrue(out.none { it.role == AideRole.Tool })
        assertEquals(1, out.size)
    }

    @Test
    fun nullCallId_pairsWithTheOpenCall() {
        val out = listOf(
            model(call("c1", "f")),
            tool(response("f", null)),
        ).normalizeForWire()

        val callId = out.callIds().single()
        val responseId = out.responseIds().single()
        assertNotNull(responseId)
        assertEquals(callId, responseId)
    }

    @Test
    fun consecutiveSameRoleMessages_areCoalesced() {
        val out = listOf(
            AideMessage(AideRole.User, listOf(AidePart.Text("a"))),
            AideMessage(AideRole.User, listOf(AidePart.Text("b"))),
        ).normalizeForWire()

        assertEquals(1, out.size)
        assertEquals(listOf("a", "b"), out.single().parts.filterIsInstance<AidePart.Text>().map { it.text })
    }

    @Test
    fun multiResponseToolMessage_isFannedOutToOneEach() {
        val out = listOf(
            model(call("a", "f"), call("b", "g")),
            tool(response("f", "a"), response("g", "b")),
        ).normalizeForWire()

        val toolMessages = out.filter { it.role == AideRole.Tool }
        assertEquals(2, toolMessages.size)
        assertTrue(toolMessages.all { it.parts.filterIsInstance<AidePart.ToolResponse>().size == 1 })
        // still correctly paired after the fan-out
        assertEquals(out.callIds().toSet(), out.responseIds().toSet())
    }

    @Test
    fun unansweredCall_beforeTheNextTurn_getsASynthesizedFailure() {
        // A turn cancelled between "call started" and "result recorded" persists exactly this shape.
        val out = listOf(
            AideMessage.user("do it"),
            model(AidePart.Text("on it"), call("c1", "f")),
            AideMessage.user("still there?"),
        ).normalizeForWire()

        assertEquals(listOf(AideRole.User, AideRole.Model, AideRole.Tool, AideRole.User), out.map { it.role })
        val synthesized = out.responses().single()
        assertEquals(out.callIds().single(), synthesized.callId)
        assertEquals("f", synthesized.name)
        assertEquals(NO_RESULT_ERROR_CODE, synthesized.error)
        val envelope = kotlinx.serialization.json.Json.parseToJsonElement(synthesized.json).jsonObject
        assertEquals("false", envelope["ok"]?.jsonPrimitive?.content)
        assertEquals(NO_RESULT_ERROR_CODE, envelope["errorCode"]?.jsonPrimitive?.content)
        // the call itself survives: deleting it can empty the assistant turn, which is its own 400
        assertTrue(out[1].parts.any { it is AidePart.ToolCall })
    }

    @Test
    fun unansweredCall_atTheEndOfTheHistory_isAnsweredToo() {
        val out = listOf(
            AideMessage.user("do it"),
            model(call("c1", "f"), call("c2", "g")),
            tool(response("f", "c1")),
        ).normalizeForWire()

        // c1 was answered, c2 was not: exactly one synthesized result, after the real one.
        val responses = out.responses()
        assertEquals(2, responses.size)
        assertEquals(listOf(null, NO_RESULT_ERROR_CODE), responses.map { it.error })
        assertEquals(out.callIds().toSet(), out.responseIds().toSet())
    }

    @Test
    fun answeredCalls_areLeftAlone() {
        val out = listOf(
            model(call("c1", "f")),
            tool(response("f", "c1")),
            AideMessage.user("next"),
        ).normalizeForWire()

        assertTrue(out.responses().none { it.error != null })
        assertEquals(1, out.responses().size)
    }

    @Test
    fun aRepairedHistory_passesTheRuntimesPromptValidation() {
        val history = listOf(
            AideMessage.user("do it"),
            model(call("c1", "f")),
            AideMessage.user("still there?"),
        )

        // Unrepaired, the runtime refuses the prompt (an unanswered call is a 400 on every vendor).
        assertTrue(runCatching { standardizePrompt(history.toAisdkPrompt()) }.isFailure)

        val prompt = standardizePrompt(history.normalizeForWire().toAisdkPrompt())

        val result = prompt.filterIsInstance<ModelMessage.Tool>().single().content.single() as ToolPart.Result
        assertTrue(result.output is ToolOutput.ErrorJson, "the model is told what happened, in the wire's own terms")
    }
}
