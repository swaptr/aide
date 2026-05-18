package com.swaptr.aide.domain.llm

import com.swaptr.aide.data.catalog.Accelerator
import com.swaptr.aide.data.catalog.ModelSpec
import com.swaptr.aide.data.chat.AideMessage
import com.swaptr.aide.domain.llm.dispatch.ToolDispatcher
import kotlinx.coroutines.flow.Flow

interface LlmEngine : AutoCloseable {
    val loadedModelId: String?

    val loadedAccelerator: Accelerator? get() = null

    suspend fun load(spec: ModelSpec, config: GenerationConfig)

    fun generate(prompt: String, config: GenerationConfig = GenerationConfig()): Flow<String>

    fun newChatSession(
        initialMessages: List<AideMessage> = emptyList(),
        tools: List<AideTool> = emptyList(),
        systemInstruction: String? = null,
        config: GenerationConfig = GenerationConfig(),
        dispatcher: ToolDispatcher,
        activationState: ToolActivationState? = null,
    ): ChatSession

    override fun close()
}
