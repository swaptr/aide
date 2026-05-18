package com.swaptr.aide.domain.llm

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
import com.swaptr.aide.data.catalog.ModelDefaultConfig
import com.swaptr.aide.data.chat.AideMessage
import com.swaptr.aide.data.chat.textContent
import com.swaptr.aide.data.chat.toLiteRt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalApi::class)
class LiteRtLmChatSession(
    private val engine: Engine,
    initialMessages: List<AideMessage>,
    private val tools: List<AideTool> = emptyList(),
    systemInstruction: String? = null,
    private val dispatcher: com.swaptr.aide.domain.llm.dispatch.ToolDispatcher,
    private val thinkingEnabled: Boolean = false,
    private val samplerDefaults: ModelDefaultConfig? = null,
    // NPU/TPU drivers reject SamplerConfig — caller must null it on those backends.
    private val primaryBackendIsNpu: Boolean = false,
    // Adds per-token validation overhead; only enable when caller passes a constrained schema.
    private val enableConversationConstrainedDecoding: Boolean = false,
) : ChatSession {

    // 0.10's ConversationConfig has no extraContext field — pass per-send via third arg.
    private val sendExtraContext: Map<String, Any> =
        if (thinkingEnabled) mapOf("enable_thinking" to true) else emptyMap()

    private val systemInstructionContents: Contents? = systemInstruction?.let { Contents.of(it) }
    // History replayed via sendMessageAsync on first send (gallery parity); not via ConversationConfig.
    private val pendingInitialMessages: List<Message> = initialMessages.map { it.toLiteRt() }
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
                            topK = samplerDefaults?.topK ?: 40,
                            topP = samplerDefaults?.topP?.toDouble() ?: 0.95,
                            temperature = samplerDefaults?.temperature?.toDouble() ?: 1.0,
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
        dispatchContext: com.swaptr.aide.domain.llm.dispatch.ToolDispatcher.Context,
    ): Flow<ChatStreamEvent> = flow {
        val c = conversation.get() ?: throw IllegalStateException("Session closed")
        var stopReason = ChatStreamEvent.StopReason.EndTurn
        var callCounter = 0
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
            runCatching { emit(ChatStreamEvent.Completed(stopReason)) }
        }
    }.flowOn(Dispatchers.IO)

    override fun reset() {
        // Reset = clean slate. Skip history replay on the fresh conversation.
        initialMessagesReplayed.set(true)
        val fresh = createConversation()
        val old = conversation.getAndSet(fresh)
        runCatching { old?.close() }
    }

    override fun cancel() {
        runCatching { conversation.get()?.cancelProcess() }
    }

    override fun close() {
        val old = conversation.getAndSet(null) ?: return
        runCatching { old.close() }
    }

    private fun Map<String, Any?>.toJsonObject(): JsonObject {
        val element = (this as Any?).toJsonElement()
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
