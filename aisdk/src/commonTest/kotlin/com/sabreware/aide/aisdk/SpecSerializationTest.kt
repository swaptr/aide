package com.sabreware.aide.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The specification's central claim, under test: a vendor payload this module does not model survives a
 * persist/replay round trip **unchanged**.
 *
 * This is not a serialization smoke test. Losing a thinking signature between turns is the single defect
 * that motivated the port — Koog drops it on the Anthropic stream, and Anthropic then rejects the
 * replayed turn outright. If these tests pass, a consumer can store an assistant turn and replay it
 * verbatim; if they fail, nothing built on top can be correct.
 */
class SpecSerializationTest {

    private val json = Json

    // A realistic Anthropic signature: opaque, long, and meaningless to this module — which is the point.
    private val signature = "ErUBCkYIBRgCIkDXm2n4Q1p9sT7yZ0aVbNc2eFgHiJkLmNoPqRsTuVwXyZ0123456789=="

    private fun anthropic(vararg entries: Pair<String, String>): ProviderMetadata =
        mapOf("anthropic" to buildJsonObject { entries.forEach { (k, v) -> put(k, v) } })

    @Test
    fun `reasoning signature survives a round trip byte for byte`() {
        val original = Content.Reasoning(
            text = "The user asked for Tuesday. I should check the calendar first.",
            providerMetadata = anthropic("signature" to signature),
        )

        val decoded = json.decodeFromString<Content>(json.encodeToString<Content>(original))

        assertEquals(original, decoded)
        val recovered = (decoded as Content.Reasoning).providerMetadata
            ?.forProvider("anthropic")
            ?.get("signature")
        assertEquals(signature, recovered?.toString()?.trim('"'))
    }

    @Test
    fun `redacted thinking survives and stays distinguishable from ordinary reasoning`() {
        val redacted = Content.Reasoning(
            text = "",
            providerMetadata = anthropic("redactedData" to "EroBCkYIBRgCIkC9zX..."),
        )

        val decoded = json.decodeFromString<Content>(json.encodeToString<Content>(redacted))

        assertEquals(redacted, decoded)
        assertTrue((decoded as Content.Reasoning).providerMetadata!!.forProvider("anthropic")!!.containsKey("redactedData"))
    }

    @Test
    fun `assistant turn keeps part order across a round trip`() {
        // Anthropic requires the replayed turn to BEGIN with its thinking block. Order is the contract,
        // which is why content is a List and not a bag of optional fields.
        val turn = ModelMessage.Assistant(
            content = listOf(
                AssistantPart.Reasoning("first, think", providerOptions = anthropic("signature" to signature)),
                AssistantPart.Text("I'll look that up."),
                AssistantPart.ToolCall(
                    toolCallId = "toolu_01A",
                    toolName = "calendar_search",
                    input = """{"day":"tuesday"}""",
                ),
            ),
        )

        val decoded = json.decodeFromString<ModelMessage>(json.encodeToString<ModelMessage>(turn))

        assertEquals(turn, decoded)
        val parts = (decoded as ModelMessage.Assistant).content
        assertTrue(parts[0] is AssistantPart.Reasoning, "a replayed turn must still begin with reasoning")
        assertTrue(parts[2] is AssistantPart.ToolCall)
    }

    @Test
    fun `metadata for an unknown provider is carried, not dropped`() {
        // The whole design: this module has never heard of "acme", and must still round-trip its payload.
        val part = Content.Text(
            text = "hi",
            providerMetadata = mapOf(
                "acme" to buildJsonObject {
                    put("somethingInventedTomorrow", "keep me")
                },
            ),
        )

        val decoded = json.decodeFromString<Content>(json.encodeToString<Content>(part))

        assertEquals(part, decoded)
    }

    @Test
    fun `discriminators match the reference wire names`() {
        val encoded = json.encodeToString<Content>(Content.Reasoning("x"))
        assertTrue(encoded.contains("\"reasoning\""), "expected the reference's `reasoning` tag in: $encoded")

        val toolCall = json.encodeToString<Content>(
            Content.ToolCall(toolCallId = "id", toolName = "t", input = "{}"),
        )
        assertTrue(toolCall.contains("\"tool-call\""), "expected `tool-call` in: $toolCall")
    }

    @Test
    fun `file bytes compare by content, not identity`() {
        val a = FileData.Bytes(byteArrayOf(1, 2, 3))
        val b = FileData.Bytes(byteArrayOf(1, 2, 3))

        // A data class over ByteArray compares by reference by default, which would make every equality
        // assertion above meaningless for attachments.
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a, json.decodeFromString<FileData>(json.encodeToString<FileData>(a)))
    }

    @Test
    fun `tool output variants stay distinct after decoding`() {
        val outputs: List<ToolOutput> = listOf(
            ToolOutput.Text("ok"),
            ToolOutput.ErrorText("boom"),
            ToolOutput.ExecutionDenied("user declined"),
        )

        val decoded = outputs.map { json.decodeFromString<ToolOutput>(json.encodeToString<ToolOutput>(it)) }

        assertEquals(outputs, decoded)
        // A failure that decodes as ordinary text is how a model ends up believing a tool succeeded.
        assertTrue(decoded[1] is ToolOutput.ErrorText)
        assertTrue(decoded[2] is ToolOutput.ExecutionDenied)
    }
}
