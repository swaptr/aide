package com.sabreware.aide.core.domain.llm

import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher
import com.sabreware.aide.core.domain.model.Accelerator
import com.sabreware.aide.core.domain.model.ChatModelSpec
import kotlinx.coroutines.flow.Flow

interface LlmEngine {
    val loadedModelId: String?

    val loadedAccelerator: Accelerator? get() = null

    suspend fun load(spec: ChatModelSpec, config: ChatGenerationConfig)

    fun generate(prompt: String, config: ChatGenerationConfig = ChatGenerationConfig()): Flow<String>

    fun newChatSession(
        initialMessages: List<AideMessage> = emptyList(),
        tools: List<AideTool> = emptyList(),
        systemInstruction: String? = null,
        config: ChatGenerationConfig = ChatGenerationConfig(),
        dispatcher: ToolDispatcher,
        activationState: ToolActivationState? = null,
    ): ChatSession

    /**
     * Free the model's weights. **Suspending on purpose**: an on-device engine hands out native handles a
     * turn is still streaming from, so the free has to wait for the turns in flight rather than race them.
     * It is also what lets [load] reclaim the previous model's RAM before allocating the next one.
     */
    suspend fun close()
}
