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
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.providers.anthropic.ANTHROPIC_PROVIDER_ID
import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.chat.ProviderPayloadKeys
import com.sabreware.aide.core.domain.chat.providerPayloadOf
import com.sabreware.aide.core.domain.fakes.FakePreferenceStore
import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.ChatGenerationConfig
import com.sabreware.aide.core.domain.llm.ChatStreamEvent
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.llm.ToolActivationState
import com.sabreware.aide.core.domain.llm.ToolEnvelope
import com.sabreware.aide.core.domain.llm.dispatch.IdempotencyCache
import com.sabreware.aide.core.domain.llm.dispatch.RateLimiter
import com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher
import com.sabreware.aide.core.domain.llm.dispatch.Tracer
import com.sabreware.aide.core.domain.llm.gates.WriteConfirmGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Behavioural lock for [AiSdkChatSession]: the translation between `:aisdk:runtime`'s run and the
 * neutral [ChatStreamEvent] union, driven by a scripted [LanguageModel] and a real [ToolDispatcher]
 * (its deps are no-arg). Covers what the turn-runner this replaced used to own — text/usage/finish, the
 * multi-round tool loop, cross-round usage summing — plus what the runtime changed: validation of what
 * the model asked for, and the replayed history the next turn is built from.
 */
class AiSdkChatSessionTest {

    private val dispatcher = ToolDispatcher(
        IdempotencyCache(),
        RateLimiter(),
        Tracer(),
        WriteConfirmGate(),
        FakePreferenceStore(),
        // Handlers run on the test's own dispatcher so the scheduler stays deterministic.
        Dispatchers.Unconfined,
    )
    private val ctx = ToolDispatcher.Context(surface = Surface.CHAT, modelId = "m", turnId = "t1")

    /**
     * Streams one scripted round PER CALL, keeping the options it was called with. Per call, not per
     * model: a round that ends in a tool call makes the runtime come back, and a script that repeated
     * would loop forever rather than fail.
     */
    private class Scripted(private val rounds: List<List<StreamPart>>) : LanguageModel {
        override val provider: String = ANTHROPIC_PROVIDER_ID
        override val modelId: String = "m"
        var calls = 0
        val optionsSeen = mutableListOf<CallOptions>()
        val lastOptions: CallOptions? get() = optionsSeen.lastOrNull()
        override suspend fun doStream(options: CallOptions): StreamResult {
            optionsSeen += options
            return StreamResult(rounds[calls++].asFlow())
        }
        override suspend fun doGenerate(options: CallOptions): GenerateResult =
            throw UnsupportedOperationException()
    }

    private fun finish(reason: FinishReason.Unified, raw: String? = null, input: Int? = null, output: Int? = null) =
        StreamPart.Finish(
            Usage(Usage.InputTokens(total = input), Usage.OutputTokens(total = output)),
            FinishReason(reason, raw),
        )

    private fun toolCall(id: String, name: String, input: String = "{}") =
        StreamPart.ToolCallPart(Content.ToolCall(id, name, input))

    private fun tool(name: String, result: JsonObject = ToolEnvelope.success(), onCall: suspend () -> Unit = {}) =
        AideTool.Function(
            name = name,
            description = "",
            parametersSchema = JsonObject(emptyMap()),
            handler = { onCall(); result },
        )

    private fun session(
        model: LanguageModel,
        tools: List<AideTool> = emptyList(),
        history: List<AideMessage> = emptyList(),
        activationState: ToolActivationState? = null,
        disabledParams: Set<String> = emptySet(),
        config: ChatGenerationConfig = ChatGenerationConfig(),
    ) = AiSdkChatSession(
        model = model,
        tools = tools,
        config = config,
        activationState = activationState,
        disabledParams = disabledParams,
        initialMessages = history,
        systemInstruction = null,
        dispatcher = dispatcher,
    )

    private fun List<ChatStreamEvent>.completed() = last() as ChatStreamEvent.Completed

    private fun List<ChatStreamEvent>.text() = filterIsInstance<ChatStreamEvent.TextDelta>().joinToString("") { it.text }

    // --- text, usage, finish ---------------------------------------------------------------------

    @Test
    fun `text deltas stream and the turn completes with the vendor's usage and reason`() = runTest {
        val model = Scripted(
            listOf(
                listOf(
                    StreamPart.TextDelta("t", "Hello "),
                    StreamPart.TextDelta("t", "world"),
                    finish(FinishReason.Unified.Stop, raw = "stop", input = 10, output = 5),
                ),
            ),
        )

        val events = session(model).send(AideMessage.user("hi"), ctx).toList()

        assertEquals("Hello ", (events[0] as ChatStreamEvent.TextDelta).text)
        assertEquals("world", (events[1] as ChatStreamEvent.TextDelta).text)
        val done = events.completed()
        assertEquals(ChatStreamEvent.StopReason.EndTurn, done.stopReason)
        assertEquals(10, done.usage?.inputTokens)
        assertEquals(5, done.usage?.outputTokens)
        assertEquals("stop", done.rawFinishReason)
    }

    @Test
    fun `usage keeps the last round's prompt count and sums the generated counts`() = runTest {
        val model = Scripted(
            listOf(
                listOf(toolCall("c1", "echo"), finish(FinishReason.Unified.ToolCalls, "tool_calls", input = 100, output = 10)),
                listOf(StreamPart.TextDelta("t", "ok"), finish(FinishReason.Unified.Stop, "stop", input = 120, output = 5)),
            ),
        )

        val done = session(model, listOf(tool("echo"))).send(AideMessage.user("x"), ctx).toList().completed()

        assertEquals(120, done.usage?.inputTokens)
        assertEquals(15, done.usage?.outputTokens)
    }

    @Test
    fun `a stream that closes without a finish part is reported as interrupted`() = runTest {
        val model = Scripted(listOf(listOf(StreamPart.TextDelta("t", "half an ans"))))

        val events = session(model).send(AideMessage.user("hi"), ctx).toList()

        assertEquals("half an ans", events.text())
        assertEquals(ChatStreamEvent.StopReason.Interrupted, events.completed().stopReason)
    }

    @Test
    fun `a provider error mid-stream fails the turn and is rethrown`() = runTest {
        val model = Scripted(
            listOf(listOf(StreamPart.TextDelta("t", "so far"), StreamPart.Error(IllegalStateException("boom")))),
        )
        val events = mutableListOf<ChatStreamEvent>()

        val thrown = assertFailsWith<IllegalStateException> {
            session(model).send(AideMessage.user("hi"), ctx).collect { events += it }
        }

        assertEquals("boom", thrown.message)
        assertEquals(ChatStreamEvent.StopReason.Error, events.completed().stopReason)
    }

    @Test
    fun `cancel() ends the turn as cancelled`() = runTest {
        val inFlight = CompletableDeferred<Unit>()
        val model = object : LanguageModel {
            override val provider = ANTHROPIC_PROVIDER_ID
            override val modelId = "m"
            override suspend fun doStream(options: CallOptions) =
                StreamResult(flow<StreamPart> { inFlight.complete(Unit); awaitCancellation() })
            override suspend fun doGenerate(options: CallOptions): GenerateResult =
                throw UnsupportedOperationException()
        }
        val chat = session(model)
        val events = mutableListOf<ChatStreamEvent>()
        val collector = launch(Dispatchers.Default) {
            chat.send(AideMessage.user("hi"), ctx).collect { events += it }
        }
        // The plug is pulled only once the request is genuinely in flight.
        inFlight.await()

        chat.cancel()
        collector.join()

        assertTrue(collector.isCancelled, "the turn's cancellation reaches the collector")
        assertEquals(ChatStreamEvent.StopReason.Cancelled, events.completed().stopReason)
    }

    @Test
    fun `a whitespace-only first turn fails locally with a readable message`() = runTest {
        val model = Scripted(listOf(listOf(finish(FinishReason.Unified.Stop))))
        val events = mutableListOf<ChatStreamEvent>()

        val thrown = assertFailsWith<Throwable> {
            session(model).send(AideMessage.user("   "), ctx).collect { events += it }
        }

        assertFalse(thrown.message.isNullOrBlank(), "the message reaches the user as the error text")
        assertEquals(ChatStreamEvent.StopReason.Error, events.completed().stopReason)
        assertEquals(0, model.calls, "nothing is sent for a prompt no vendor would accept")
    }

    // --- the tool loop -----------------------------------------------------------------------------

    @Test
    fun `a tool round dispatches, replays the result and continues to the answer`() = runTest {
        val model = Scripted(
            listOf(
                listOf(toolCall("call_1", "echo"), finish(FinishReason.Unified.ToolCalls, "tool_calls")),
                listOf(StreamPart.TextDelta("t", "done"), finish(FinishReason.Unified.Stop, "stop")),
            ),
        )
        val echo = tool("echo", ToolEnvelope.success { put("ok2", true) })

        val events = session(model, listOf(echo)).send(AideMessage.user("call it"), ctx).toList()

        val started = events.filterIsInstance<ChatStreamEvent.ToolCallStarted>().single()
        assertEquals("call_1", started.callId)
        assertEquals("echo", started.name)
        val completed = events.filterIsInstance<ChatStreamEvent.ToolCallCompleted>().single()
        assertEquals("call_1", completed.callId)
        assertNull(completed.error)
        assertEquals("done", events.text())
        assertEquals(ChatStreamEvent.StopReason.EndTurn, events.completed().stopReason)

        // Two rounds ran; round-2's prompt must replay the result so the model can answer.
        assertEquals(2, model.calls)
        val toolTurn = model.lastOptions!!.prompt.filterIsInstance<ModelMessage.Tool>().single()
        val result = toolTurn.content.single() as ToolPart.Result
        assertEquals("call_1", result.toolCallId)
        assertTrue(result.output is ToolOutput.Json)
    }

    @Test
    fun `ids survive renumbering across two sends`() = runTest {
        val model = Scripted(
            listOf(
                listOf(toolCall("vendor-id-9", "echo"), finish(FinishReason.Unified.ToolCalls)),
                listOf(StreamPart.TextDelta("t", "one"), finish(FinishReason.Unified.Stop)),
                listOf(StreamPart.TextDelta("t", "two"), finish(FinishReason.Unified.Stop)),
            ),
        )
        val chat = session(model, listOf(tool("echo")))

        chat.send(AideMessage.user("first"), ctx).toList()
        chat.send(AideMessage.user("second"), ctx).toList()

        val replay = model.lastOptions!!.prompt
        val call = replay.filterIsInstance<ModelMessage.Assistant>()
            .flatMap { it.content }.filterIsInstance<AssistantPart.ToolCall>().single()
        val result = replay.filterIsInstance<ModelMessage.Tool>()
            .flatMap { it.content }.filterIsInstance<ToolPart.Result>().single()
        assertEquals("call_0", call.toolCallId, "normalizeForWire renumbers deterministically")
        assertEquals(call.toolCallId, result.toolCallId, "the pair stays paired")
    }

    @Test
    fun `a call the model was never offered is refused, not dispatched`() = runTest {
        val model = Scripted(
            listOf(
                listOf(toolCall("c1", "nope"), finish(FinishReason.Unified.ToolCalls)),
                listOf(StreamPart.TextDelta("t", "ok"), finish(FinishReason.Unified.Stop)),
                listOf(finish(FinishReason.Unified.Stop)),
            ),
        )
        var invoked = false
        val chat = session(model, listOf(tool("echo") { invoked = true }))

        val events = chat.send(AideMessage.user("x"), ctx).toList()

        assertFalse(invoked)
        val started = events.filterIsInstance<ChatStreamEvent.ToolCallStarted>().single()
        assertEquals("nope", started.name)
        val completed = events.filterIsInstance<ChatStreamEvent.ToolCallCompleted>().single()
        assertEquals("INVALID_CALL", completed.error)

        // The next turn replays the refused call AND its error result, so the turn stays well-formed.
        chat.send(AideMessage.user("again"), ctx).toList()
        val replay = model.lastOptions!!.prompt
        assertTrue(replay.filterIsInstance<ModelMessage.Assistant>().flatMap { it.content }.any {
            it is AssistantPart.ToolCall && it.toolName == "nope"
        })
        val result = replay.filterIsInstance<ModelMessage.Tool>().flatMap { it.content }
            .filterIsInstance<ToolPart.Result>().single()
        assertTrue(result.output is ToolOutput.ErrorJson)
    }

    @Test
    fun `a failure envelope becomes the completed event's error and an error result on replay`() = runTest {
        val model = Scripted(
            listOf(
                listOf(toolCall("c1", "flaky"), finish(FinishReason.Unified.ToolCalls)),
                listOf(StreamPart.TextDelta("t", "sorry"), finish(FinishReason.Unified.Stop)),
                listOf(finish(FinishReason.Unified.Stop)),
            ),
        )
        val chat = session(model, listOf(tool("flaky", ToolEnvelope.failure("BOOM", "bad day"))))

        val events = chat.send(AideMessage.user("x"), ctx).toList()

        val completed = events.filterIsInstance<ChatStreamEvent.ToolCallCompleted>().single()
        assertEquals("BOOM", completed.error)
        assertEquals("bad day", completed.resultJson.parseArgsOrEmpty()["error"]?.jsonPrimitive?.content)

        chat.send(AideMessage.user("again"), ctx).toList()
        val result = model.lastOptions!!.prompt.filterIsInstance<ModelMessage.Tool>().flatMap { it.content }
            .filterIsInstance<ToolPart.Result>().single()
        val output = result.output as ToolOutput.ErrorJson
        assertEquals("BOOM", output.value.jsonObject["errorCode"]?.jsonPrimitive?.content)
    }

    @Test
    fun `two calls in one round are dispatched one at a time and both complete`() = runTest {
        val model = Scripted(
            listOf(
                listOf(toolCall("a", "slow"), toolCall("b", "slow"), finish(FinishReason.Unified.ToolCalls)),
                listOf(StreamPart.TextDelta("t", "ok"), finish(FinishReason.Unified.Stop)),
            ),
        )
        var active = 0
        var overlapped = false
        val slow = tool("slow") {
            active++
            if (active > 1) overlapped = true
            delay(50)
            active--
        }

        val events = session(model, listOf(slow)).send(AideMessage.user("x"), ctx).toList()

        assertFalse(overlapped, "the confirm gates only ever ran serially")
        assertEquals(2, events.filterIsInstance<ChatStreamEvent.ToolCallStarted>().size)
        assertEquals(setOf("a", "b"), events.filterIsInstance<ChatStreamEvent.ToolCallCompleted>().map { it.callId }.toSet())
    }

    @Test
    fun `the loop stops at the round cap with the tool reason`() = runTest {
        val forever = object : LanguageModel {
            override val provider = ANTHROPIC_PROVIDER_ID
            override val modelId = "m"
            var calls = 0
            override suspend fun doStream(options: CallOptions): StreamResult {
                calls++
                return StreamResult(listOf(toolCall("c$calls", "echo"), finish(FinishReason.Unified.ToolCalls, "tool_use")).asFlow())
            }
            override suspend fun doGenerate(options: CallOptions): GenerateResult =
                throw UnsupportedOperationException()
        }

        val events = session(forever, listOf(tool("echo"))).send(AideMessage.user("x"), ctx).toList()

        assertEquals(32, forever.calls)
        assertEquals(ChatStreamEvent.StopReason.ToolUse, events.completed().stopReason)
    }

    // --- what goes on the wire ---------------------------------------------------------------------

    @Test
    fun `a tool behind an activation the model never asked for is not offered`() = runTest {
        val model = Scripted(listOf(listOf(finish(FinishReason.Unified.Stop))))
        val gated = AideTool.Function(
            name = "gated", description = "", parametersSchema = JsonObject(emptyMap()),
            handler = { ToolEnvelope.success() }, category = "phone", requiresActivation = true,
        )

        session(model, listOf(gated, tool("open")), activationState = ToolActivationState())
            .send(AideMessage.user("x"), ctx).toList()

        assertEquals(listOf("open"), model.lastOptions!!.tools?.map { it.name })
    }

    @Test
    fun `sampler knobs the model rejects are omitted from the request`() = runTest {
        val model = Scripted(listOf(listOf(finish(FinishReason.Unified.Stop))))

        session(model, disabledParams = setOf("temperature", "topP", "topK"))
            .send(AideMessage.user("x"), ctx).toList()

        val options = model.lastOptions!!
        assertNull(options.temperature)
        assertNull(options.topP)
        assertNull(options.topK)
        // The baseline `maxTokens` is the on-device engine's, not a ceiling for a remote model: unset.
        assertNull(options.maxOutputTokens)
    }

    // --- reasoning and replay ----------------------------------------------------------------------

    @Test
    fun `a signed block closes with its payload and replays first, ahead of the call it preceded`() = runTest {
        val payload = providerPayloadOf(ANTHROPIC_PROVIDER_ID, ProviderPayloadKeys.SIGNATURE to "sig")
        val model = Scripted(
            listOf(
                listOf(
                    StreamPart.ReasoningStart("r"),
                    StreamPart.ReasoningDelta("r", "weighing"),
                    StreamPart.ReasoningEnd("r", providerMetadata = payload),
                    StreamPart.TextDelta("t", "let me check"),
                    toolCall("c1", "echo", """{"q":"x"}"""),
                    finish(FinishReason.Unified.ToolCalls, "tool_use"),
                ),
                listOf(StreamPart.TextDelta("t", "42."), finish(FinishReason.Unified.Stop, "end_turn")),
                listOf(finish(FinishReason.Unified.Stop)),
            ),
        )
        val chat = session(model, listOf(tool("echo")))

        val events = chat.send(AideMessage.user("go"), ctx).toList()

        val thinking = events.filterIsInstance<ChatStreamEvent.ThinkingDelta>()
        assertEquals("weighing", thinking[0].text)
        assertEquals("", thinking[1].text)
        assertEquals(payload, thinking[1].providerMetadata)

        // The next send replays the session's own history: reasoning first, signed, then text, then the
        // call with its arguments, then the result — exactly what the use case persists from the events.
        chat.send(AideMessage.user("and now"), ctx).toList()
        val replay = model.lastOptions!!.prompt
        val assistant = replay.filterIsInstance<ModelMessage.Assistant>().first()
        val reasoning = assistant.content[0] as AssistantPart.Reasoning
        assertEquals("weighing", reasoning.text)
        assertEquals(payload, reasoning.providerOptions)
        assertEquals("let me check", (assistant.content[1] as AssistantPart.Text).text)
        val call = assistant.content[2] as AssistantPart.ToolCall
        assertEquals("""{"q":"x"}""", call.input)
        val result = replay.filterIsInstance<ModelMessage.Tool>().single().content.single() as ToolPart.Result
        assertEquals(call.toolCallId, result.toolCallId)
        assertEquals(JsonPrimitive(true), (result.output as ToolOutput.Json).value.jsonObject["ok"])
    }

    @Test
    fun `a redacted block is one empty signed event and replays as an empty signed part`() = runTest {
        val redacted = providerPayloadOf(ANTHROPIC_PROVIDER_ID, ProviderPayloadKeys.REDACTED to "EroBCkYIBRgC")
        val model = Scripted(
            listOf(
                listOf(
                    StreamPart.ReasoningStart("r"),
                    StreamPart.ReasoningEnd("r", providerMetadata = redacted),
                    StreamPart.TextDelta("t", "ok"),
                    finish(FinishReason.Unified.Stop),
                ),
                listOf(finish(FinishReason.Unified.Stop)),
            ),
        )
        val chat = session(model)

        val events = chat.send(AideMessage.user("go"), ctx).toList()

        val thinking = events.filterIsInstance<ChatStreamEvent.ThinkingDelta>().single()
        assertEquals("", thinking.text)
        assertEquals(redacted, thinking.providerMetadata)

        chat.send(AideMessage.user("more"), ctx).toList()
        val assistant = model.lastOptions!!.prompt.filterIsInstance<ModelMessage.Assistant>().first()
        val reasoning = assistant.content.first() as AssistantPart.Reasoning
        assertEquals("", reasoning.text)
        assertEquals(redacted, reasoning.providerOptions)
    }

    @Test
    fun `an unsigned block emits no closing event and replays without a payload`() = runTest {
        val model = Scripted(
            listOf(
                listOf(
                    StreamPart.ReasoningStart("r"),
                    StreamPart.ReasoningDelta("r", "open trace"),
                    StreamPart.ReasoningEnd("r"),
                    StreamPart.TextDelta("t", "ok"),
                    finish(FinishReason.Unified.Stop),
                ),
                listOf(finish(FinishReason.Unified.Stop)),
            ),
        )
        val chat = session(model)

        val events = chat.send(AideMessage.user("go"), ctx).toList()

        // A spurious empty event would make the accumulator open a second part.
        val thinking = events.filterIsInstance<ChatStreamEvent.ThinkingDelta>()
        assertEquals(1, thinking.size)
        assertEquals("open trace", thinking.single().text)

        chat.send(AideMessage.user("more"), ctx).toList()
        val reasoning = model.lastOptions!!.prompt.filterIsInstance<ModelMessage.Assistant>().first()
            .content.first() as AssistantPart.Reasoning
        assertNull(reasoning.providerOptions)
    }

    @Test
    fun `a signed call carries its payload on the started event`() = runTest {
        val payload = providerPayloadOf("google", ProviderPayloadKeys.THOUGHT_SIGNATURE to "CikBhTnM")
        val model = Scripted(
            listOf(
                listOf(
                    StreamPart.ToolInputStart("c1", "echo"),
                    StreamPart.ToolInputDelta("c1", """{"q":"""),
                    StreamPart.ToolInputDelta("c1", """"x"}"""),
                    StreamPart.ToolInputEnd("c1"),
                    StreamPart.ToolCallPart(Content.ToolCall("c1", "echo", """{"q":"x"}""", providerMetadata = payload)),
                    finish(FinishReason.Unified.ToolCalls),
                ),
                listOf(finish(FinishReason.Unified.Stop)),
            ),
        )

        val events = session(model, listOf(tool("echo"))).send(AideMessage.user("go"), ctx).toList()

        // The partial ToolInput* parts are ignored: one started event, with the arguments once.
        val started = events.filterIsInstance<ChatStreamEvent.ToolCallStarted>().single()
        assertEquals(buildJsonObject { put("q", "x") }, started.args)
        assertEquals(payload, started.providerMetadata)
    }
}
