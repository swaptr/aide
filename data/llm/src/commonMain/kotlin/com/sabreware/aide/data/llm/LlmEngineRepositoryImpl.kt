package com.sabreware.aide.data.llm

import com.sabreware.aide.core.common.coroutines.runSuspendCatching
import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.ChatGenerationConfig
import com.sabreware.aide.core.domain.llm.ChatProviderRegistry
import com.sabreware.aide.core.domain.llm.ChatSession
import com.sabreware.aide.core.domain.llm.LlmEngine
import com.sabreware.aide.core.domain.llm.LlmEngineRepository
import com.sabreware.aide.core.domain.llm.ManageableRegistry
import com.sabreware.aide.core.domain.llm.ToolActivationState
import com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher
import com.sabreware.aide.core.domain.model.Accelerator
import com.sabreware.aide.core.domain.model.ChatModelSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one [LlmEngineRepository]: a router from `spec.provider` to the contributing provider's engine, with
 * the single-resident load lifecycle over them. Only one engine may hold a resident model at a time, so
 * switching providers unloads first and we never hold two sets of native weights in RAM.
 *
 * Platform variance is the injected [EngineLoadPolicy] — nothing else differed between the android and
 * desktop copies this replaced.
 */
class LlmEngineRepositoryImpl(
    private val chatProviders: ChatProviderRegistry,
    private val manageables: ManageableRegistry,
    private val loadPolicy: EngineLoadPolicy,
    scope: CoroutineScope,
) : LlmEngineRepository {

    /**
     * Chat engines — the single-resident lifecycle runs over exactly these. Read per call: the set is the
     * on-device engine plus one per connection, and connections come and go while the app runs.
     */
    private val engines: Collection<LlmEngine> get() = chatProviders.all.map { it.chat }

    // Once-per-landed-model hydration so a 50-tag library doesn't fan out N /api/show on refresh.
    override suspend fun hydrateSpec(spec: ChatModelSpec) {
        runSuspendCatching { manageables.await(spec.provider)?.management?.hydrateSpec(spec.id) }
    }

    private val _loadedModelId = MutableStateFlow(currentLoadedId())
    override val loadedModelIdFlow: StateFlow<String?> = _loadedModelId.asStateFlow()

    override val loadedModelId: String? get() = currentLoadedId()

    private val _loadedAccelerator = MutableStateFlow(currentLoadedAccelerator())
    override val loadedAcceleratorFlow: StateFlow<Accelerator?> = _loadedAccelerator.asStateFlow()

    // Serialises load/unload across the app — release mustn't race a mid-native-init load.
    private val lifecycleLock: Mutex = Mutex()

    // After every property it touches: the collector may run as soon as it is launched.
    init {
        // A connection removed while its model is loaded takes its engine with it: close what disappeared so
        // nothing keeps reporting a model no registry can reach, and republish what is loaded now.
        scope.launch {
            var previous: Set<LlmEngine> = emptySet()
            chatProviders.flow.filterNotNull().collect { providers ->
                val current = providers.mapTo(HashSet()) { it.chat }
                val gone = previous - current
                previous = current
                if (gone.isNotEmpty()) {
                    withLifecycleLock { gone.forEach { runSuspendCatching { it.close() } } }
                    _loadedModelId.value = currentLoadedId()
                    _loadedAccelerator.value = currentLoadedAccelerator()
                }
            }
        }
    }

    override suspend fun <T> withLifecycleLock(block: suspend () -> T): T =
        lifecycleLock.withLock { block() }

    override suspend fun ensureLoaded(spec: ChatModelSpec, config: ChatGenerationConfig?) {
        val target = awaitEngine(spec)
        if (target.loadedModelId == spec.id) return
        loadInternal(target, spec, config)
    }

    override suspend fun load(spec: ChatModelSpec, config: ChatGenerationConfig?) {
        loadInternal(awaitEngine(spec), spec, config)
    }

    private suspend fun loadInternal(target: LlmEngine, spec: ChatModelSpec, config: ChatGenerationConfig?) {
        // Cross-provider eviction is the ResidencyManager's job: a model with a held handle is never closed,
        // and unheld ones idle-release on their keepAlive. Same-provider replacement still happens inside the
        // engine's own load() (it closes the prior model first).
        loadPolicy.load(target, spec, config ?: ChatGenerationConfig(backend = spec.defaultBackend))
        _loadedModelId.value = currentLoadedId()
        _loadedAccelerator.value = currentLoadedAccelerator()
    }

    override suspend fun unload(modelId: String) {
        engines.filter { it.loadedModelId == modelId }.forEach { runSuspendCatching { it.close() } }
        _loadedModelId.value = currentLoadedId()
        _loadedAccelerator.value = currentLoadedAccelerator()
    }

    override fun isLoaded(spec: ChatModelSpec): Boolean = chatProviders[spec.provider]?.chat?.loadedModelId == spec.id

    override fun newChatSession(
        spec: ChatModelSpec,
        initialMessages: List<AideMessage>,
        tools: List<AideTool>,
        systemInstruction: String?,
        config: ChatGenerationConfig,
        dispatcher: ToolDispatcher,
        activationState: ToolActivationState?,
    ): ChatSession = engineFor(spec).newChatSession(
        initialMessages, tools, systemInstruction, config, dispatcher, activationState,
    )

    // Emits incremental deltas (not accumulated string) — caller appends each chunk.
    override fun engineGenerate(
        prompt: String,
        config: ChatGenerationConfig,
    ): Flow<String> = activeEngine().generate(prompt, config)

    // Load paths wait for the connections to be read: a send on a cold start must not fail for a connection
    // that is simply not read yet. A session is only opened after a load, so its lookup can be immediate.
    private suspend fun awaitEngine(spec: ChatModelSpec): LlmEngine = chatProviders.await(spec.provider)?.chat
        ?: throw IllegalStateException("No engine for provider=${spec.provider.value} (its connection is gone)")

    private fun engineFor(spec: ChatModelSpec): LlmEngine = chatProviders[spec.provider]?.chat
        ?: throw IllegalStateException("No engine for provider=${spec.provider.value} (its connection is gone)")

    private fun activeEngine(): LlmEngine = engines.firstOrNull { it.loadedModelId != null }
        ?: throw IllegalStateException("No model loaded; call load() first")

    private fun currentLoadedId(): String? =
        engines.firstNotNullOfOrNull { it.loadedModelId }

    private fun currentLoadedAccelerator(): Accelerator? =
        engines.firstNotNullOfOrNull { it.loadedAccelerator }
}
