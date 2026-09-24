package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.test.runTest

/**
 * The thinking-binding controls and the `updates` display mode (reference `4d25a08`, `e4292e7`).
 *
 * Bodies are lifted from `anthropic-language-model.test.ts` — "should include the updates thinking
 * display mode", "should serialize thinking binding controls and add the beta header", "should
 * serialize strict thinking binding controls with adaptive thinking", "should omit preserved thinking
 * input transformations when absent". The one departure is recorded in `DESIGN.md`: adaptive thinking
 * here always asks for `display: summarized`, so the third body carries that field where the reference
 * snapshot does not, and `stream: true` because this provider only streams.
 */
class AnthropicThinkingBindingTest {

    private val endTurn =
        "event: message_delta\n" +
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}," +
            "\"usage\":{\"output_tokens\":4}}\n\n"

    private class Sent(val body: JsonObject, val beta: String?, val parts: List<StreamPart>)

    private suspend fun send(modelId: String, options: CallOptions, sse: String = endTurn): Sent {
        val server = TestServer(TestServer.sse(sse))
        val parts = AnthropicLanguageModel(modelId = modelId, http = server.http()).doStream(options).stream.toList()
        val call = server.request()
        return Sent(call.bodyJson(), call.header("anthropic-beta"), parts)
    }

    private fun hello(thinking: JsonObject, maxOutputTokens: Int? = null) = CallOptions(
        prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Hello")))),
        maxOutputTokens = maxOutputTokens,
        providerOptions = mapOf(ANTHROPIC_PROVIDER_ID to buildJsonObject { put("thinking", thinking) }),
    )

    @Test
    fun `display updates goes out and adds its beta`() = runTest {
        val sent = send(
            "claude-opus-4-7",
            hello(buildJsonObject { put("type", "adaptive"); put("display", "updates") }),
        )

        assertEquals(parseJsonObject("""{"type":"adaptive","display":"updates"}"""), sent.body["thinking"])
        assertTrue(ANTHROPIC_THINKING_DISPLAY_UPDATES_BETA in sent.beta.orEmpty(), sent.beta.toString())
    }

    @Test
    fun `a binding-only thinking object is sent without a type`() = runTest {
        // A recovery request that only says how a mismatched prefix is handled: no type, no display,
        // and the neutral effort must NOT derive one into it.
        val sent = send(
            "claude-fable-5",
            hello(buildJsonObject { putJsonObject("blockBinding") { put("prefixMismatchBehavior", "drop_block") } }),
        )

        assertEquals(
            parseJsonObject("""{"block_binding":{"prefix_mismatch_behavior":"drop_block"}}"""),
            sent.body["thinking"],
        )
        assertTrue(ANTHROPIC_THINKING_BINDING_CONTROLS_BETA in sent.beta.orEmpty(), sent.beta.toString())
    }

    @Test
    fun `strict binding rides beside adaptive thinking`() = runTest {
        val sent = send(
            "claude-fable-5-1",
            hello(
                buildJsonObject {
                    put("type", "adaptive")
                    putJsonObject("blockBinding") { put("prefixMismatchBehavior", "error") }
                },
                maxOutputTokens = 4096,
            ),
        )

        assertEquals(
            parseJsonObject(
                """
                {"max_tokens":4096,
                 "messages":[{"content":[{"text":"Hello","type":"text"}],"role":"user"}],
                 "model":"claude-fable-5-1",
                 "thinking":{"block_binding":{"prefix_mismatch_behavior":"error"},"display":"summarized","type":"adaptive"},
                 "stream":true}
                """,
            ),
            sent.body,
        )
        assertTrue(ANTHROPIC_THINKING_BINDING_CONTROLS_BETA in sent.beta.orEmpty(), sent.beta.toString())
    }

    private fun List<StreamPart>.finishMetadata(): JsonObject =
        filterIsInstance<StreamPart.Finish>().single().providerMetadata!!.getValue(ANTHROPIC_PROVIDER_ID)

    @Test
    fun `input transformations are omitted from the finish metadata when absent`() = runTest {
        val sent = send("claude-fable-5-1", CallOptions(prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Hello"))))))

        assertNull(sent.parts.finishMetadata()["inputTransformations"])
    }

    @Test
    fun `input transformations reported on message_delta surface in the finish metadata`() = runTest {
        // The reference pins only the absent case; the values here are placeholders that exercise the
        // three-string passthrough the wire schema defines, not vendor vocabulary.
        val sse =
            "event: message_delta\n" +
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}," +
                "\"usage\":{\"output_tokens\":4}," +
                "\"input_transformations\":[{\"type\":\"thinking_block_dropped\"," +
                "\"path\":\"messages.1.content.0\",\"reason\":\"prefix_mismatch\"}]}\n\n"
        val sent = send(
            "claude-fable-5-1",
            hello(buildJsonObject { putJsonObject("blockBinding") { put("prefixMismatchBehavior", "drop_block") } }),
            sse = sse,
        )

        assertEquals(
            parseJsonObject(
                """{"transformations":[{"type":"thinking_block_dropped","path":"messages.1.content.0","reason":"prefix_mismatch"}]}""",
            )["transformations"],
            sent.parts.finishMetadata()["inputTransformations"],
        )
        assertEquals("end_turn", sent.parts.filterIsInstance<StreamPart.Finish>().single().finishReason.raw)
        assertTrue(sent.body["thinking"]!!.jsonObject.containsKey("block_binding"))
    }
}
