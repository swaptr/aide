package com.swaptr.aide.ui.chat

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.swaptr.aide.data.attachments.ImageStore
import com.swaptr.aide.data.chat.InMemoryChatTranscript
import com.swaptr.aide.data.model.ModelRegistryRepository
import com.swaptr.aide.data.prefs.ToolCategory
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import com.swaptr.aide.domain.llm.ChatSession
import com.swaptr.aide.domain.model.ModelSummary
import com.swaptr.aide.domain.search.WebSearchProviderId
import com.swaptr.aide.domain.tools.AideToolRegistry
import com.swaptr.aide.domain.tools.WebFetchToolset
import com.swaptr.aide.domain.tools.WebSearchToolset
import com.swaptr.aide.domain.tools.fs.FileSystemToolset
import com.swaptr.aide.domain.tools.phone.ContactPickGate
import com.swaptr.aide.domain.usecase.ArchiveChatWithFallbackUseCase
import com.swaptr.aide.domain.usecase.CreateChatUseCase
import com.swaptr.aide.domain.usecase.DeleteChatWithFallbackUseCase
import com.swaptr.aide.domain.usecase.ObserveChatMessagesUseCase
import com.swaptr.aide.domain.usecase.ObserveChatUseCase
import com.swaptr.aide.domain.usecase.ObserveModelsUseCase
import com.swaptr.aide.domain.usecase.RenameChatUseCase
import com.swaptr.aide.domain.usecase.SendChatMessageUseCase
import com.swaptr.aide.domain.usecase.SetChatArchivedUseCase
import com.swaptr.aide.domain.usecase.SetChatStarredUseCase
import com.swaptr.aide.domain.speech.dictation.DictationController
import com.swaptr.aide.domain.speech.dictation.DictationSurfaceId
import com.swaptr.aide.domain.speech.dictation.sink.TextFieldStateSink
import com.swaptr.aide.navigation.Route
import com.swaptr.aide.permission.RuntimePermissionGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject

// Empty chatId = draft; DB row created lazily on first send so "+ New chat" doesn't
// pollute drawer with empty placeholders.
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    observeChat: ObserveChatUseCase,
    observeMessages: ObserveChatMessagesUseCase,
    observeModels: ObserveModelsUseCase,
    private val createChat: CreateChatUseCase,
    private val sendChatMessage: SendChatMessageUseCase,
    private val userPrefs: UserPreferencesRepository,
    private val imageStore: ImageStore,
    private val registry: ModelRegistryRepository,
    private val engineRepository: com.swaptr.aide.data.model.LlmEngineRepository,
    private val setChatStarred: SetChatStarredUseCase,
    private val setChatArchived: SetChatArchivedUseCase,
    private val renameChatUseCase: RenameChatUseCase,
    private val deleteChatWithFallback: DeleteChatWithFallbackUseCase,
    private val archiveChatWithFallback: ArchiveChatWithFallbackUseCase,
    private val dictationController: DictationController,
    private val runtimePermissionGate: RuntimePermissionGate,
) : ViewModel(), SendChatMessageUseCase.SessionHolder {

    private val route: Route.Chat = savedStateHandle.toRoute()

    private val chatIdFlow = MutableStateFlow(route.chatId)

    // VM-local so toggling doesn't change nav dest (in-place mode transition).
    private val incognitoTranscriptFlow = MutableStateFlow<InMemoryChatTranscript?>(
        if (route.incognito) InMemoryChatTranscript() else null,
    )

    private val _uiState = MutableStateFlow(
        ChatUiState(chatId = chatIdFlow.value, isIncognito = route.incognito),
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val modelOptions: StateFlow<List<ModelSummary>> = observeModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val streamingPatchFlow = MutableStateFlow<StreamingPatch?>(null)

    // Live chips by callId; cleared once persisted version lands via observeMessages.
    private val inFlightToolCalls = MutableStateFlow<Map<String, ChatMessage.ToolInvocation>>(emptyMap())

    // Streaming thinking chips keyed by assistant row id; cleared on Done (persisted takes over).
    private val inFlightThinking = MutableStateFlow<Map<Long, ChatMessage.Thinking>>(emptyMap())

    // Local set so header recomposes don't re-enter the management layer's dedupe each time.
    private val hydratedModelIds: MutableSet<String> = mutableSetOf()

    // ChatScreen watches this to launch ACTION_PICK; cleared by resolveContactPick or stop().
    private val pendingContactPickFlow = MutableStateFlow<String?>(null)
    val pendingContactPick: StateFlow<String?> = pendingContactPickFlow.asStateFlow()

    override var session: ChatSession? = null
    override var sessionModelId: String? = null
    override var sessionEnabledGated: Set<AideToolRegistry.Gated> = emptySet()
    override var sessionWebSearchProviderId: WebSearchProviderId? = null
    override var sessionWebSearchToolset: WebSearchToolset? = null
    override var sessionWebFetchToolset: WebFetchToolset? = null
    override var sessionFileSystemToolset: FileSystemToolset? = null
    override var sessionEnabledCategories: Set<ToolCategory> = emptySet()

    private var sendJob: Job? = null
    private val sendMutex = Mutex()

    // VM-owned so external writes don't bounce through value/onValueChange and reset cursor.
    val composerState: TextFieldState = TextFieldState()

    private val dictationSink: TextFieldStateSink = TextFieldStateSink(composerState)

    init {
        // Pre-close cached session on external model switch; SendChatMessageUseCase rebind
        // would catch it anyway, but this prevents leaking a session bound to a stale provider.
        registry.lastUsedModelIdFlow
            .onEach { newId ->
                if (sessionModelId != null && sessionModelId != newId) {
                    runCatching { session?.close() }
                    session = null
                    sessionModelId = null
                }
            }
            .launchIn(viewModelScope)

        // Active model id is global registry state shared with all open chats + Models screen.
        combine(
            chatIdFlow.flatMapLatest { id ->
                if (id.isBlank()) flowOf(null) else observeChat(id)
            },
            registry.effectiveLastUsedModelIdFlow,
            modelOptions,
        ) { chat, activeId, models ->
            val availableIds = models.asSequence()
                .filter { it.isDownloaded }
                .map { it.spec.id }
                .toSet()
            val effectiveId = activeId.orEmpty()
            val spec = if (effectiveId in availableIds) {
                models.firstOrNull { it.spec.id == effectiveId }?.spec
            } else null
            HeaderInfo(
                displayName = spec?.displayName.orEmpty(),
                modelId = spec?.id.orEmpty(),
                provider = spec?.provider,
                supportsTools = spec?.capabilities?.toolsLocal ?: false,
                supportsVision = spec?.capabilities?.visionIn ?: false,
                noneDownloaded = availableIds.isEmpty(),
                unavailable = false,
                title = chat?.title.orEmpty(),
                isStarred = chat?.isStarred ?: false,
                isArchived = chat?.isArchived ?: false,
            )
        }
            .onEach { header ->
                // First-touch hydration; LiteRT no-ops, Ollama issues one /api/show, then re-emits.
                val modelId = header.modelId
                if (modelId.isNotBlank() && hydratedModelIds.add(modelId)) {
                    registry.findSpec(modelId)?.let { spec ->
                        viewModelScope.launch { engineRepository.hydrateSpec(spec) }
                    }
                }
                // Switched to non-vision model? Drop staged image + banner so it doesn't vanish silently.
                val pendingPath = _uiState.value.pendingImagePath
                val dropImage = pendingPath != null && !header.supportsVision
                if (dropImage) {
                    viewModelScope.launch { imageStore.delete(pendingPath!!) }
                }
                _uiState.update {
                    it.copy(
                        chatId = chatIdFlow.value,
                        modelDisplayName = header.displayName,
                        currentModelId = header.modelId,
                        modelProvider = header.provider,
                        modelSupportsTools = header.supportsTools,
                        modelSupportsVision = header.supportsVision,
                        noModelDownloaded = header.noneDownloaded,
                        modelUnavailable = header.unavailable,
                        title = header.title,
                        isStarred = header.isStarred,
                        isArchived = header.isArchived,
                        pendingImagePath = if (dropImage) null else it.pendingImagePath,
                        errorMessage = if (dropImage) {
                            "Image cleared — selected model can't read images."
                        } else it.errorMessage,
                    )
                }
            }
            .launchIn(viewModelScope)

        // Observe persisted web-search toggle.
        userPrefs.webSearchEnabledFlow
            .onEach { enabled -> _uiState.update { it.copy(webSearchEnabled = enabled) } }
            .launchIn(viewModelScope)

        // Observe persisted filesystem-tool toggle.
        userPrefs.filesystemToolEnabledFlow
            .onEach { enabled -> _uiState.update { it.copy(filesystemToolEnabled = enabled) } }
            .launchIn(viewModelScope)

        // Reactive on incognitoTranscriptFlow so mode-flip swaps the source live.
        val baseMessages: Flow<List<ChatMessage>> =
            incognitoTranscriptFlow.flatMapLatest { transcript ->
                if (transcript != null) {
                    transcript.entries.map { entries -> entries.toChatMessagesFromEntries() }
                } else {
                    chatIdFlow.flatMapLatest { id ->
                        if (id.isBlank()) flowOf(emptyList())
                        else observeMessages(id).map { entities -> entities.toChatMessages() }
                    }
                }
            }
        combine(
            baseMessages,
            streamingPatchFlow,
            inFlightToolCalls,
            inFlightThinking,
        ) { ui, patch, inFlightTools, inFlightThink ->
            val patched = if (patch == null) ui
            else ui.map { msg ->
                if (msg is ChatMessage.Assistant && msg.id == patch.assistantId) {
                    msg.copy(text = patch.text, isStreaming = true)
                } else msg
            }
            val withThinking = mergeInFlightThinking(patched, inFlightThink, patch?.assistantId)
            mergeInFlightChips(withThinking, inFlightTools, patch?.assistantId)
        }
            .onEach { messages -> _uiState.update { it.copy(messages = messages) } }
            .launchIn(viewModelScope)

        // Controller is source-of-truth while dictating; non-dictation errors keep their own paths.
        dictationController.registerSurface(DictationSurfaceId.MAIN_CHAT_COMPOSER)
        dictationController.stateFor(DictationSurfaceId.MAIN_CHAT_COMPOSER)
            .onEach { dictation ->
                _uiState.update { ui ->
                    ui.copy(
                        isDictating = dictation.isDictating,
                        errorMessage = dictation.errorMessage ?: ui.errorMessage,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    // Splices above streaming assistant; dropped when persisted Thinking row arrives.
    private fun mergeInFlightThinking(
        ui: List<ChatMessage>,
        inFlight: Map<Long, ChatMessage.Thinking>,
        assistantId: Long?,
    ): List<ChatMessage> {
        if (inFlight.isEmpty()) return ui
        val knownThinkingForAssistant: Set<Long> = ui.asSequence()
            .filterIsInstance<ChatMessage.Thinking>()
            .map { it.id }
            .toSet()
        val out = ui.toMutableList()
        for ((parentAssistantId, chip) in inFlight) {
            // Skip splice when persisted row already landed (same conceptual slot, different id).
            val syntheticId = -parentAssistantId * 10L - 1L
            if (syntheticId in knownThinkingForAssistant) continue
            val anchorIndex = out.indexOfFirst {
                it is ChatMessage.Assistant && it.id == parentAssistantId
            }
            val insertAt = if (anchorIndex >= 0) anchorIndex else out.size
            // Use synthetic id so list-keying stays stable when persisted row takes over.
            out.add(insertAt, chip.copy(id = syntheticId))
        }
        return out
    }

    // Drop in-flight chip when persisted ToolInvocation with same callId arrives, to avoid double-render.
    private fun mergeInFlightChips(
        ui: List<ChatMessage>,
        inFlight: Map<String, ChatMessage.ToolInvocation>,
        assistantId: Long?,
    ): List<ChatMessage> {
        if (inFlight.isEmpty()) return ui
        val knownCallIds = ui.asSequence()
            .filterIsInstance<ChatMessage.ToolInvocation>()
            .mapNotNull { it.callId }
            .toSet()
        val missing = inFlight.values.filter { it.callId !in knownCallIds }
        if (missing.isEmpty()) return ui
        val out = ui.toMutableList()
        val assistantIndex = if (assistantId != null) {
            out.indexOfFirst { it is ChatMessage.Assistant && it.id == assistantId }
        } else -1
        val insertAt = if (assistantIndex >= 0) assistantIndex else out.size
        out.addAll(insertAt, missing)
        return out
    }

    fun startDictation() {
        if (_uiState.value.isDictating) return
        dictationController.toggle(
            surface = DictationSurfaceId.MAIN_CHAT_COMPOSER,
            sink = dictationSink,
            editorInfo = null,
        )
    }

    fun stopDictation() {
        dictationController.stop(DictationSurfaceId.MAIN_CHAT_COMPOSER)
    }

    fun selectModel(modelId: String) {
        // Close session here too; init observer would do it on next flow tick but we save a hop.
        if (modelOptions.value.none { it.spec.id == modelId && it.isDownloaded }) return
        runCatching { session?.close() }
        session = null
        sessionModelId = null
        viewModelScope.launch {
            registry.findSpec(modelId)?.let { spec ->
                runCatching { registry.recordSelected(spec) }
            }
        }
    }

    fun send() {
        val current = _uiState.value
        val draft = composerState.text.toString()
        if (current.composerBusy || draft.isBlank()) return
        if (current.modelUnavailable) {
            _uiState.update {
                it.copy(
                    errorMessage = "Model unavailable. Pick another to send.",
                    openPickerRequest = true,
                )
            }
            return
        }
        if (current.noModelDownloaded || current.currentModelId.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "Setup a model first")
            }
            return
        }
        val text = draft.trim()
        val imagePath = current.pendingImagePath

        // Sending vs Warming: Warming reserved for weight-load so we don't flash it on every send.
        composerState.clearText()
        _uiState.update {
            it.copy(
                pendingImagePath = null,
                engineState = EngineState.Sending,
                errorMessage = null,
            )
        }

        sendJob = viewModelScope.launch {
            sendMutex.withLock {
                if (incognitoTranscriptFlow.value != null) {
                    runIncognitoSend(text, imagePath)
                    return@withLock
                }
                val chatId = ensureChatRow()
                if (chatId == null) {
                    _uiState.update {
                        it.copy(
                            engineState = EngineState.Idle,
                            errorMessage = "Could not create chat",
                        )
                    }
                    return@withLock
                }
                runSend(chatId, text, imagePath)
            }
        }
    }

    fun attachImageFromUri(uri: Uri) {
        viewModelScope.launch {
            val path = runCatching { imageStore.importFromUri(uri) }.getOrNull()
            if (path == null) {
                _uiState.update { it.copy(errorMessage = "Could not load image") }
                return@launch
            }
            // Drop previously-staged image so we don't leak orphan JPEGs under filesDir.
            val previous = _uiState.value.pendingImagePath
            if (previous != null && previous != path) imageStore.delete(previous)
            _uiState.update { it.copy(pendingImagePath = path) }
        }
    }

    fun clearPendingImage() {
        val path = _uiState.value.pendingImagePath ?: return
        _uiState.update { it.copy(pendingImagePath = null) }
        viewModelScope.launch { imageStore.delete(path) }
    }

    // Zero-replay so config-change doesn't accidentally re-launch the camera.
    private val _cameraReady: MutableSharedFlow<Boolean> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 4,
    )
    val cameraReady: SharedFlow<Boolean> = _cameraReady.asSharedFlow()

    fun requestCameraAccess() {
        viewModelScope.launch {
            val outcome = runtimePermissionGate.request(android.Manifest.permission.CAMERA)
            _cameraReady.emit(outcome.granted)
        }
    }

    // Path stored in SavedStateHandle so OS-killing our process while camera is foreground still recovers the JPEG.
    fun prepareCameraCapture(): Uri {
        val capture = imageStore.newCameraCapture()
        savedStateHandle[KEY_PENDING_CAMERA] = capture.file.absolutePath
        return capture.uri
    }

    fun onCameraCaptured(success: Boolean) {
        val path: String? = savedStateHandle[KEY_PENDING_CAMERA]
        savedStateHandle[KEY_PENDING_CAMERA] = null
        Log.i(
            "AideCamera",
            "onCameraCaptured success=$success path=$path " +
                "exists=${path?.let { File(it).exists() }} " +
                "size=${path?.let { File(it).length() }}",
        )
        if (!success || path == null) {
            path?.let { runCatching { File(it).delete() } }
            return
        }
        val file = File(path)
        viewModelScope.launch {
            if (!file.exists() || file.length() == 0L) {
                _uiState.update { it.copy(errorMessage = "Camera returned no photo") }
                return@launch
            }
            // Shrink full-res capture in-place so chat history isn't 12 MP.
            val compressed = runCatching { imageStore.compressInPlace(path) }
            if (compressed.isFailure) {
                _uiState.update { it.copy(errorMessage = "Could not process photo") }
                file.delete()
                return@launch
            }
            val previous = _uiState.value.pendingImagePath
            if (previous != null && previous != path) imageStore.delete(previous)
            _uiState.update { it.copy(pendingImagePath = path) }
        }
    }

    private companion object {
        const val KEY_PENDING_CAMERA = "pendingCameraFile"
    }

    // Side-effect: updates chatIdFlow so observers rebind to the new row.
    private suspend fun ensureChatRow(): String? {
        val existing = chatIdFlow.value
        if (existing.isNotBlank()) return existing
        return try {
            val chat = createChat()
            chatIdFlow.value = chat.id
            chat.id
        } catch (t: Throwable) {
            null
        }
    }

    private suspend fun runSend(chatId: String, userText: String, imagePath: String?) {
        val modelId = registry.effectiveLastUsedModelIdFlow.first() ?: run {
            _uiState.update {
                it.copy(
                    engineState = EngineState.Idle,
                    errorMessage = "Setup a model first",
                )
            }
            return
        }
        collectSendEvents(
            sendChatMessage.invoke(
                chatId = chatId,
                modelId = modelId,
                userText = userText,
                imagePath = imagePath,
                holder = this,
                enabledGated = currentEnabledGated(),
            ),
        )
    }

    private suspend fun runIncognitoSend(userText: String, imagePath: String?) {
        val modelId = _uiState.value.currentModelId
        val transcript = incognitoTranscriptFlow.value ?: return
        collectSendEvents(
            sendChatMessage.invoke(
                transcript = transcript,
                modelId = modelId,
                userText = userText,
                imagePath = imagePath,
                holder = this,
                enabledGated = currentEnabledGated(),
            ),
        )
    }

    fun setIncognito(enabled: Boolean) {
        val currentlyIncognito = incognitoTranscriptFlow.value != null
        if (currentlyIncognito == enabled) return
        // Unblock any pending tool-confirm await BEFORE cancelling the send job —
        // otherwise the handler thread sits in runBlocking for the full timeout.
        sendChatMessage.cancelAllToolConfirms()
        sendJob?.cancel()
        runCatching { session?.close() }
        session = null
        sessionModelId = null
        sessionEnabledGated = emptySet()
        sessionWebSearchProviderId = null
        sessionWebSearchToolset = null
        sessionWebFetchToolset = null
        sessionFileSystemToolset = null
        sessionEnabledCategories = emptySet()
        streamingPatchFlow.value = null
        inFlightToolCalls.value = emptyMap()
        inFlightThinking.value = emptyMap()
        chatIdFlow.value = ""
        incognitoTranscriptFlow.value = if (enabled) InMemoryChatTranscript() else null
        composerState.clearText()
        _uiState.update {
            it.copy(
                chatId = "",
                pendingImagePath = null,
                engineState = EngineState.Idle,
                errorMessage = null,
                pendingConfirm = null,
                isIncognito = enabled,
            )
        }
    }

    private fun currentEnabledGated(): Set<AideToolRegistry.Gated> = buildSet {
        val state = _uiState.value
        if (!state.modelSupportsTools) return@buildSet
        if (state.webSearchEnabled) add(AideToolRegistry.Gated.WEB_SEARCH)
        if (state.filesystemToolEnabled) add(AideToolRegistry.Gated.FILESYSTEM)
    }

    private suspend fun collectSendEvents(flow: Flow<SendChatMessageUseCase.Event>) {
        try {
            flow.collect { event ->
                when (event) {
                    SendChatMessageUseCase.Event.Warming -> {
                        _uiState.update { it.copy(engineState = EngineState.Warming) }
                    }
                    is SendChatMessageUseCase.Event.ToolRunning -> {
                        // Legacy status-line — inline chip rendering supersedes it; no-op until ported.
                    }
                    is SendChatMessageUseCase.Event.ToolCallStarted -> {
                        val invocation = ChatMessage.ToolInvocation(
                            id = -event.callId.hashCode().toLong(),
                            callId = event.callId,
                            toolName = event.name,
                            argsJson = event.argsJson,
                            resultJson = null,
                            error = null,
                            isRunning = true,
                        )
                        inFlightToolCalls.update { it + (event.callId to invocation) }
                    }
                    is SendChatMessageUseCase.Event.ToolCallCompleted -> {
                        inFlightToolCalls.update { current ->
                            val existing = current[event.callId] ?: return@update current
                            current + (event.callId to existing.copy(
                                resultJson = event.resultJson,
                                error = event.error,
                                isRunning = false,
                            ))
                        }
                    }
                    is SendChatMessageUseCase.Event.ToolNeedsConfirm -> {
                        _uiState.update {
                            it.copy(
                                pendingConfirm = ToolConfirmPrompt(
                                    opId = event.opId,
                                    toolName = event.toolName,
                                    summary = event.summary,
                                    details = event.details,
                                    severity = event.severity,
                                ),
                            )
                        }
                    }
                    is SendChatMessageUseCase.Event.ContactPickRequested -> {
                        pendingContactPickFlow.value = event.opId
                    }
                    is SendChatMessageUseCase.Event.Thinking -> {
                        inFlightThinking.update {
                            it + (event.assistantMessageId to ChatMessage.Thinking(
                                id = -event.assistantMessageId * 10L - 1L,
                                text = event.text,
                                durationMs = event.durationMs,
                                isStreaming = event.isStreaming,
                            ))
                        }
                    }
                    is SendChatMessageUseCase.Event.Streaming -> {
                        _uiState.update {
                            it.copy(engineState = EngineState.Generating)
                        }
                        streamingPatchFlow.value = StreamingPatch(event.assistantMessageId, event.text)
                    }
                    is SendChatMessageUseCase.Event.Done -> {
                        streamingPatchFlow.value = null
                        inFlightToolCalls.value = emptyMap()
                        inFlightThinking.value = emptyMap()
                        _uiState.update { it.copy(engineState = EngineState.Idle) }
                    }
                    is SendChatMessageUseCase.Event.Error -> {
                        streamingPatchFlow.value = null
                        inFlightToolCalls.value = emptyMap()
                        inFlightThinking.value = emptyMap()
                        _uiState.update {
                            it.copy(
                                engineState = EngineState.Idle,
                                errorMessage = event.message,
                            )
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            streamingPatchFlow.value = null
            inFlightToolCalls.value = emptyMap()
            inFlightThinking.value = emptyMap()
            _uiState.update { it.copy(engineState = EngineState.Idle) }
            throw ce
        } catch (t: Throwable) {
            streamingPatchFlow.value = null
            inFlightToolCalls.value = emptyMap()
            inFlightThinking.value = emptyMap()
            _uiState.update {
                it.copy(
                    engineState = EngineState.Idle,
                    errorMessage = t.message ?: t::class.java.simpleName,
                )
            }
        }
    }

    fun stop() {
        sendChatMessage.cancelAllToolConfirms()
        runCatching { session?.cancel() }
        streamingPatchFlow.value = null
        inFlightToolCalls.value = emptyMap()
        inFlightThinking.value = emptyMap()
        pendingContactPickFlow.value = null
        _uiState.update {
            it.copy(
                engineState = EngineState.Idle,
                pendingConfirm = null,
            )
        }
    }

    fun setWebSearchEnabled(enabled: Boolean) {
        viewModelScope.launch { userPrefs.setWebSearchEnabled(enabled) }
    }

    fun consumeOpenPicker() {
        _uiState.update { it.copy(openPickerRequest = false) }
    }

    fun setStarredCurrent(starred: Boolean) {
        val id = chatIdFlow.value
        if (id.isBlank()) return
        viewModelScope.launch { setChatStarred(id, starred) }
    }

    fun renameCurrent(newTitle: String) {
        val id = chatIdFlow.value
        if (id.isBlank()) return
        viewModelScope.launch { renameChatUseCase(id, newTitle) }
    }

    // onReplacement gets the next visible chat id so the screen can navigate off the hidden row.
    fun setArchivedCurrent(archived: Boolean, onReplacement: ((String) -> Unit)? = null) {
        val id = chatIdFlow.value
        if (id.isBlank()) return
        viewModelScope.launch {
            if (archived && onReplacement != null) {
                onReplacement(archiveChatWithFallback(id))
            } else {
                setChatArchived(id, archived)
            }
        }
    }

    fun deleteCurrent(onReplacement: (String) -> Unit) {
        val id = chatIdFlow.value
        if (id.isBlank()) return
        viewModelScope.launch {
            onReplacement(deleteChatWithFallback(id))
        }
    }

    fun confirmToolOp(opId: String, accepted: Boolean) {
        _uiState.update { it.copy(pendingConfirm = null) }
        sendChatMessage.resolveToolConfirm(opId, accepted)
    }

    // null result = user cancelled the picker.
    fun resolveContactPick(opId: String, result: ContactPickGate.ContactPickResult?) {
        pendingContactPickFlow.value = null
        sendChatMessage.resolveContactPick(opId, result)
    }

    override fun onCleared() {
        super.onCleared()
        sendChatMessage.cancelAllToolConfirms()
        sendJob?.cancel()
        dictationController.unregisterSurface(DictationSurfaceId.MAIN_CHAT_COMPOSER)
        runCatching { session?.close() }
    }

    private data class StreamingPatch(val assistantId: Long, val text: String)

    private data class HeaderInfo(
        val displayName: String,
        val modelId: String,
        val provider: com.swaptr.aide.data.catalog.ProviderId?,
        val supportsTools: Boolean,
        val supportsVision: Boolean,
        val noneDownloaded: Boolean,
        val unavailable: Boolean,
        val title: String,
        val isStarred: Boolean,
        val isArchived: Boolean,
    )

    private fun hostFromUrl(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }
            .getOrDefault("")
            .ifBlank { url }
}
