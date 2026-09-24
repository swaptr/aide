package com.sabreware.aide.data.llm.aisdk

import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.core.domain.llm.ToolEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The one translation between AIDE's envelope and the spec's [ToolOutput], in both directions — the
 * executor, the completed event and the replayed history all read it, so a drift here shows up as three
 * different accounts of the same tool call.
 */
class ToolEnvelopesTest {

    @Test
    fun `a success envelope is structured output and carries no error`() {
        val envelope = ToolEnvelope.success { put("paths", JsonPrimitive("/a")) }

        val output = envelope.toToolOutput()
        assertTrue(output is ToolOutput.Json)

        val persisted = output.toEnvelopeJson()
        assertNull(persisted.error)
        assertEquals(envelope, persisted.json.parseArgsOrEmpty())
    }

    @Test
    fun `a failure envelope is a flagged error and keeps its code`() {
        val envelope = ToolEnvelope.failure("RATE_LIMITED", "slow down")

        val output = envelope.toToolOutput()
        assertTrue(output is ToolOutput.ErrorJson)

        val persisted = output.toEnvelopeJson()
        assertEquals("RATE_LIMITED", persisted.error)
        assertEquals("slow down", persisted.json.parseArgsOrEmpty()["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an error the runtime described in prose becomes an INVALID_CALL envelope`() {
        val persisted = ToolOutput.ErrorText("missing required property q").toEnvelopeJson()

        assertEquals("INVALID_CALL", persisted.error)
        val envelope = persisted.json.parseArgsOrEmpty()
        assertEquals(JsonPrimitive(false), envelope["ok"])
        assertEquals("missing required property q", envelope["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a denial becomes a USER_CANCELLED envelope`() {
        val persisted = ToolOutput.ExecutionDenied("not approved").toEnvelopeJson()

        assertEquals("USER_CANCELLED", persisted.error)
        assertEquals("not approved", persisted.json.parseArgsOrEmpty()["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `plain and multipart text become a success envelope with the text`() {
        assertEquals("hi", ToolOutput.Text("hi").toEnvelopeJson().json.parseArgsOrEmpty()["text"]?.jsonPrimitive?.content)

        val multipart = ToolOutput.Multipart(
            listOf(ToolOutput.Multipart.Item.Text("a"), ToolOutput.Multipart.Item.Text("b")),
        ).toEnvelopeJson()
        assertNull(multipart.error)
        assertEquals("a\nb", multipart.json.parseArgsOrEmpty()["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a structured error without a code still reads as an error`() {
        val persisted = ToolOutput.ErrorJson(buildJsonObject { put("detail", "x") }).toEnvelopeJson()

        assertEquals("ERROR", persisted.error)
        assertEquals("x", persisted.json.parseArgsOrEmpty()["detail"]?.jsonPrimitive?.content)
    }

    @Test
    fun `arguments parse to an object, and anything else to an empty one`() {
        assertEquals(buildJsonObject { put("q", "x") }, """{"q":"x"}""".parseArgsOrEmpty())
        assertEquals(JsonObject(emptyMap()), "".parseArgsOrEmpty())
        assertEquals(JsonObject(emptyMap()), """{"q":""".parseArgsOrEmpty())
        assertEquals(JsonObject(emptyMap()), "[1,2]".parseArgsOrEmpty())
    }
}
