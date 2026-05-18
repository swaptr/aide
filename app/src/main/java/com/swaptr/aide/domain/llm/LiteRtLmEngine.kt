package com.swaptr.aide.domain.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.swaptr.aide.data.catalog.Accelerator
import com.swaptr.aide.data.catalog.ModelBackend
import com.swaptr.aide.data.catalog.ModelSpec
import com.swaptr.aide.data.chat.AideMessage
import com.swaptr.aide.data.chat.textContent
import com.swaptr.aide.data.storage.ModelStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalApi::class)
class LiteRtLmEngine(
    private val appContext: Context,
    private val storage: ModelStorage,
) : LlmEngine {

    private val state = AtomicReference<LoadedState?>(null)

    override val loadedModelId: String?
        get() = state.get()?.spec?.id

    override val loadedAccelerator: Accelerator?
        get() = state.get()?.primaryBackend?.toAccelerator()

    override suspend fun load(spec: ModelSpec, config: GenerationConfig) = withContext(Dispatchers.IO) {
        if (!storage.isDownloaded(spec)) {
            throw IllegalStateException("Model ${spec.id} is not downloaded")
        }
        close()

        val file = storage.modelFile(spec)
        val supportImage = spec.capabilities.visionIn
        val supportAudio = spec.capabilities.audioIn
        val maxNumTokens = spec.defaultConfig?.maxTokens ?: DEFAULT_MAX_TOKEN

        val preferredBackend = labelToBackend(config.backend.toAcceleratorLabel())
        val visionBackend = if (supportImage)
            labelToBackend(spec.defaultConfig?.visionAccelerator)
        else null
        val engineConfig = EngineConfig(
            modelPath = file.absolutePath,
            backend = preferredBackend,
            visionBackend = visionBackend,
            audioBackend = if (supportAudio) Backend.CPU() else null,
            maxNumTokens = maxNumTokens,
            // GPU kernel-cache writes break when cacheDir is forced for app-private storage.
            // Only override for /data/local/tmp side-loads.
            cacheDir = if (file.absolutePath.startsWith("/data/local/tmp"))
                appContext.getExternalFilesDir(null)?.absolutePath
            else null,
        )
        android.util.Log.i(
            TAG,
            "load spec=${spec.id} backend=${engineConfig.backend} " +
                "visionBackend=${engineConfig.visionBackend} audioBackend=${engineConfig.audioBackend} " +
                "maxNumTokens=${engineConfig.maxNumTokens} cacheDir=${engineConfig.cacheDir} " +
                "modelPath=${engineConfig.modelPath}",
        )

        val engine = try {
            Engine(engineConfig).also { it.initialize() }
        } catch (t: Throwable) {
            // Rethrow so LlmEngineRepository.loadInternal can flip GPU → CPU and retry.
            throw IllegalStateException(
                cleanUpMediapipeTaskErrorMessage(t.message ?: "Unknown error"),
                t,
            )
        }
        state.set(LoadedState(spec, engine, preferredBackend))
    }

    override fun generate(prompt: String, config: GenerationConfig): Flow<String> = flow {
        val loaded = state.get() ?: throw IllegalStateException("No model loaded; call load() first")
        loaded.engine.createConversation().use { conversation ->
            conversation.sendMessageAsync(prompt).collect { message ->
                val text = message.textContent()
                if (text.isNotEmpty()) emit(text)
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun newChatSession(
        initialMessages: List<AideMessage>,
        tools: List<AideTool>,
        systemInstruction: String?,
        config: GenerationConfig,
        dispatcher: com.swaptr.aide.domain.llm.dispatch.ToolDispatcher,
        activationState: com.swaptr.aide.domain.llm.ToolActivationState?,
    ): ChatSession {
        val loaded = state.get() ?: throw IllegalStateException("No model loaded; call load() first")
        warnUnsupportedConfig(config)
        // Gemma 4 prompt template is binary on/off — no Level support.
        val thinkingEnabled = config.thinking !is GenerationConfig.ThinkingRequest.Off
        return LiteRtLmChatSession(
            engine = loaded.engine,
            initialMessages = initialMessages,
            tools = tools,
            systemInstruction = systemInstruction,
            dispatcher = dispatcher,
            thinkingEnabled = thinkingEnabled,
            samplerDefaults = loaded.spec.defaultConfig,
            // NPU/TPU drivers reject SamplerConfig — must be null on those backends.
            primaryBackendIsNpu = loaded.primaryBackend is Backend.NPU,
        )
    }

    private fun warnUnsupportedConfig(config: GenerationConfig) {
        if (config.responseSchema != null) {
            android.util.Log.w(TAG, "responseSchema ignored (unsupported by LiteRT 0.11)")
        }
        if (config.stopSequences.isNotEmpty()) {
            android.util.Log.w(TAG, "stopSequences ignored (unsupported by LiteRT 0.11)")
        }
    }

    override fun close() {
        val old = state.getAndSet(null) ?: return
        runCatching { old.engine.close() }
    }

    private fun ModelBackend.toAcceleratorLabel(): String = when (this) {
        ModelBackend.CPU -> Accelerator.CPU.label
        ModelBackend.GPU -> Accelerator.GPU.label
    }

    // NPU/TPU need nativeLibraryDir so the delegate can dlopen vendor blobs.
    private fun labelToBackend(label: String?): Backend = when (label) {
        Accelerator.CPU.label -> Backend.CPU()
        Accelerator.GPU.label -> Backend.GPU()
        Accelerator.NPU.label,
        Accelerator.TPU.label ->
            Backend.NPU(nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir)
        else -> Backend.GPU()
    }

    // TPU collapses to NPU at the runtime layer — same delegate class on Pixel 10.
    private fun Backend.toAccelerator(): Accelerator = when (this) {
        is Backend.CPU -> Accelerator.CPU
        is Backend.GPU -> Accelerator.GPU
        is Backend.NPU -> Accelerator.NPU
        else -> Accelerator.CPU
    }

    private data class LoadedState(
        val spec: ModelSpec,
        val engine: Engine,
        val primaryBackend: Backend,
    )

    private companion object {
        const val TAG = "AideEngine"
        const val DEFAULT_MAX_TOKEN = 1024
    }
}
