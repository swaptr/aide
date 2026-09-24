package com.sabreware.aide.data.llm.aisdk

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.providers.anthropic.ANTHROPIC_PROVIDER_ID
import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.chat.AideRole
import com.sabreware.aide.core.domain.chat.ProviderPayloadKeys
import com.sabreware.aide.core.domain.chat.providerPayloadOf
import com.sabreware.aide.core.domain.fakes.FakePreferenceStore
import com.sabreware.aide.core.domain.llm.ChatGenerationConfig
import com.sabreware.aide.core.domain.llm.ChatStreamEvent
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.llm.dispatch.IdempotencyCache
import com.sabreware.aide.core.domain.llm.dispatch.RateLimiter
import com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher
import com.sabreware.aide.core.domain.llm.dispatch.Tracer
import com.sabreware.aide.core.domain.llm.gates.WriteConfirmGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The signature's whole journey: provider stream → [ChatStreamEvent] → persisted JSON → replayed prompt.
 *
 * Each hop was tested on its own and the payload was still lost, because the two defects lived exactly
 * between the tested halves: the stream event had no field to carry a payload, and the tool fragment had
 * none either. Both were invisible to a test that started from a hand-built [AideMessage], which is what
 * every test on the outbound side did.
 *
 * The persistence hop is a real serializer round-trip rather than an object handed along, because
 * `partsJson` is where a payload that survives in memory for one session stops surviving.
 */
class AiSdkSeamSignatureTest {

    private val thoughtSignature = "ErUBCkYIBRgCIkDXm2n4Q1p9sT7yZ0aVbNc"
    private val callSignature = "CikBhTnMcm9tIHRoZSBjYWxs"
    private val reasoningPayload =
        providerPayloadOf(ANTHROPIC_PROVIDER_ID, ProviderPayloadKeys.SIGNATURE to thoughtSignature)
    private val callPayload =
        providerPayloadOf("google", ProviderPayloadKeys.THOUGHT_SIGNATURE to callSignature)

    private val dispatcher = ToolDispatcher(
        IdempotencyCache(),
        RateLimiter(),
        Tracer(),
        WriteConfirmGate(),
        FakePreferenceStore(),
        Dispatchers.Unconfined,
    )
    private val ctx = ToolDispatcher.Context(surface = Surface.CHAT, modelId = "m", turnId = "t1")

    /**
     * Streams one scripted round PER CALL, keeping the options it was called with — which is the prompt
     * the replay produced. Per call, not per model: a round that ends in a tool call makes the session
     * come back, and a script that repeats would loop forever rather than fail.
     */
    private class Scripted(private val rounds: List<List<StreamPart>>) : LanguageModel {
        override val provider: String = ANTHROPIC_PROVIDER_ID
        override val modelId: String = "m"
        private var round = 0
        var lastOptions: CallOptions? = null
        override suspend fun doStream(options: CallOptions): StreamResult {
            lastOptions = options
            return StreamResult(rounds[round++].asFlow())
        }
        override suspend fun doGenerate(options: CallOptions): GenerateResult =
            throw UnsupportedOperationException()
    }

    private fun session(model: LanguageModel, history: List<AideMessage>) = AiSdkChatSession(
        model = model,
        tools = emptyList(),
        config = ChatGenerationConfig(),
        activationState = null,
        disabledParams = emptySet(),
        initialMessages = history,
        systemInstruction = null,
        dispatcher = dispatcher,
    )

    @Test
    fun `a signature survives the stream, the events, storage and the replay`() = runTest {
        val first = Scripted(
            listOf(
                listOf(
                    StreamPart.ReasoningStart("r"),
                    StreamPart.ReasoningDelta("r", "weighing it up"),
                    StreamPart.ReasoningEnd("r", providerMetadata = reasoningPayload),
                    StreamPart.ToolCallPart(
                        Content.ToolCall("c1", "lookup", """{"q":"x"}""", providerMetadata = callPayload),
                    ),
                    StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.ToolCalls, raw = "tool_use")),
                ),
                listOf(
                    StreamPart.TextDelta("t", "42."),
                    StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.Stop, raw = "end_turn")),
                ),
            ),
        )

        val events = session(first, emptyList())
            .send(AideMessage.user("go"), ctx)
            .toList()

        // Hop 1→2. The tool round names a tool the model was never offered, so the runtime refuses it
        // with an error result — irrelevant here: what matters is that both payloads reached the
        // neutral events.
        val closed = events.filterIsInstance<ChatStreamEvent.ThinkingDelta>()
            .last { it.providerMetadata != null }
        val started = events.filterIsInstance<ChatStreamEvent.ToolCallStarted>().single()
        assertEquals(reasoningPayload, closed.providerMetadata)
        assertEquals(callPayload, started.providerMetadata)

        // Hop 2→3. These are the parts the use case builds from those events, through the serializer the
        // transcript writes into `ChatEntity.partsJson`.
        val assistantParts = listOf(
            AidePart.Thinking(
                text = events.filterIsInstance<ChatStreamEvent.ThinkingDelta>()
                    .joinToString("") { it.text },
                durationMs = 0L,
                providerMetadata = closed.providerMetadata,
            ),
            AidePart.ToolCall(
                callId = started.callId,
                name = started.name,
                argsJson = started.args.toString(),
                providerMetadata = started.providerMetadata,
            ),
        )
        val serializer = ListSerializer(AidePart.serializer())
        val stored = Json.encodeToString(serializer, assistantParts)
        val reloaded = Json.decodeFromString(serializer, stored)

        // Hop 3→4: a NEW session over the reloaded history — a model switch, or simply the next launch.
        val second = Scripted(
            listOf(
                listOf(
                    StreamPart.TextDelta("t", "42."),
                    StreamPart.Finish(Usage(), FinishReason(FinishReason.Unified.Stop, raw = "end_turn")),
                ),
            ),
        )
        session(
            second,
            listOf(
                AideMessage.user("go"),
                AideMessage(AideRole.Model, reloaded),
                AideMessage(
                    AideRole.Tool,
                    listOf(AidePart.ToolResponse("lookup", """{"ok":true}""", callId = started.callId)),
                ),
            ),
        ).send(AideMessage.user("and now"), ctx).toList()

        val replayed = second.lastOptions!!.prompt.filterIsInstance<ModelMessage.Assistant>().single()
        assertEquals(
            reasoningPayload,
            replayed.content.filterIsInstance<AssistantPart.Reasoning>().single().providerOptions,
            "an unsigned replayed thinking block is dropped by Anthropic and 400s on Gemini",
        )
        assertEquals(
            callPayload,
            replayed.content.filterIsInstance<AssistantPart.ToolCall>().single().providerOptions,
            "a replayed call without its thoughtSignature is rejected outright",
        )
    }
}
