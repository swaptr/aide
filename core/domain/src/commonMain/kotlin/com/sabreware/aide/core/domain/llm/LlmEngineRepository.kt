package com.sabreware.aide.core.domain.llm

import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher
import com.sabreware.aide.core.domain.model.Accelerator
import com.sabreware.aide.core.domain.model.ChatModelSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain-facing engine lifecycle/session contract. Only one engine may hold a resident model at a
 * time; switching providers unloads first. Impl ([data.model.LlmEngineRepositoryImpl]) owns the
 * Android memory-trim + GPU→CPU fallback. Bound via Hilt `@Binds`.
 */
interface LlmEngineRepository {
    val loadedModelIdFlow: StateFlow<String?>
    val loadedModelId: String?
    val loadedAcceleratorFlow: StateFlow<Accelerator?>

    suspend fun hydrateSpec(spec: ChatModelSpec)

    /**
     * Serialises load/unload across the app so a release never races a mid-native-init load.
     * Replaces a leaked `Mutex` — the concurrency primitive stays out of the contract.
     */
    suspend fun <T> withLifecycleLock(block: suspend () -> T): T

    suspend fun ensureLoaded(spec: ChatModelSpec, config: ChatGenerationConfig? = null)
    suspend fun load(spec: ChatModelSpec, config: ChatGenerationConfig? = null)
    suspend fun unload()

    fun newChatSession(
        spec: ChatModelSpec,
        initialMessages: List<AideMessage> = emptyList(),
        tools: List<AideTool> = emptyList(),
        systemInstruction: String? = null,
        config: ChatGenerationConfig = ChatGenerationConfig(),
        dispatcher: ToolDispatcher,
        activationState: ToolActivationState? = null,
    ): ChatSession

    fun engineGenerate(prompt: String, config: ChatGenerationConfig = ChatGenerationConfig()): Flow<String>
}
