package com.sabreware.aide.data.llm.aisdk

import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.core.common.media.AttachmentBytesReader
import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.ChatGenerationConfig
import com.sabreware.aide.core.domain.llm.ChatSession
import com.sabreware.aide.core.domain.llm.ChatStreamEvent
import com.sabreware.aide.core.domain.llm.LlmEngine
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.llm.ToolActivationState
import com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher
import com.sabreware.aide.core.domain.model.ChatModelSpec
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.datetime.Clock

/**
 * The chat engine for every `:aisdk`-backed provider.
 *
 * One engine, one session, any vendor: this needs a [Provider] and a model id, and the vendor
 * differences live inside the provider, which is where they belong. A new provider `:aisdk` gains
 * reaches AIDE through this class with no change here.
 *
 * `load()` records the resolved model id and nothing else; there are no weights to load. Every turn runs
 * through an [AiSdkChatSession], which hands the loop to `:aisdk:runtime` and keeps AIDE's tool
 * dispatch and history.
 *
 * [providerFactory] is a lambda rather than a [Provider] because credentials change while the app runs —
 * a key pasted into Settings has to take effect on the next turn, not the next launch.
 */
public class AiSdkLlmEngine(
    private val providerFactory: () -> Provider?,
    private val logTag: String,
    private val dispatcher: ToolDispatcher,
    private val readBytes: AttachmentBytesReader = AttachmentBytesReader { null },
) : LlmEngine {

    private val state = atomic<LoadedState?>(null)

    override val loadedModelId: String? get() = state.value?.spec?.id

    override suspend fun load(spec: ChatModelSpec, config: ChatGenerationConfig) {
        state.value = LoadedState(spec, spec.remoteName ?: spec.id)
    }

    override fun generate(prompt: String, config: ChatGenerationConfig): Flow<String> = channelFlow {
        val session = newSession(emptyList(), emptyList(), null, config, dispatcher, null)
        session.send(
            AideMessage.user(prompt),
            ToolDispatcher.Context(
                surface = Surface.CHAT,
                modelId = requireLoaded().modelName,
                turnId = "$logTag-generate-${Clock.System.now().toEpochMilliseconds()}",
            ),
        ).collect { event ->
            if (event is ChatStreamEvent.TextDelta) send(event.text)
        }
    }

    override fun newChatSession(
        initialMessages: List<AideMessage>,
        tools: List<AideTool>,
        systemInstruction: String?,
        config: ChatGenerationConfig,
        dispatcher: ToolDispatcher,
        activationState: ToolActivationState?,
    ): ChatSession = newSession(initialMessages, tools, systemInstruction, config, dispatcher, activationState)

    private fun newSession(
        initialMessages: List<AideMessage>,
        tools: List<AideTool>,
        systemInstruction: String?,
        config: ChatGenerationConfig,
        dispatcher: ToolDispatcher,
        activationState: ToolActivationState?,
    ): ChatSession {
        val loaded = requireLoaded()
        val provider = providerFactory()
            ?: throw IllegalStateException("Provider not configured (no base URL or key)")
        val model = provider.languageModel(loaded.modelName)
            ?: throw IllegalStateException("${provider.providerId} serves no language models")

        return AiSdkChatSession(
            model = model,
            tools = tools,
            config = config,
            activationState = activationState,
            // From the model's own capabilities rather than a name-matching guess, so a model the
            // catalog knows rejects `temperature` never has it sent.
            disabledParams = loaded.spec.capabilities.disabledParams.map { it.wireName() }.toSet(),
            initialMessages = initialMessages,
            systemInstruction = systemInstruction,
            dispatcher = dispatcher,
            readBytes = readBytes,
            logTag = logTag,
        )
    }

    override suspend fun close() {
        state.value = null
    }

    private fun requireLoaded(): LoadedState =
        state.value ?: throw IllegalStateException("No model loaded; call load() first")

    private data class LoadedState(val spec: ChatModelSpec, val modelName: String)
}
