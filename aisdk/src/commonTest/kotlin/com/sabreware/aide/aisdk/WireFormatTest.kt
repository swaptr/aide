package com.sabreware.aide.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The EXACT JSON each spec type encodes to, asserted against the reference's own wire names.
 *
 * `SpecSerializationTest` proves a value survives its own round trip, which is the property a consumer
 * needs from us and is necessary but not sufficient. A type can round-trip perfectly through a
 * discriminator we invented, and it will keep round-tripping right up until someone stores a turn with
 * our encoder and reads it with an implementation that followed the specification. That is the failure
 * this file exists to prevent, and it is the one no round-trip test can see.
 *
 * `aisdk/DESIGN.md` sells persist-and-replay as the reason these types are `@Serializable` at all. It is
 * therefore the advertised capability, and the wire it produces is part of the contract rather than an
 * implementation detail. Five mismatches were live before this file existed — `"ContentFilter"` where
 * the spec says `"content-filter"`, `type` where it says `role`, `media` where it says `file`, a
 * `ToolResultPart` that emitted no discriminator at all — every one latent, because only tests encoded
 * these types and no test asserted output.
 *
 * The encoder is deliberately plain [Json] rather than `ProviderJson`: this asserts what the SPEC
 * produces, not what one provider's configuration happens to leave out.
 */
class WireFormatTest {

    private val json = Json { encodeDefaults = false }

    private fun encoded(value: String): JsonObject = Json.parseToJsonElement(value) as JsonObject

    private fun assertWire(expected: String, actual: String) {
        assertEquals(encoded(expected), encoded(actual))
    }

    // -----------------------------------------------------------------------------------------------
    // Messages — discriminated on `role`, not `type`
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `a message is discriminated by role`() {
        assertWire(
            """{"role":"system","content":"be brief"}""",
            json.encodeToString<ModelMessage>(ModelMessage.System("be brief")),
        )
    }

    @Test
    fun `an assistant turn encodes its parts in order with their own discriminators`() {
        val turn = ModelMessage.Assistant(
            listOf(
                AssistantPart.Reasoning(
                    text = "check the calendar",
                    providerOptions = mapOf(
                        "anthropic" to buildJsonObject { put("signature", "sig") },
                    ),
                ),
                AssistantPart.ToolCall("call_1", "calendar", """{"day":"tue"}"""),
            ),
        )

        assertWire(
            """
            {
              "role": "assistant",
              "content": [
                {
                  "type": "reasoning",
                  "text": "check the calendar",
                  "providerOptions": { "anthropic": { "signature": "sig" } }
                },
                {
                  "type": "tool-call",
                  "toolCallId": "call_1",
                  "toolName": "calendar",
                  "input": "{\"day\":\"tue\"}"
                }
              ]
            }
            """.trimIndent(),
            json.encodeToString<ModelMessage>(turn),
        )
    }

    @Test
    fun `a tool turn carries results and approval responses side by side`() {
        val turn = ModelMessage.Tool(
            listOf(
                ToolPart.Result("call_1", "calendar", ToolOutput.Text("free")),
                ToolPart.ApprovalResponse("appr_1", approved = false, reason = "user declined"),
            ),
        )

        assertWire(
            """
            {
              "role": "tool",
              "content": [
                {
                  "type": "tool-result",
                  "toolCallId": "call_1",
                  "toolName": "calendar",
                  "output": { "type": "text", "value": "free" }
                },
                {
                  "type": "tool-approval-response",
                  "approvalId": "appr_1",
                  "approved": false,
                  "reason": "user declined"
                }
              ]
            }
            """.trimIndent(),
            json.encodeToString<ModelMessage>(turn),
        )
    }

    // -----------------------------------------------------------------------------------------------
    // Content
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `every content discriminator matches the reference`() {
        val cases: List<Pair<Content, String>> = listOf(
            Content.Text("hi") to "text",
            Content.Reasoning("thinking") to "reasoning",
            Content.File("image/png", FileData.Url("https://x/y.png")) to "file",
            Content.ReasoningFile("image/png", FileData.Url("https://x/y.png")) to "reasoning-file",
            Content.ToolCall("c", "t", "{}") to "tool-call",
            Content.ToolResult("c", "t", ToolOutput.Text("v")) to "tool-result",
            Content.ToolApprovalRequest("a", "c") to "tool-approval-request",
            Content.Source.Url("s", "https://x") to "source-url",
            Content.Source.Document("s", "application/pdf", "Title") to "source-document",
            Content.Custom("openai.reasoning_item") to "custom",
        )

        cases.forEach { (content, discriminator) ->
            val obj = encoded(json.encodeToString<Content>(content))
            assertEquals(
                discriminator,
                (obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                "discriminator for ${content::class.simpleName}",
            )
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Tool output — the multipart item was tagged `media` where the reference says `file`
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `multipart tool output uses the reference item tags`() {
        val output = ToolOutput.Multipart(
            listOf(
                ToolOutput.Multipart.Item.Text("here is the chart"),
                ToolOutput.Multipart.Item.File(
                    data = FileData.Url("https://x/chart.png"),
                    mediaType = "image/png",
                ),
            ),
        )

        assertWire(
            """
            {
              "type": "content",
              "value": [
                { "type": "text", "text": "here is the chart" },
                {
                  "type": "file",
                  "data": { "type": "url", "url": "https://x/chart.png" },
                  "mediaType": "image/png"
                }
              ]
            }
            """.trimIndent(),
            json.encodeToString<ToolOutput>(output),
        )
    }

    @Test
    fun `every tool output discriminator matches the reference`() {
        val cases: List<Pair<ToolOutput, String>> = listOf(
            ToolOutput.Text("v") to "text",
            ToolOutput.Json(buildJsonObject { put("k", 1) }) to "json",
            ToolOutput.ErrorText("boom") to "error-text",
            ToolOutput.ErrorJson(buildJsonObject { put("code", "E") }) to "error-json",
            ToolOutput.ExecutionDenied("no") to "execution-denied",
            ToolOutput.Multipart(emptyList()) to "content",
        )

        cases.forEach { (output, discriminator) ->
            val obj = encoded(json.encodeToString<ToolOutput>(output))
            assertEquals(
                discriminator,
                (obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                "discriminator for ${output::class.simpleName}",
            )
        }
    }

    // -----------------------------------------------------------------------------------------------
    // File data — the two arms added so a vendor-held file is referenced rather than re-uploaded
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `file data arms encode to the reference tags`() {
        assertWire(
            """{"type":"url","url":"https://x/y"}""",
            json.encodeToString<FileData>(FileData.Url("https://x/y")),
        )
        assertWire(
            """{"type":"reference","reference":{"openai":"file-abc123"}}""",
            json.encodeToString<FileData>(FileData.Reference(mapOf("openai" to "file-abc123"))),
        )
        assertWire(
            """{"type":"text","text":"inline document"}""",
            json.encodeToString<FileData>(FileData.Text("inline document")),
        )
    }

    // -----------------------------------------------------------------------------------------------
    // Finish reason — the cheapest fix in the audit, and the one most likely to reach a wire
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `finish reasons encode kebab-case, not Kotlin case`() {
        val expected = mapOf(
            FinishReason.Unified.Stop to "stop",
            FinishReason.Unified.Length to "length",
            FinishReason.Unified.ContentFilter to "content-filter",
            FinishReason.Unified.ToolCalls to "tool-calls",
            FinishReason.Unified.Error to "error",
            FinishReason.Unified.Other to "other",
        )

        expected.forEach { (unified, wire) ->
            assertWire(
                """{"unified":"$wire"}""",
                json.encodeToString(FinishReason(unified)),
            )
        }
    }

    @Test
    fun `a vendor's own finish reason is kept beside the normalized one`() {
        assertWire(
            """{"unified":"tool-calls","raw":"MALFORMED_FUNCTION_CALL"}""",
            json.encodeToString(FinishReason(FinishReason.Unified.ToolCalls, "MALFORMED_FUNCTION_CALL")),
        )
    }

    // -----------------------------------------------------------------------------------------------
    // Defaults must not reach the wire
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `absent optional fields are omitted rather than sent null`() {
        // Several vendors reject an explicit null where they accept an absent key, and `null means omit`
        // is the porting rule this whole specification is written against.
        assertWire(
            """{"type":"text","text":"hi"}""",
            json.encodeToString<Content>(Content.Text("hi")),
        )
    }

    @Test
    fun `an invalid tool call says so on the wire`() {
        // A truncated call has to be replayed — the model produced it — and must never be executed. If
        // the flag did not survive encoding, a run resumed from storage would execute it.
        assertWire(
            """{"type":"tool-call","toolCallId":"c","toolName":"t","input":"{\"a\":","invalid":true}""",
            json.encodeToString<Content>(
                Content.ToolCall("c", "t", """{"a":""", invalid = true),
            ),
        )
    }
}
