package com.sabreware.aide.app.llm
import com.sabreware.aide.core.common.di.IO
import com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher
import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.ChatGenerationConfig
import com.sabreware.aide.core.domain.llm.ModelWarning
import com.sabreware.aide.core.domain.llm.ToolActivationState
import com.sabreware.aide.core.domain.llm.ChatSession
import com.sabreware.aide.core.domain.llm.LlmEngine
import com.sabreware.aide.core.domain.util.AideLog

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.sabreware.aide.core.domain.model.Accelerator
import com.sabreware.aide.core.domain.model.ModelBackend
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.model.ModelStorage
import kotlinx.coroutines.CoroutineDispatcher
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LlmEngine {

    private val state = AtomicReference<LoadedState?>(null)

    /**
     * Shared with every session this engine creates, so closing the engine waits for the conversations
     * hanging off it. See [NativeTurnGate] for why a free and a decode must never overlap.
     */
    private val turnGate = NativeTurnGate()

    override val loadedModelId: String?
        get() = state.get()?.spec?.id

    override val loadedAccelerator: Accelerator?
        get() = state.get()?.primaryBackend?.toAccelerator()

    override suspend fun load(spec: ChatModelSpec, config: ChatGenerationConfig) = withContext(ioDispatcher) {
        if (!storage.isDownloaded(spec)) {
            throw IllegalStateException("Model ${spec.id} is not downloaded")
        }
        close()

        val file = storage.modelFile(spec).toFile()
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

    override fun generate(prompt: String, config: ChatGenerationConfig): Flow<String> = flow {
        val loaded = state.get() ?: throw IllegalStateException("No model loaded; call load() first")
        // Inside the gate: this one-shot conversation streams from the same engine handle [close] frees.
        turnGate.turn {
            loaded.engine.createConversation().use { conversation ->
                conversation.sendMessageAsync(prompt).collect { message ->
                    val text = message.textContent()
                    if (text.isNotEmpty()) emit(text)
                }
            }
        }
    }.flowOn(ioDispatcher)

    override fun newChatSession(
        initialMessages: List<AideMessage>,
        tools: List<AideTool>,
        systemInstruction: String?,
        config: ChatGenerationConfig,
        dispatcher: com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher,
        activationState: com.sabreware.aide.core.domain.llm.ToolActivationState?,
    ): ChatSession {
        val loaded = state.get() ?: throw IllegalStateException("No model loaded; call load() first")
        // Gemma 4 prompt template is binary on/off — no Level support.
        val thinkingEnabled = config.thinking !is ChatGenerationConfig.ThinkingRequest.Off
        return LiteRtLmChatSession(
            engine = loaded.engine,
            turnGate = turnGate,
            initialMessages = initialMessages,
            tools = tools,
            systemInstruction = systemInstruction,
            dispatcher = dispatcher,
            thinkingEnabled = thinkingEnabled,
            samplerTopK = config.topK,
            samplerTopP = config.topP.toDouble(),
            samplerTemperature = config.temperature.toDouble(),
            // NPU/TPU drivers reject SamplerConfig — must be null on those backends.
            primaryBackendIsNpu = loaded.primaryBackend is Backend.NPU,
            // Config knobs LiteRT can't honor — surfaced on the terminal, not lost to a log.
            configWarnings = unsupportedConfigWarnings(config),
        )
    }

    // Config fields this engine silently ignores. Returned as typed warnings so the terminal
    // Completed can carry them (a log line is kept for grep-ability, but is no longer the only trace).
    private fun unsupportedConfigWarnings(config: ChatGenerationConfig): List<ModelWarning> = buildList {
        if (config.responseSchema != null) {
            add(ModelWarning.UnsupportedSetting("responseSchema", "unsupported by LiteRT 0.11"))
        }
        if (config.stopSequences.isNotEmpty()) {
            add(ModelWarning.UnsupportedSetting("stopSequences", "unsupported by LiteRT 0.11"))
        }
    }.onEach { AideLog.w(TAG, "config ignored: $it") }

    /**
     * Waits for every in-flight turn on this engine before freeing it. The reference is swapped out first,
     * so no NEW turn can reach the handle while the drain is waiting for the ones already running.
     *
     * The reachable path this closes: the Android load policy broadcasts `TRIM_MEMORY_COMPLETE` before every
     * local load, which routes straight to the residency manager's eviction — which used to free the engine
     * from that thread while a decode was still inside it.
     */
    override suspend fun close() {
        val old = state.getAndSet(null) ?: return
        turnGate.drain { runCatching { old.engine.close() } }
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
    }

    private data class LoadedState(
        val spec: ChatModelSpec,
        val engine: Engine,
        val primaryBackend: Backend,
    )

    private companion object {
        const val TAG = "AideEngine"
        const val DEFAULT_MAX_TOKEN = 1024
    }
}
