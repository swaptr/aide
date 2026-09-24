package com.sabreware.aide.app.llm
import com.sabreware.aide.core.domain.engine.NativeTurnGate
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
    // Owns every conversation of [engine]; opening and freeing go through it. See [HandleLedger].
    private val ledger: HandleLedger<Conversation>,
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

    // Set when a turn ends any way but its natural end: LiteRT-LM documents a session as poisoned after
    // `CancelProcess()` (session_advanced.h, "neither recommended nor supported"), and a failed decode leaves
    // the KV cache mid-turn. The owner rebuilds from the transcript instead of sending into it.
    private val broken = AtomicBoolean(false)

    // Set by [cancel] so a turn that LiteRT ends quietly after a cancel is still recorded as cancelled.
    private val cancelRequested = AtomicBoolean(false)


    override val reusable: Boolean
        get() = !broken.get() && !ledger.isClosed && conversation.get() != null

    // Outlives the caller of close()/reset() on purpose — see [freeWhenIdle]. SupervisorJob so one failed
    // free never cancels the next.
    private val freeScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // Single builder so init + reset share the exact same config. The flag is process-global and read at
    // construction, so set-create-reset is one step under a process-wide lock: two sessions built at once on
    // different threads could otherwise each see the other's value. try/finally keeps it from leaking on throw.
    private fun createConversation(): Conversation = synchronized(EXPERIMENTAL_FLAGS_LOCK) {
        ExperimentalFlags.enableConversationConstrainedDecoding = enableConversationConstrainedDecoding
        try {
            ledger.open {
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
            }
        } finally {
            ExperimentalFlags.enableConversationConstrainedDecoding = false
        }
    }

    override fun send(
        userMessage: AideMessage,
        dispatchContext: com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher.Context,
    ): Flow<ChatStreamEvent> = flow {
        var stopReason = ChatStreamEvent.StopReason.EndTurn
        var callCounter = 0
        cancelRequested.set(false)

        // Every NATIVE call runs inside the gate, and only those: reset()/close()/the engine's own close free
        // handles from other threads, and the gate keeps each free off a running decode. Tool dispatch runs
        // between them, outside it, because it can wait on the user (a write confirmation): holding the gate
        // there stalled every model switch and speech load until the dialog was answered. The handle is read
        // inside each hold, where no engine close can be running; a close that landed between two rounds is
        // then seen as a closed engine, never used.
        suspend fun <T> native(block: suspend (Conversation) -> T): T = turnGate.turn {
            check(!ledger.isClosed) { "Engine closed" }
            block(conversation.get() ?: throw IllegalStateException("Session closed"))
        }

        try {
            // Replay history through sendMessageAsync before the new user turn.
            if (initialMessagesReplayed.compareAndSet(false, true)) {
                native { c ->
                    for (priorMessage in pendingInitialMessages) {
                        c.settledStream { sendMessageAsync(priorMessage, it, sendExtraContext) }.collect { }
                    }
                }
            }
            var nextMessage: Message = userMessage.toLiteRt()
            loop@ while (true) {
                val pendingToolCalls = mutableListOf<com.google.ai.edge.litertlm.ToolCall>()
                val outgoing = nextMessage
                native { c ->
                    c.settledStream { sendMessageAsync(outgoing, it, sendExtraContext) }.collect { message ->
                        val thinking = message.channels[THINKING_CHANNEL]
                        if (!thinking.isNullOrEmpty()) emit(ChatStreamEvent.ThinkingDelta(thinking))
                        val text = message.textContent()
                        if (text.isNotEmpty()) emit(ChatStreamEvent.TextDelta(text))
                        val calls = message.toolCalls
                        if (calls.isNotEmpty()) pendingToolCalls += calls
                    }
                }
                // A Stop lands in LiteRT as a quiet end of stream: no more tool rounds after it.
                if (cancelRequested.get()) {
                    stopReason = ChatStreamEvent.StopReason.Cancelled
                    break@loop
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
            if (stopReason != ChatStreamEvent.StopReason.EndTurn) broken.set(true)
            runCatching { emit(ChatStreamEvent.Completed(stopReason, warnings = configWarnings)) }
        }
    }.flowOn(ioDispatcher)

    override fun reset() {
        // A session whose engine was closed has nothing to open a conversation on: the owner rebuilds it.
        if (ledger.isClosed) return
        // Reset = clean slate. Skip history replay on the fresh conversation.
        initialMessagesReplayed.set(true)
        val fresh = createConversation()
        conversation.getAndSet(fresh)?.let(::freeWhenIdle)
        broken.set(false)
    }

    override fun cancel() {
        // Deliberately NOT gated: cancelProcess is LiteRT's "stop generating" and is meant to be called
        // from another thread while a turn is running. It signals; it does not free. Cancelling the turn's
        // coroutine reaches the same call through [settledStream]; this is the path for a caller that
        // stops generation without owning the collector.
        cancelRequested.set(true)
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
            // Through the ledger: if the engine's close got here first it already freed this conversation, and
            // closing it again would touch freed memory.
            turnGate.drain { ledger.free(old) }
        }
    }

    private fun Map<String, Any?>.toJsonObject(): JsonObject {
        val element = (this as? Any).toJsonElement()
        return element as? JsonObject ?: JsonObject(emptyMap())
    }

    companion object {
        // Engine auto-exposes Gemma's thought channel under this exact key — must match.
        private const val THINKING_CHANNEL = "thought"

        // Guards LiteRT's process-global ExperimentalFlags across every session in the process.
        private val EXPERIMENTAL_FLAGS_LOCK = Any()
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
