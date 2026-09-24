package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.testing.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The finish part's `providerMetadata["anthropic"]` — the payload that makes container reuse work.
 *
 * The container id, stop details, per-iteration usage and applied context edits exist ONLY on the
 * message_start/message_delta events; a mapper that reads them and surfaces nothing leaves every
 * multi-step code-execution run allocating a fresh container and losing the last one's files.
 */
class AnthropicFinishMetadataTest {

    @Test
    fun `container, stop details, iterations and applied edits ride the finish part`() = runTest {
        val server = TestServer(
            TestServer.sse(
                "event: message_start\n" +
                    """data: {"type":"message_start","message":{"id":"msg_1","model":"claude-opus-4-5",""" +
                    """"usage":{"input_tokens":10},"container":{"id":"cont_1","expires_at":"2026-01-01T00:00:00Z"}}}""" +
                    "\n\n",
                "event: message_delta\n" +
                    """data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null,""" +
                    """"stop_details":{"type":"end_turn","category":"normal"},""" +
                    """"container":{"id":"cont_1","expires_at":"2026-01-01T00:05:00Z",""" +
                    """"skills":[{"type":"anthropic","skill_id":"pdf","version":"1"}]}},""" +
                    """"usage":{"output_tokens":12,"iterations":[{"type":"message","input_tokens":10,"output_tokens":12}]},""" +
                    """"context_management":{"applied_edits":[{"type":"clear_tool_uses_20250919",""" +
                    """"cleared_tool_uses":3,"cleared_input_tokens":900}]}}""" +
                    "\n\n",
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
            ),
        )
        val parts = AnthropicLanguageModel(modelId = "claude-opus-4-5", http = server.http())
            .doStream(CallOptions(prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi"))))))
            .stream.toList()

        val metadata = parts.filterIsInstance<StreamPart.Finish>().single()
            .providerMetadata!!.getValue(ANTHROPIC_PROVIDER_ID)

        val container = metadata["container"]!!.jsonObject
        assertEquals("cont_1", container["id"]!!.jsonPrimitive.content)
        // The delta's container wins: it carries the refreshed expiry and the skills.
        assertEquals("2026-01-01T00:05:00Z", container["expiresAt"]!!.jsonPrimitive.content)
        assertEquals(
            "pdf",
            (container["skills"] as JsonArray)[0].jsonObject["skillId"]!!.jsonPrimitive.content,
        )

        assertEquals("end_turn", metadata["stopDetails"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("normal", metadata["stopDetails"]!!.jsonObject["category"]!!.jsonPrimitive.content)

        assertEquals(
            10,
            (metadata["iterations"] as JsonArray)[0].jsonObject["inputTokens"]!!.jsonPrimitive.content.toInt(),
        )

        val edit = (metadata["contextManagement"]!!.jsonObject["appliedEdits"] as JsonArray)[0].jsonObject
        assertEquals("clear_tool_uses_20250919", edit["type"]!!.jsonPrimitive.content)
        assertEquals(3, edit["clearedToolUses"]!!.jsonPrimitive.content.toInt())
        assertEquals(900, edit["clearedInputTokens"]!!.jsonPrimitive.content.toInt())
    }
}
