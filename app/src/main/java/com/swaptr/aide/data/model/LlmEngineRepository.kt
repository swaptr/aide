package com.swaptr.aide.data.model

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.swaptr.aide.data.catalog.Accelerator
import com.swaptr.aide.data.catalog.ModelBackend
import com.swaptr.aide.data.catalog.ModelSpec
import com.swaptr.aide.data.catalog.ProviderId
import com.swaptr.aide.data.chat.AideMessage
import com.swaptr.aide.domain.llm.AideTool
import com.swaptr.aide.domain.llm.ChatSession
import com.swaptr.aide.domain.llm.GenerationConfig
import com.swaptr.aide.domain.llm.LlmEngine
import com.swaptr.aide.domain.llm.Provider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

// Only one engine may have a resident model at a time; switching providers unloads first
// so we never hold two sets of native weights in RAM.
@Singleton
class LlmEngineRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val providers: Map<ProviderId, @JvmSuppressWildcards Provider>,
) {
    /** Internal engine view; preserved for the lifecycle/loading code paths. */
    private val engines: Map<ProviderId, LlmEngine> = providers.mapValues { it.value.engine }

    fun providerFor(id: ProviderId): Provider = providers[id]
        ?: throw IllegalStateException("No provider registered for $id")

    // Once-per-landed-model hydration so a 50-tag library doesn't fan out N /api/show on refresh.
    suspend fun hydrateSpec(spec: ModelSpec) {
        runCatching { providers[spec.provider]?.management?.hydrateSpec(spec.id) }
    }

    fun allProviders(): Collection<Provider> = providers.values

    private val _loadedModelId = MutableStateFlow(currentLoadedId())
    val loadedModelIdFlow: StateFlow<String?> = _loadedModelId.asStateFlow()

    val loadedModelId: String? get() = currentLoadedId()

    private val _loadedAccelerator = MutableStateFlow(currentLoadedAccelerator())
    val loadedAcceleratorFlow: StateFlow<Accelerator?> = _loadedAccelerator.asStateFlow()

    // Serialises load/unload across the app — release mustn't race a mid-native-init load.
    val lifecycleLock: Mutex = Mutex()

    suspend fun ensureLoaded(spec: ModelSpec, config: GenerationConfig? = null) {
        val target = engineFor(spec)
        if (target.loadedModelId == spec.id) return
        loadInternal(target, spec, config ?: GenerationConfig(backend = spec.defaultBackend))
    }

    suspend fun load(spec: ModelSpec, config: GenerationConfig? = null) {
        val target = engineFor(spec)
        loadInternal(target, spec, config ?: GenerationConfig(backend = spec.defaultBackend))
    }

    // GPU init can fail invisibly on older Mali drivers until first inference — do the
    // fallback at load time so users never get a half-initialised engine.
    private suspend fun loadInternal(target: LlmEngine, spec: ModelSpec, config: GenerationConfig) {
        for ((id, engine) in engines) {
            if (id != spec.provider && engine.loadedModelId != null) {
                runCatching { engine.close() }
            }
        }
        // Pre-load memory trim — give lmkd room before the 3-4 GB model weights land in RAM.
        // Pixel 6a (6 GB) observed lmkd cascade-kill aide mid-load. AideApp's onTrimMemory
        // override dispatches to ResidentModelGuardRegistry which drops resident state.
        runCatching {
            (appContext.applicationContext as? Application)
                ?.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        }
        System.gc()
        try {
            target.load(spec, config)
        } catch (t: Throwable) {
            if (spec.provider == ProviderId.LOCAL && config.backend == ModelBackend.GPU) {
                Log.w(TAG, "GPU load failed for ${spec.id}, falling back to CPU", t)
                target.load(spec, config.copy(backend = ModelBackend.CPU))
            } else throw t
        }
        _loadedModelId.value = currentLoadedId()
        _loadedAccelerator.value = currentLoadedAccelerator()
    }

    fun unload() {
        engines.values.forEach { runCatching { it.close() } }
        _loadedModelId.value = null
        _loadedAccelerator.value = null
    }

    fun newChatSession(
        spec: ModelSpec,
        initialMessages: List<AideMessage> = emptyList(),
        tools: List<AideTool> = emptyList(),
        systemInstruction: String? = null,
        config: GenerationConfig = GenerationConfig(),
        dispatcher: com.swaptr.aide.domain.llm.dispatch.ToolDispatcher,
        activationState: com.swaptr.aide.domain.llm.ToolActivationState? = null,
    ): ChatSession = engineFor(spec).newChatSession(
        initialMessages, tools, systemInstruction, config, dispatcher, activationState,
    )

    // Emits incremental deltas (not accumulated string) — caller appends each chunk.
    fun engineGenerate(
        prompt: String,
        config: GenerationConfig = GenerationConfig(),
    ): Flow<String> = activeEngine().generate(prompt, config)

    private fun engineFor(spec: ModelSpec): LlmEngine = engines[spec.provider]
        ?: throw IllegalStateException("No engine registered for provider=${spec.provider}")

    private fun activeEngine(): LlmEngine = engines.values.firstOrNull { it.loadedModelId != null }
        ?: throw IllegalStateException("No model loaded; call load() first")

    private fun currentLoadedId(): String? =
        engines.values.firstNotNullOfOrNull { it.loadedModelId }

    private fun currentLoadedAccelerator(): Accelerator? =
        engines.values.firstNotNullOfOrNull { it.loadedAccelerator }

    companion object {
        private const val TAG = "AideEngine"
    }
}
