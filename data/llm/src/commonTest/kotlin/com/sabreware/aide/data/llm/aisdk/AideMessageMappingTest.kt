package com.sabreware.aide.data.llm.aisdk

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.providers.anthropic.ANTHROPIC_PROVIDER_ID
import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.chat.AideRole
import com.sabreware.aide.core.domain.chat.ProviderPayloadKeys
import com.sabreware.aide.core.domain.chat.providerPayloadOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive

/**
 * The outbound half of the seam: AIDE's persisted history into the spec's prompt.
 *
 * A join is where a signature that survived storage can still be dropped on its way into the wire
 * types, so each replayable payload is asserted under the namespace the provider reads it from.
 */
class AideMessageMappingTest {

    private val signature = "ErUBCkYIBRgCIkDXm2n4Q1p9sT7yZ0aVbNc"
    private val redacted = "EroBCkYIBRgCIkC9zXencrypted"

    @Test
    fun `a persisted signature reaches the provider under its namespace`() {
        val history = listOf(
            AideMessage(role = AideRole.User, parts = listOf(AidePart.Text("hi"))),
            AideMessage(
                role = AideRole.Model,
                parts = listOf(
                    AidePart.Thinking(
                        text = "thought",
                        durationMs = 5,
                        providerMetadata = providerPayloadOf(ANTHROPIC_PROVIDER_ID, ProviderPayloadKeys.SIGNATURE to signature),
                    ),
                ),
            ),
        )

        val assistant = history.toAisdkPrompt().filterIsInstance<ModelMessage.Assistant>().single()

        val reasoning = assistant.content.single() as AssistantPart.Reasoning
        // Straight across, unchanged: both sides carry the same provider-namespaced shape.
        val payload = reasoning.providerOptions?.get(ANTHROPIC_PROVIDER_ID)
        assertEquals(signature, payload?.get(ProviderPayloadKeys.SIGNATURE)?.jsonPrimitive?.content)
    }

    @Test
    fun `a redacted block keeps its payload`() {
        val history = listOf(
            AideMessage(
                role = AideRole.Model,
                parts = listOf(
                    AidePart.Thinking(
                        text = "",
                        durationMs = 0,
                        providerMetadata = providerPayloadOf(ANTHROPIC_PROVIDER_ID, ProviderPayloadKeys.REDACTED to redacted),
                    ),
                ),
            ),
        )

        val reasoning = (history.toAisdkPrompt().single() as ModelMessage.Assistant).content.single() as AssistantPart.Reasoning

        assertEquals(
            redacted,
            reasoning.providerOptions?.get(ANTHROPIC_PROVIDER_ID)?.get(ProviderPayloadKeys.REDACTED)?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `unsigned reasoning replays without inventing a signature`() {
        val history = listOf(
            AideMessage(
                role = AideRole.Model,
                parts = listOf(AidePart.Thinking("open trace", durationMs = 1, providerMetadata = null)),
            ),
        )

        val reasoning = (history.toAisdkPrompt().single() as ModelMessage.Assistant).content.single() as AssistantPart.Reasoning

        assertNull(reasoning.providerOptions)
    }

    @Test
    fun `roles map onto the spec's message kinds`() {
        val history = listOf(
            AideMessage(role = AideRole.System, parts = listOf(AidePart.Text("be terse"))),
            AideMessage(role = AideRole.User, parts = listOf(AidePart.Text("hi"))),
            AideMessage(role = AideRole.Model, parts = listOf(AidePart.Text("hello"))),
            AideMessage(role = AideRole.Tool, parts = listOf(AidePart.ToolResponse(name = "t", json = "{}", callId = "c1"))),
        )

        val prompt = history.toAisdkPrompt()

        assertTrue(prompt[0] is ModelMessage.System)
        assertTrue(prompt[1] is ModelMessage.User)
        assertTrue(prompt[2] is ModelMessage.Assistant)
        assertTrue(prompt[3] is ModelMessage.Tool)
    }

    @Test
    fun `a tool call replays so the vendor can pair it with its result`() {
        val history = listOf(
            AideMessage(
                role = AideRole.Model,
                parts = listOf(AidePart.ToolCall(callId = "c1", name = "lookup", argsJson = """{"q":"x"}""")),
            ),
        )

        val call = (history.toAisdkPrompt().single() as ModelMessage.Assistant).content.single() as AssistantPart.ToolCall

        assertEquals("c1", call.toolCallId)
        assertEquals("""{"q":"x"}""", call.input)
    }
}
