package com.swaptr.aide.domain.llm.ollama

import android.util.Log
import com.swaptr.aide.data.chat.AideMessage
import com.swaptr.aide.data.chat.AidePart
import com.swaptr.aide.data.chat.AideRole
import com.swaptr.aide.data.chat.toOllama
import com.swaptr.aide.domain.llm.AideTool
import com.swaptr.aide.domain.llm.ChatSession
import com.swaptr.aide.domain.llm.ChatStreamEvent
import com.swaptr.aide.domain.llm.GenerationConfig
import com.swaptr.aide.domain.llm.toJsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class OllamaChatSession(
    private val client: OllamaClient,
    private val modelName: String,
    initialMessages: List<AideMessage>,
    private val tools: List<AideTool> = emptyList(),
    systemInstruction: String? = null,
    private val generationConfig: GenerationConfig = GenerationConfig(),
    private val dispatcher: com.swaptr.aide.domain.llm.dispatch.ToolDispatcher,
    private val activationState: com.swaptr.aide.domain.llm.ToolActivationState? = null,
) : ChatSession {

    private val history: MutableList<AideMessage> = mutableListOf<AideMessage>().apply {
        if (!systemInstruction.isNullOrBlank()) {
            add(AideMessage.system(systemInstruction))
        }
        addAll(initialMessages)
    }

    override fun send(
        userMessage: AideMessage,
        dispatchContext: com.swaptr.aide.domain.llm.dispatch.ToolDispatcher.Context,
    ): Flow<ChatStreamEvent> = channelFlow {
        history += userMessage

        var assistantBuffer = StringBuilder()
        val pendingToolCalls = mutableListOf<OllamaToolCall>()
        var stopReason: ChatStreamEvent.StopReason = ChatStreamEvent.StopReason.EndTurn
        var callCounter = 0
        var finished = false
        val turnStartNs = System.nanoTime()
        var roundIndex = 0
        var firstTokenLogged = false

        try {
            outer@ while (!finished) {
                pendingToolCalls.clear()
                assistantBuffer = StringBuilder()
                val thinkingByteBudget = intArrayOf(0)
                val roundStartNs = System.nanoTime()
                roundIndex++

                val request = OllamaChatRequest(
                    model = modelName,
                    messages = history.map { it.toOllama() },
                    stream = true,
                    tools = tools.toWireDefs(),
                    options = generationConfig.toOptionsJson(),
                    think = generationConfig.thinking.toWire(),
                    format = generationConfig.responseSchema,
                )
                Log.i(
                    "AidePerf",
                    "ollama.session round=$roundIndex model=$modelName " +
                        "historyMsgs=${history.size} tools=${request.tools?.size ?: 0} " +
                        "think=${request.think}",
                )

                client.chat(request).collect { chunk ->
                    if (chunk.error != null) {
                        throw java.io.IOException(chunk.error)
                    }
                    val msg = chunk.message
                    if (msg != null) {
                        val toolCalls = msg.toolCalls
                        if (!toolCalls.isNullOrEmpty()) {
                            pendingToolCalls.addAll(toolCalls)
                        }
                        if (msg.thinking.isNotEmpty()) {
                            if (!firstTokenLogged) {
                                Log.i(
                                    "AidePerf",
                                    "ollama.session round=$roundIndex firstThinkingMs=" +
                                        "${(System.nanoTime() - roundStartNs) / 1_000_000}",
                                )
                                firstTokenLogged = true
                            }
                            thinkingByteBudget[0] += msg.thinking.length
                            send(ChatStreamEvent.ThinkingDelta(msg.thinking))
                        }
                        val content = msg.content
                        if (content.isNotEmpty() && toolCalls.isNullOrEmpty()) {
                            if (!firstTokenLogged) {
                                Log.i(
                                    "AidePerf",
                                    "ollama.session round=$roundIndex firstTextMs=" +
                                        "${(System.nanoTime() - roundStartNs) / 1_000_000}",
                                )
                                firstTokenLogged = true
                            }
                            assistantBuffer.append(content)
                            send(ChatStreamEvent.TextDelta(content))
                        }
                    }
                    if (chunk.done) {
                        Log.i(
                            "AidePerf",
                            "ollama.session round=$roundIndex done reason=${chunk.doneReason} " +
                                "roundMs=${(System.nanoTime() - roundStartNs) / 1_000_000} " +
                                "answerChars=${assistantBuffer.length} thinkingChars=${thinkingByteBudget[0]} " +
                                "pendingToolCalls=${pendingToolCalls.size}",
                        )
                        if (pendingToolCalls.isNotEmpty()) {
                            val callRecords = mutableListOf<AidePart.ToolCall>()
                            val resultRecords = mutableListOf<Pair<String, AidePart.ToolResponse>>()

                            for (call in pendingToolCalls) {
                                val name = call.function.name
                                val callId = "ollama-tc-${++callCounter}"
                                val tool = tools.firstOrNull { it.name == name } as? AideTool.Function
                                val argsJson = call.function.arguments
                                val args = if (argsJson is JsonObject) argsJson
                                else runCatching { argsJson.jsonObject }.getOrNull()
                                    ?: JsonObject(emptyMap())

                                callRecords += AidePart.ToolCall(
                                    callId = callId,
                                    name = name,
                                    argsJson = args.toString(),
                                )

                                send(ChatStreamEvent.ToolCallStarted(callId, name, args))

                                val dispatchStartNs = System.nanoTime()
                                val envelope = dispatcher.dispatch(tool, name, args, dispatchContext)
                                Log.i(
                                    "AidePerf",
                                    "ollama.session tool=$name " +
                                        "dispatchMs=${(System.nanoTime() - dispatchStartNs) / 1_000_000}",
                                )
                                val errorMessage = (envelope["errorCode"] as? JsonPrimitive)?.content
                                val resultJson = envelope.toString()
                                send(ChatStreamEvent.ToolCallCompleted(callId, name, resultJson, errorMessage))

                                resultRecords += callId to AidePart.ToolResponse(
                                    name = name,
                                    json = resultJson,
                                    callId = callId,
                                    error = errorMessage,
                                )
                            }

                            history += AideMessage(
                                role = AideRole.Model,
                                parts = buildList {
                                    if (assistantBuffer.isNotEmpty()) {
                                        add(AidePart.Text(assistantBuffer.toString()))
                                    }
                                    addAll(callRecords)
                                },
                            )
                            for ((_, response) in resultRecords) {
                                history += AideMessage(
                                    role = AideRole.Tool,
                                    parts = listOf(response),
                                )
                            }
                            return@collect
                        } else {
                            history += AideMessage(
                                role = AideRole.Model,
                                parts = listOf(AidePart.Text(assistantBuffer.toString())),
                            )
                            stopReason = chunk.doneReason.toStopReason()
                            finished = true
                            return@collect
                        }
                    }
                }
                if (pendingToolCalls.isEmpty()) break@outer
            }
            Log.i(
                "AidePerf",
                "ollama.session turn complete rounds=$roundIndex " +
                    "totalMs=${(System.nanoTime() - turnStartNs) / 1_000_000} " +
                    "finalHistoryMsgs=${history.size}",
            )
            send(ChatStreamEvent.Completed(stopReason))
        } catch (ce: CancellationException) {
            Log.i(
                "AidePerf",
                "ollama.session turn cancelled rounds=$roundIndex " +
                    "totalMs=${(System.nanoTime() - turnStartNs) / 1_000_000}",
            )
            runCatching { send(ChatStreamEvent.Completed(ChatStreamEvent.StopReason.Cancelled)) }
            throw ce
        } catch (t: Throwable) {
            Log.w(
                "AidePerf",
                "ollama.session turn error rounds=$roundIndex " +
                    "totalMs=${(System.nanoTime() - turnStartNs) / 1_000_000}: " +
                    "${t.javaClass.simpleName}: ${t.message}",
                t,
            )
            send(ChatStreamEvent.Completed(ChatStreamEvent.StopReason.Error))
            throw t
        }
    }.flowOn(Dispatchers.IO)

    override fun reset() {
        history.clear()
    }

    override fun cancel() {
        // Streams cancel via coroutine cancellation → channelFlow → OkHttp Call.cancel().
    }

    override fun close() {
        history.clear()
    }

    private fun List<AideTool>.toWireDefs(): List<OllamaToolDef>? {
        // ProviderNative bindings (Cloud web_search) hit a separate endpoint, not chat tools[].
        val activated = activationState?.activated.orEmpty()
        val fnTools = filterIsInstance<AideTool.Function>().filter { tool ->
            !tool.requiresActivation || (tool.category != null && tool.category in activated)
        }
        return fnTools.takeIf { it.isNotEmpty() }?.map { tool ->
            OllamaToolDef(
                function = OllamaToolFunction(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parametersSchema,
                ),
            )
        }
    }

    private fun String?.toStopReason(): ChatStreamEvent.StopReason = when (this) {
        "stop" -> ChatStreamEvent.StopReason.EndTurn
        "length" -> ChatStreamEvent.StopReason.MaxTokens
        "stop_sequence" -> ChatStreamEvent.StopReason.StopSequence
        else -> ChatStreamEvent.StopReason.EndTurn
    }

    private fun GenerationConfig.toOptionsJson(): JsonObject = buildJsonObject {
        put("temperature", temperature)
        put("top_p", topP)
        put("top_k", topK)
        put("num_predict", maxTokens)
        if (stopSequences.isNotEmpty()) {
            put("stop", JsonArray(stopSequences.map { JsonPrimitive(it) }))
        }
    }

    private fun GenerationConfig.ThinkingRequest.toWire(): JsonElement? = when (this) {
        GenerationConfig.ThinkingRequest.Off -> null
        GenerationConfig.ThinkingRequest.On -> JsonPrimitive(true)
        is GenerationConfig.ThinkingRequest.Level -> JsonPrimitive(level)
    }
}
