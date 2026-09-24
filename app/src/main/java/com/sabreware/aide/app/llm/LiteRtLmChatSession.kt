package com.sabreware.aide.app.llm
import com.sabreware.aide.data.llm.normalizeForWire
import com.sabreware.aide.core.common.di.IO
import com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher
import com.sabreware.aide.core.domain.llm.toJsonElement
import com.sabreware.aide.core.domain.llm.toAnyMap
import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.ChatStreamEvent
import com.sabreware.aide.core.domain.llm.ChatSession
import com.sabreware.aide.core.domain.llm.ModelWarning

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.sabreware.aide.core.domain.chat.AideMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalApi::class)
internal class LiteRtLmChatSession(
    private val engine: Engine,
    // Shared with the owning engine: a turn holds it open, a free waits on it. See [NativeTurnGate].
    private val turnGate: NativeTurnGate,
    initialMessages: List<AideMessage>,
    private val tools: List<AideTool> = emptyList(),
    systemInstruction: String? = null,
    private val dispatcher: com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher,
    private val thinkingEnabled: Boolean = false,
    // Resolved sampler (base ← model default ← user override) from the turn's ChatGenerationConfig.
    private val samplerTopK: Int = 40,
    private val samplerTopP: Double = 0.95,
    private val samplerTemperature: Double = 1.0,
    // NPU/TPU drivers reject SamplerConfig — caller must null it on those backends.
    private val primaryBackendIsNpu: Boolean = false,
    // Adds per-token validation overhead; only enable when caller passes a constrained schema.
    private val enableConversationConstrainedDecoding: Boolean = false,
    // Config knobs the engine couldn't honor for this turn — replayed on the terminal Completed.
    private val configWarnings: List<ModelWarning> = emptyList(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ChatSession {

    // 0.10's ConversationConfig has no extraContext field — pass per-send via third arg.
    private val sendExtraContext: Map<String, Any> =
        if (thinkingEnabled) mapOf("enable_thinking" to true) else emptyMap()

    private val systemInstructionContents: Contents? = systemInstruction?.let { Contents.of(it) }
    /**
     * The whole transcript, replayed through the model on the first send of every new session.
     *
     * This is a real cost and it grows with the conversation: recreating a session — app restart, model
     * switch, an engine reload after a memory trim — re-prefills everything said so far, so the longer
     * someone has been talking the slower it is to resume.
     *
     * LiteRT-LM (the C++ runtime) supports serialising a session's KV-cache state and restoring it, which
     * would replace this replay with a load. **The Android binding does not expose it.** Checked against
     * `litertlm-android` 0.11.0 (shipped) and 0.16.1 (latest at the time of writing): `Session` offers only
     * `runPrefill` / `runDecode` / `generateContent*` / `cancelProcess`, and no class in either artifact
     * declares a save, restore, serialize or snapshot method. So this stays a replay until the SDK surfaces
     * it — the seam is right, the capability simply is not reachable from Kotlin yet.
     */
    private val pendingInitialMessages: List<Message> =
        initialMessages.normalizeForWire().map { it.toLiteRt() }
    private val initialMessagesReplayed = AtomicBoolean(pendingInitialMessages.isEmpty())
    private val litertTools: List<ToolProvider> =
        tools.filterIsInstance<AideTool.Function>().map { tool(it.toOpenApi()) }

    init {
        // ProviderNative tools are server-executed — LiteRT can't run them.
        val dropped = tools.filterIsInstance<AideTool.ProviderNative>()
        if (dropped.isNotEmpty()) {
            android.util.Log.w(
                "LiteRtLmChatSession",
                "Dropping ${dropped.size} ProviderNative tool(s) — LiteRT runs locally: " +
                    dropped.joinToString(", ") { it.name },
            )
        }
    }

    private val conversation = AtomicReference<Conversation?>(createConversation())

    // Outlives the caller of close()/reset() on purpose — see [freeWhenIdle]. SupervisorJob so one failed
    // free never cancels the next.
    private val freeScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // Single builder so init + reset share the exact same config. try/finally guards the
    // process-global flag from leaking on throw.
    private fun createConversation(): Conversation {
        ExperimentalFlags.enableConversationConstrainedDecoding = enableConversationConstrainedDecoding
        return try {
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = systemInstructionContents,
                    tools = litertTools,
                    samplerConfig = if (primaryBackendIsNpu) {
                        null
                    } else {
                        SamplerConfig(
                            topK = samplerTopK,
                            topP = samplerTopP,
                            temperature = samplerTemperature,
                        )
                    },
                    // Forced false so every call routes through ToolDispatcher (write-gate,
                    // idempotency, rate limit). Native auto-exec would bypass all of them.
                    automaticToolCalling = false,
                ),
            )
        } finally {
            ExperimentalFlags.enableConversationConstrainedDecoding = false
        }
    }

    override fun send(
        userMessage: AideMessage,
        dispatchContext: com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher.Context,
    ): Flow<ChatStreamEvent> = flow {
        val c = conversation.get() ?: throw IllegalStateException("Session closed")
        var stopReason = ChatStreamEvent.StopReason.EndTurn
        var callCounter = 0
        // The whole turn runs inside the gate. `c` is a native handle captured for the turn's lifetime;
        // reset()/close()/the engine's own close all free handles like it, and without the gate they did so
        // from another thread while this loop was still streaming.
        turnGate.turn {
        try {
            // Replay history through sendMessageAsync before the new user turn.
            if (initialMessagesReplayed.compareAndSet(false, true)) {
                for (priorMessage in pendingInitialMessages) {
                    c.sendMessageAsync(priorMessage, sendExtraContext).collect { /* drain */ }
                }
            }
            var nextMessage: Message = userMessage.toLiteRt()
            loop@ while (true) {
                val pendingToolCalls = mutableListOf<com.google.ai.edge.litertlm.ToolCall>()
                c.sendMessageAsync(nextMessage, sendExtraContext).collect { message ->
                    val thinking = message.channels[THINKING_CHANNEL]
                    if (!thinking.isNullOrEmpty()) emit(ChatStreamEvent.ThinkingDelta(thinking))
                    val text = message.textContent()
                    if (text.isNotEmpty()) emit(ChatStreamEvent.TextDelta(text))
                    val calls = message.toolCalls
                    if (calls.isNotEmpty()) pendingToolCalls += calls
                }
                if (pendingToolCalls.isEmpty()) break@loop

                val responses = mutableListOf<Content>()
                for (call in pendingToolCalls) {
                    val name = call.name
                    val callId = "litert-tc-${++callCounter}"
                    val args = call.arguments.toJsonObject()
                    emit(ChatStreamEvent.ToolCallStarted(callId, name, args))

                    val fn = tools.filterIsInstance<AideTool.Function>()
                        .firstOrNull { it.name == name }
                    val envelope = dispatcher.dispatch(fn, name, args, dispatchContext)
                    val errorMessage = (envelope["errorCode"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.content
                    val resultJson = envelope.toString()
                    emit(ChatStreamEvent.ToolCallCompleted(callId, name, resultJson, errorMessage))
                    responses += Content.ToolResponse(name, envelope.toAnyMap())
                }
                nextMessage = Message.tool(Contents.of(responses))
            }
        } catch (ce: CancellationException) {
            stopReason = ChatStreamEvent.StopReason.Cancelled
            throw ce
        } catch (t: Throwable) {
            stopReason = ChatStreamEvent.StopReason.Error
            throw t
        } finally {
            runCatching { emit(ChatStreamEvent.Completed(stopReason, warnings = configWarnings)) }
        }
        }
    }.flowOn(ioDispatcher)

    override fun reset() {
        // Reset = clean slate. Skip history replay on the fresh conversation.
        initialMessagesReplayed.set(true)
        val fresh = createConversation()
        conversation.getAndSet(fresh)?.let(::freeWhenIdle)
    }

    override fun cancel() {
        // Deliberately NOT gated: cancelProcess is LiteRT's "stop generating" and is meant to be called
        // from another thread while a turn is running. It signals; it does not free.
        runCatching { conversation.get()?.cancelProcess() }
    }

    override fun close() {
        conversation.getAndSet(null)?.let(::freeWhenIdle)
    }

    /**
     * Swap-then-free. The reference is already gone by the time this runs, so no new turn can reach the
     * conversation; the drain then waits out whatever turn is still streaming from it.
     *
     * Asynchronous because [ChatSession.close] is not suspending — it is called from `onCleared` and from
     * the session-rebind path, neither of which has a coroutine to suspend in. What matters for correctness
     * is the ordering (free strictly after the last turn), not that the caller observes it.
     */
    private fun freeWhenIdle(old: Conversation) {
        freeScope.launch {
            turnGate.drain { runCatching { old.close() } }
        }
    }

    private fun Map<String, Any?>.toJsonObject(): JsonObject {
        val element = (this as? Any).toJsonElement()
        return element as? JsonObject ?: JsonObject(emptyMap())
    }

    companion object {
        // Engine auto-exposes Gemma's thought channel under this exact key — must match.
        private const val THINKING_CHANNEL = "thought"
    }

    private fun AideTool.Function.toOpenApi(): OpenApiTool {
        val descriptor = buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", parametersSchema)
        }.toString()
        return object : OpenApiTool {
            override fun getToolDescriptionJsonString(): String = descriptor
            // Should be unreachable: automaticToolCalling=false routes everything through ToolDispatcher.
            override fun execute(paramsJsonString: String): String {
                throw IllegalStateException("OpenApiTool.execute invoked for '$name' — manual dispatch only")
            }
        }
    }
}
