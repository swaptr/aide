package com.swaptr.aide.domain.llm.ollama

import com.swaptr.aide.data.catalog.Accelerator
import com.swaptr.aide.data.catalog.ModelSpec
import com.swaptr.aide.data.chat.AideMessage
import com.swaptr.aide.domain.llm.AideTool
import com.swaptr.aide.domain.llm.ChatSession
import com.swaptr.aide.domain.llm.ChatStreamEvent
import com.swaptr.aide.domain.llm.GenerationConfig
import com.swaptr.aide.domain.llm.LlmEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.util.concurrent.atomic.AtomicReference

class OllamaLlmEngine(
    private val client: OllamaClient,
    private val dispatcher: com.swaptr.aide.domain.llm.dispatch.ToolDispatcher,
) : LlmEngine {

    private val state = AtomicReference<LoadedState?>(null)

    override val loadedModelId: String?
        get() = state.get()?.spec?.id

    override val loadedAccelerator: Accelerator? get() = null

    override suspend fun load(spec: ModelSpec, config: GenerationConfig) {
        val target = spec.remoteName ?: spec.id
        state.set(LoadedState(spec, target))
    }

    override fun generate(prompt: String, config: GenerationConfig): Flow<String> = channelFlow {
        val loaded = state.get() ?: throw IllegalStateException("No model loaded; call load() first")
        val session = OllamaChatSession(
            client = client,
            modelName = loaded.modelName,
            initialMessages = emptyList(),
            dispatcher = dispatcher,
        )
        session.send(
            AideMessage.user(prompt),
            com.swaptr.aide.domain.llm.dispatch.ToolDispatcher.Context(
                surface = com.swaptr.aide.domain.llm.Surface.CHAT,
                modelId = loaded.modelName,
                turnId = "ollama-generate-${System.nanoTime()}",
            ),
        ).collect { event ->
            if (event is ChatStreamEvent.TextDelta) send(event.text)
        }
    }

    override fun newChatSession(
        initialMessages: List<AideMessage>,
        tools: List<AideTool>,
        systemInstruction: String?,
        config: GenerationConfig,
        dispatcher: com.swaptr.aide.domain.llm.dispatch.ToolDispatcher,
        activationState: com.swaptr.aide.domain.llm.ToolActivationState?,
    ): ChatSession {
        val loaded = state.get() ?: throw IllegalStateException("No model loaded; call load() first")
        return OllamaChatSession(
            client = client,
            modelName = loaded.modelName,
            initialMessages = initialMessages,
            tools = tools,
            systemInstruction = systemInstruction,
            generationConfig = config,
            dispatcher = dispatcher,
            activationState = activationState,
        )
    }

    override fun close() {
        state.set(null)
    }

    private data class LoadedState(val spec: ModelSpec, val modelName: String)
}
