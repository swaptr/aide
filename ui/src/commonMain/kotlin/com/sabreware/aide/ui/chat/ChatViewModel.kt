package com.sabreware.aide.ui.chat

import kotlinx.coroutines.flow.onStart
import com.sabreware.aide.core.common.prefs.peek
import com.sabreware.aide.core.domain.model.ModelSelection
import com.sabreware.aide.core.domain.model.ModelGateState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import com.sabreware.aide.core.common.persist.DocState
import com.sabreware.aide.core.designsystem.state.stateInUi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabreware.aide.core.common.media.AttachmentKind
import com.sabreware.aide.core.common.media.FileAttachmentStore
import com.sabreware.aide.core.common.media.ImageAttachmentStore
import com.sabreware.aide.core.common.media.PendingFileAttachment
import com.sabreware.aide.core.common.media.classifyAttachment
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.speech.DictationController
import com.sabreware.aide.core.common.speech.DictationSurfaceId
import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.chat.Chat
import com.sabreware.aide.core.domain.chat.ChatTranscriptFactory
import com.sabreware.aide.core.domain.chat.ObservableChatTranscript
import com.sabreware.aide.core.domain.io.IdentityOutputChannel
import com.sabreware.aide.core.domain.io.InputEvent
import com.sabreware.aide.core.domain.io.MicClipRecorder
import com.sabreware.aide.core.domain.io.TextInputChannel
import com.sabreware.aide.core.domain.llm.ChatSession
import com.sabreware.aide.core.domain.llm.LlmEngineRepository
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.InputModality
import com.sabreware.aide.core.domain.model.ModelPrefs
import com.sabreware.aide.core.domain.model.ModelRegistryRepository
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.model.inputModalityOrNull
import com.sabreware.aide.core.domain.permission.AppPermission
import com.sabreware.aide.core.domain.permission.PermissionResult
import com.sabreware.aide.core.domain.permission.RuntimePermissionGate
import com.sabreware.aide.core.domain.search.SearchPrefs
import com.sabreware.aide.core.domain.search.WebSearchProviderId
import com.sabreware.aide.core.domain.tools.ToolCategory
import com.sabreware.aide.core.domain.tools.ToolGate
import com.sabreware.aide.core.domain.tools.phone.ContactPickGate
import com.sabreware.aide.core.domain.usecase.ArchiveChatWithFallbackUseCase
import com.sabreware.aide.core.domain.usecase.CreateChatUseCase
import com.sabreware.aide.core.domain.usecase.DeleteChatWithFallbackUseCase
import com.sabreware.aide.core.domain.usecase.DeleteMessagesFromUseCase
import com.sabreware.aide.core.domain.usecase.ObserveChatMessagesUseCase
import com.sabreware.aide.core.domain.usecase.ObserveChatUseCase
import com.sabreware.aide.core.domain.usecase.RenameChatUseCase
import com.sabreware.aide.core.domain.usecase.SendChatMessageUseCase
import com.sabreware.aide.core.domain.usecase.SetChatArchivedUseCase
import com.sabreware.aide.core.domain.usecase.SetChatStarredUseCase
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.ui.navigation.Route
import com.sabreware.aide.ui.speech.TextFieldStateSink
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path.Companion.toPath

// Empty chatId = draft; DB row created lazily on first send so "+ New chat" doesn't
// pollute drawer with empty placeholders.
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    // The page's route, handed in by its entry (Navigation 3 keeps route args out of SavedStateHandle).
    private val route: Route.Chat,
    private val savedStateHandle: SavedStateHandle,
    observeChat: ObserveChatUseCase,
    observeMessages: ObserveChatMessagesUseCase,
    private val createChat: CreateChatUseCase,
    private val sendChatMessage: SendChatMessageUseCase,
    private val userPrefs: PreferenceStore,
    private val imageStore: ImageAttachmentStore,
    private val fileStore: FileAttachmentStore,
    private val registry: ModelRegistryRepository,
    private val engineRepository: com.sabreware.aide.core.domain.llm.LlmEngineRepository,
    private val setChatStarred: SetChatStarredUseCase,
    private val setChatArchived: SetChatArchivedUseCase,
    private val renameChatUseCase: RenameChatUseCase,
    private val deleteChatWithFallback: DeleteChatWithFallbackUseCase,
    private val deleteMessagesFrom: DeleteMessagesFromUseCase,
    private val archiveChatWithFallback: ArchiveChatWithFallbackUseCase,
    private val dictationController: DictationController,
    private val clipRecorder: MicClipRecorder,
    private val gate: RuntimePermissionGate,
    private val transcriptFactory: ChatTranscriptFactory,
) : ViewModel(), SendChatMessageUseCase.SessionHolder {

    private val chatIdFlow = MutableStateFlow(route.chatId)

    // VM-local so toggling doesn't change nav dest (in-place mode transition).
    private val incognitoTranscriptFlow = MutableStateFlow<ObservableChatTranscript?>(
        if (route.incognito) transcriptFactory.createInMemory() else null,
    )

    // Seeded SYNCHRONOUSLY from the user's choice document, which the app scope starts reading at process
    // start — so the first composition normally has the model name (or a settled "no model") in hand and
    // never draws a shimmer at all. If the read has not landed, the collector in `init` fills it in a
    // dispatch later; either way nothing blocks on I/O to build the ViewModel.
    private val _uiState = MutableStateFlow(
        ChatUiState(
            chatId = chatIdFlow.value,
            // A blank id is a new chat, known empty; an existing one is unknown until its rows land.
            messagesLoaded = chatIdFlow.value.isBlank(),
            isIncognito = route.incognito,
            webSearchEnabled = userPrefs.peek(SearchPrefs.Enabled),
            // A property of the platform, known at construction — not something to discover by calling
            // newCameraCapture() and catching.
            cameraAvailable = imageStore.supportsCameraCapture,
        ).seededWith(registry.selection.value),
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Global reasoning toggle, surfaced in the chat's "Add to chat" sheet for quick access (same pref
    // the Settings screen writes; read at send time by SendChatMessageUseCase).
    // A preference that cannot be read falls back to its default rather than sticking forever.
    val reasoningEnabled: StateFlow<Boolean> = userPrefs.flow(ModelPrefs.ReasoningEnabled)
        .stateInUi(viewModelScope, userPrefs.peek(ModelPrefs.ReasoningEnabled)) { ModelPrefs.ReasoningEnabled.default }

    private val streamingPatchFlow = MutableStateFlow<StreamingPatch?>(null)

    // The reply accumulates HERE, in place. Concatenating `previous.text + delta` per token is O(n²) in
    // reply length, and snapshotting per token keeps it O(n²) — so the builder appends per token (O(1))
    // and a String snapshot is published at most once per STREAM_PATCH_MIN_INTERVAL. Total copy cost is
    // then bounded by stream *duration*, not token count, and the list rebuild + markdown re-parse behind
    // the patch flow run at most ~30/s however fast the model emits. Only touched from collectSendEvents
    // and the reset paths, all on the ViewModel's main-dispatcher scope — no lock needed.
    private val streamingText = StringBuilder()
    private var streamingAssistantId = NO_STREAMING_ID
    private var streamingPatchMark: TimeMark? = null

    // Live chips by callId; cleared once persisted version lands via observeMessages.
    private val inFlightToolCalls = MutableStateFlow<Map<String, ChatMessage.ToolInvocation>>(emptyMap())

    // Streaming thinking chips keyed by assistant row id; cleared on Done (persisted takes over).
    private val inFlightThinking = MutableStateFlow<Map<Long, ChatMessage.Thinking>>(emptyMap())

    // Local set so header recomposes don't re-enter the management layer's dedupe each time.
    private val hydratedModelIds: MutableSet<String> = mutableSetOf()

    // ChatScreen watches this to launch ACTION_PICK; cleared by resolveContactPick or stop().
    private val pendingContactPickFlow = MutableStateFlow<String?>(null)
    val pendingContactPick: StateFlow<String?> = pendingContactPickFlow.asStateFlow()

    // Bumping restarts the messages source chain — pull-to-refresh on the message list.
    private val messagesRefreshTrigger = MutableStateFlow(0)

    private val _messagesRefreshing = MutableStateFlow(false)
    val messagesRefreshing: StateFlow<Boolean> = _messagesRefreshing.asStateFlow()

    fun refreshMessages() {
        viewModelScope.launch {
            _messagesRefreshing.value = true
            messagesRefreshTrigger.update { it + 1 }
            // Floor so the indicator reads as a refresh even when the re-query returns instantly.
            delay(500)
            _messagesRefreshing.value = false
        }
    }

    override var session: ChatSession? = null
    override var sessionModelId: String? = null
    override var sessionEnabledGated: Set<ToolGate> = emptySet()
    override var sessionWebSearchProviderId: WebSearchProviderId? = null
    override var sessionEnabledCategories: Set<ToolCategory> = emptySet()

    private var sendJob: Job? = null
    private val sendMutex = Mutex()

    /**
     * Whether a turn is actually running — as opposed to what the composer is currently displaying.
     *
     * [stop] flips the engine state to Idle for immediate feedback, so `composerBusy` says "not busy" while
     * the turn is still unwinding. A second [send] admitted on that reassigns [sendJob], which leaves the
     * first turn with no handle to cancel and puts two turns on one mutable session holder.
     */
    private val sendInFlight: Boolean get() = sendJob?.isActive == true

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

        // STAGE 1 — the header's cheap seed: the user's choice document, one small file read at process
        // start. The pill carries the chosen model — or a settled "No model" with its call to action — on the
        // FIRST frame while the registry below does its provider/crypto/disk work. It only ever paints:
        // `hasModel` stays false until stage 2, so nothing is sent to a name whose spec is not resolved.
        registry.selection
            .onEach { selection -> _uiState.update { it.seededWith(selection) } }
            .launchIn(viewModelScope)

        // The conversation's own metadata, independent of the model: a title or star change must land even
        // while the model is still resolving.
        chatIdFlow.flatMapLatest { id -> if (id.isBlank()) flowOf(null) else observeChat(id) }
            .onEach { chat ->
                _uiState.update {
                    it.copy(
                        chatId = chatIdFlow.value,
                        title = chat?.title.orEmpty(),
                        isStarred = chat?.isStarred ?: false,
                        isArchived = chat?.isArchived ?: false,
                    )
                }
            }
            .launchIn(viewModelScope)

        // STAGE 2 — the settled answer, for the ONE model the user chose. `registry.resolve` asks only that
        // model's source: the disk for an on-device model, its own provider for a remote one. It used to wait
        // for every provider to settle, so a downloaded local model sat behind cloud catalogs it has nothing
        // to do with. Keyed on the choice DOCUMENT (not `lastUsedModelIdFlow`, whose seed is a null that would
        // read as "nothing chosen" before the file has been read).
        registry.selection
            .filterIsInstance<DocState.Ready<ModelSelection>>()
            .map { it.value.lastUsedModelId }
            .distinctUntilChanged()
            .flatMapLatest { id ->
                if (id.isNullOrBlank()) flowOf(null) else registry.resolve(id).map { gate -> id to gate }
            }
            // Unresolved / Downloading are not answers: paint the recorded choice and hold the composer. This
            // must also DROP any previously resolved model — after a switch from A to a B whose provider has
            // not answered yet, keeping A resolved would send the next turn to the model the user just left.
            .onEach { resolved ->
                val gate = resolved?.second
                if (gate is ModelGateState.Unresolved || gate is ModelGateState.Downloading) {
                    _uiState.update {
                        it.copy(
                            modelResolution = ModelResolution.Unresolved,
                            currentModelId = "",
                            unavailableModel = null,
                            reroutedFrom = null,
                        ).seededWith(registry.selection.value)
                    }
                }
            }
            .filter { it == null || it.second is ModelGateState.Ready || it.second is ModelGateState.Missing }
            .map { resolved ->
                val gate = resolved?.second
                val ready = gate as? ModelGateState.Ready
                val spec = ready?.spec
                HeaderInfo(
                    displayName = spec?.displayName.orEmpty(),
                    modelId = spec?.id.orEmpty(),
                    provider = spec?.provider,
                    supportsTools = spec?.capabilities?.toolsLocal ?: false,
                    supportsVision = spec?.capabilities?.visionIn ?: false,
                    supportsAudio = spec?.capabilities?.audioIn ?: false,
                    supportsDocuments = spec?.capabilities?.documentIn ?: false,
                    supportsThinking = spec?.capabilities?.thinking?.let { it != com.sabreware.aide.core.domain.model.ChatCapabilities.ThinkingMode.None } ?: false,
                    // Chosen but not usable: name it. Only the user's reroute setting may swap in another model,
                    // and then `reroutedFrom` says so for as long as it lasts.
                    unavailable = (gate as? ModelGateState.Missing)?.card?.displayName,
                    reroutedFrom = ready?.reroutedFrom?.displayName,
                    choiceId = resolved?.first.orEmpty(),
                )
            }
            .onEach { header ->
                // First-touch hydration; LiteRT no-ops, Ollama issues one /api/show, then re-emits.
                val modelId = header.modelId
                if (modelId.isNotBlank() && hydratedModelIds.add(modelId)) {
                    registry.findSpec(modelId)?.let { spec ->
                        if (spec is ChatModelSpec) viewModelScope.launch { engineRepository.hydrateSpec(spec) }
                    }
                }
                // Switched to a model that can't take a staged attachment? Drop it WITH a message rather
                // than letting it vanish silently (image on non-vision, PDF on non-document).
                val pendingPath = _uiState.value.pendingImagePath
                val dropImage = pendingPath != null && !header.supportsVision
                if (dropImage) {
                    viewModelScope.launch { imageStore.delete(pendingPath) }
                }
                val pendingDoc = _uiState.value.pendingFile
                val dropDoc = pendingDoc != null &&
                    pendingDoc.kind == AttachmentKind.Pdf && !header.supportsDocuments
                if (dropDoc) {
                    viewModelScope.launch { fileStore.delete(pendingDoc.path) }
                }
                _uiState.update {
                    it.copy(
                        modelResolution = ModelResolution.Resolved,
                        modelDisplayName = header.displayName,
                        currentModelId = header.modelId,
                        modelProvider = header.provider,
                        modelSupportsTools = header.supportsTools,
                        modelSupportsVision = header.supportsVision,
                        modelSupportsAudio = header.supportsAudio,
                        modelSupportsDocuments = header.supportsDocuments,
                        modelSupportsThinking = header.supportsThinking,
                        unavailableModel = header.unavailable,
                        reroutedFrom = header.reroutedFrom,
                        resolvedChoiceId = header.choiceId,
                        pendingImagePath = if (dropImage) null else it.pendingImagePath,
                        pendingFile = if (dropDoc) null else it.pendingFile,
                        errorMessage = when {
                            dropImage -> "Image removed. This model can't read images."
                            dropDoc -> "File removed. This model can't read PDFs."
                            else -> it.errorMessage
                        },
                    )
                }
            }
            .launchIn(viewModelScope)

        // Observe persisted web-search toggle (seeded from the prefs snapshot in the initial state).
        userPrefs.flow(SearchPrefs.Enabled)
            .onEach { enabled -> _uiState.update { it.copy(webSearchEnabled = enabled) } }
            .launchIn(viewModelScope)

        // Reactive on incognitoTranscriptFlow so mode-flip swaps the source live. The outer refresh
        // trigger restarts the whole source chain — the chat's pull-to-refresh re-fetches from the DB.
        val baseMessages: Flow<List<ChatMessage>> =
            messagesRefreshTrigger.flatMapLatest {
                incognitoTranscriptFlow.flatMapLatest { transcript ->
                    if (transcript != null) {
                        transcript.entries.map { entries -> entries.toChatMessages() }
                    } else {
                        chatIdFlow.flatMapLatest { id ->
                            if (id.isBlank()) flowOf(emptyList())
                            else observeMessages(id).map { entities -> entities.toChatMessages() }
                                // Switching to another chat: unknown again until its rows land.
                                .onStart { _uiState.update { it.copy(messagesLoaded = false) } }
                        }
                    }
                }
            }
        combine(
            baseMessages,
            streamingPatchFlow,
            inFlightToolCalls,
            inFlightThinking,
        ) { ui, patch, inFlightTools, inFlightThink ->
            val patched = when {
                patch == null -> ui
                ui.any { it is ChatMessage.Assistant && it.id == patch.assistantId } ->
                    ui.map { msg ->
                        if (msg is ChatMessage.Assistant && msg.id == patch.assistantId) {
                            msg.copy(text = patch.text, isStreaming = true)
                        } else msg
                    }
                // No persisted Assistant row to patch yet: during a thinking→answer (or tool→answer)
                // transition the turn is persisted as only a thinking chip / tool chips with empty text,
                // so toChatMessages emits no Assistant row and the answer has nothing to stream into —
                // it pops in only when the final text persists. Append a synthetic streaming row so the
                // answer streams live; the persisted row (with text) takes over on completion.
                else -> ui + ChatMessage.Assistant(
                    id = patch.assistantId,
                    text = patch.text,
                    isStreaming = true,
                )
            }
            val withThinking = mergeInFlightThinking(patched, inFlightThink, patch?.assistantId)
            // Stats are persisted on turn completion and rehydrate via the message rows (StoredMessage.stats).
            mergeInFlightChips(withThinking, inFlightTools, patch?.assistantId)
        }
            .onEach { messages -> _uiState.update { it.copy(messages = messages, messagesLoaded = true) } }
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
        )
    }

    fun stopDictation() {
        dictationController.stop(DictationSurfaceId.MAIN_CHAT_COMPOSER)
    }

    fun send() {
        val current = _uiState.value
        val draft = composerState.text.toString()
        if (sendInFlight ||
            current.composerBusy ||
            (draft.isBlank() && current.pendingAudioPath == null && current.pendingFile == null)
        ) {
            return
        }
        if (!current.hasModel) {
            // No model yet — open the picker (select/add) instead of writing an inline error.
            // Leave the draft in the composer so the user can resend once a model is set.
            _uiState.update {
                it.copy(openPickerRequest = true, errorMessage = null)
            }
            return
        }
        // Compare-and-act: the model this turn goes to is the one resolved for the CURRENT choice, bound here
        // at the tap. A pick that landed in the file but has not reached the header yet (a switch in flight)
        // means the resolved id is the model being left — refuse, keep the draft, and let the header catch
        // up; the next tap sends to the new model once it resolves.
        val modelId = current.currentModelId
        val chosen = (registry.selection.value as? DocState.Ready)?.value?.lastUsedModelId
        if (chosen != current.resolvedChoiceId) return
        // A rerouted turn runs on a stand-in and must not be recorded as the user's choice.
        val recordUsage = current.reroutedFrom == null
        val text = draft.trim()
        val imagePath = current.pendingImagePath
        val audioPath = current.pendingAudioPath
        val file = current.pendingFile
        // Captured before it's cleared: a non-null id means this send replaces that user turn and
        // restarts the conversation from there (truncate the transcript, then send the edited text).
        val editFromId = current.editingMessageId

        // Sending vs Warming: Warming reserved for weight-load so we don't flash it on every send.
        composerState.clearText()
        _uiState.update {
            it.copy(
                pendingImagePath = null,
                pendingAudioPath = null,
                pendingFile = null,
                engineState = EngineState.Sending,
                errorMessage = null,
                editingMessageId = null,
            )
        }

        sendJob = viewModelScope.launch {
            sendMutex.withLock {
                if (incognitoTranscriptFlow.value != null) {
                    if (editFromId != null) prepareEditResend(editFromId, chatId = null)
                    runIncognitoSend(modelId, recordUsage, text, imagePath, audioPath, file)
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
                if (editFromId != null) prepareEditResend(editFromId, chatId = chatId)
                runSend(chatId, modelId, recordUsage, text, imagePath, audioPath, file)
            }
        }
    }

    /** Enter edit mode for a prior user turn: prefill the composer with [text] and show the editing
     *  affordance. Sending while editing restarts the conversation from this turn. No-op mid-generation. */
    fun beginEditUserMessage(id: Long, text: String) {
        if (_uiState.value.composerBusy) return
        composerState.setTextAndPlaceCursorAtEnd(text)
        _uiState.update { it.copy(editingMessageId = id, errorMessage = null) }
    }

    /** Leave edit mode without sending — clears the prefilled draft and restores the normal composer. */
    fun cancelEdit() {
        if (_uiState.value.editingMessageId == null) return
        composerState.clearText()
        _uiState.update { it.copy(editingMessageId = null) }
    }

    /** Truncate the transcript at the edited turn, then drop the cached session so the next send
     *  reseeds its KV cache from the (now shorter) history — a clean restart from this point. */
    private suspend fun prepareEditResend(fromId: Long, chatId: String?) {
        if (chatId != null) {
            deleteMessagesFrom(chatId, fromId)
        } else {
            incognitoTranscriptFlow.value?.truncateFrom(fromId)
        }
        resetSessionForRebind()
    }

    /** Force the next send to rebuild its chat session (and KV cache) from the current transcript. */
    private fun resetSessionForRebind() {
        runCatching { session?.close() }
        session = null
        sessionModelId = null
        sessionEnabledGated = emptySet()
        sessionWebSearchProviderId = null
        sessionEnabledCategories = emptySet()
    }

    /**
     * Stage a picked file — any type; classification decides the route (image/audio slots, PDF document,
     * or inline text). Capability gating happens HERE, at attach, with the reason surfaced immediately —
     * not silently at send, and not by hiding the picker. [readBytes] is the picker's lazy content reader,
     * so no picker types cross into the VM.
     */
    fun attachPickedFile(fileName: String, readBytes: suspend () -> ByteArray?) {
        viewModelScope.launch {
            val state = _uiState.value
            val kind = classifyAttachment(fileName)
            // The kind→capability rule is the shared [InputModality] mapping; only the copy for the
            // rejection strings lives here. Text maps to no modality — inlined at send, never gated.
            val modality = kind.inputModalityOrNull()
            val rejection = when {
                kind == AttachmentKind.Unsupported -> "Can't attach \"$fileName\". Unsupported file type."
                modality != null && !state.accepts(modality) -> when (modality) {
                    InputModality.Image -> "Selected model can't read images."
                    InputModality.Audio -> "Selected model can't take audio."
                    InputModality.Document -> "Selected model can't read PDFs."
                }
                else -> null
            }
            if (rejection != null) {
                _uiState.update { it.copy(errorMessage = rejection) }
                return@launch
            }
            val bytes = runCatching { readBytes() }.getOrNull()
            val path = bytes?.let { fileStore.import(it, fileName) }
            if (path == null) {
                _uiState.update { it.copy(errorMessage = "Can't load \"$fileName\". Too large or unreadable.") }
                return@launch
            }
            when (kind) {
                // Image/audio ride the existing slots (chip, preview, send plumbing). NOTE: a file-picked
                // image skips ImageStore's downscale — it's sent at original resolution.
                AttachmentKind.Image -> {
                    _uiState.value.pendingImagePath?.let { imageStore.delete(it) }
                    _uiState.update { it.copy(pendingFile = null, pendingImagePath = path) }
                }
                AttachmentKind.Audio -> {
                    _uiState.value.pendingAudioPath?.let { fileStore.delete(it) }
                    _uiState.update { it.copy(pendingAudioPath = path) }
                }
                else -> {
                    _uiState.value.pendingFile?.let { fileStore.delete(it.path) }
                    _uiState.update { it.copy(pendingFile = PendingFileAttachment(path, fileName, kind)) }
                }
            }
        }
    }

    fun clearPendingFile() {
        _uiState.value.pendingFile?.let { file ->
            viewModelScope.launch { fileStore.delete(file.path) }
        }
        _uiState.update { it.copy(pendingFile = null) }
    }

    fun attachImageFromUri(uriString: String) {
        viewModelScope.launch {
            val path = runCatching { imageStore.importFromUri(uriString) }.getOrNull()
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
    private val _cameraReady: MutableSharedFlow<PermissionResult> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 4,
    )
    val cameraReady: SharedFlow<PermissionResult> = _cameraReady.asSharedFlow()

    fun requestCameraAccess() {
        viewModelScope.launch {
            _cameraReady.emit(gate.ensure(AppPermission.CAMERA))
        }
    }

    // Path stored in SavedStateHandle so OS-killing our process while camera is foreground still recovers the JPEG.
    fun prepareCameraCapture(): String {
        val capture = imageStore.newCameraCapture()
        savedStateHandle[KEY_PENDING_CAMERA] = capture.path
        return capture.uriString
    }

    fun onCameraCaptured(success: Boolean) {
        val path: String? = savedStateHandle[KEY_PENDING_CAMERA]
        savedStateHandle[KEY_PENDING_CAMERA] = null
        AideLog.i(
            "AideCamera",
            "onCameraCaptured success=$success path=$path " +
                "exists=${path?.let { FileSystem.SYSTEM.exists(it.toPath()) }} " +
                "size=${path?.let { FileSystem.SYSTEM.metadataOrNull(it.toPath())?.size ?: 0L }}",
        )
        if (!success || path == null) {
            path?.let { runCatching { FileSystem.SYSTEM.delete(it.toPath(), mustExist = false) } }
            return
        }
        viewModelScope.launch {
            val exists = FileSystem.SYSTEM.exists(path.toPath())
            val size = FileSystem.SYSTEM.metadataOrNull(path.toPath())?.size ?: 0L
            if (!exists || size == 0L) {
                _uiState.update { it.copy(errorMessage = "Camera returned no photo") }
                return@launch
            }
            // Shrink full-res capture in-place so chat history isn't 12 MP.
            val compressed = runCatching { imageStore.compressInPlace(path) }
            if (compressed.isFailure) {
                _uiState.update { it.copy(errorMessage = "Could not process photo") }
                FileSystem.SYSTEM.delete(path.toPath(), mustExist = false)
                return@launch
            }
            val previous = _uiState.value.pendingImagePath
            if (previous != null && previous != path) imageStore.delete(previous)
            _uiState.update { it.copy(pendingImagePath = path) }
        }
    }

    private companion object {
        const val KEY_PENDING_CAMERA = "pendingCameraFile"

        /** No stream in flight — [streamingText] holds nothing worth publishing. */
        const val NO_STREAMING_ID = Long.MIN_VALUE

        /** ~30 snapshots/s: fast enough to read as live, slow enough that render cost stops scaling with token rate. */
        val STREAM_PATCH_MIN_INTERVAL = 33.milliseconds
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

    // Layer-D output seam: chat presentation reacts per-event in collectSendEvents, so the output
    // channel is the identity — every surface still composes as input → reason → output.
    private val output = IdentityOutputChannel<SendChatMessageUseCase.Event>()

    /** True while a mic clip is being recorded for attachment. */
    val isRecordingClip: StateFlow<Boolean> = clipRecorder.isRecording

    init {
        // A recorder that cannot open the microphone (another app holds it) reports it rather than going
        // quiet — the composer previously just stayed on "not recording" with nothing to explain it.
        clipRecorder.error
            .filterNotNull()
            .onEach { message -> _uiState.update { it.copy(errorMessage = message) } }
            .launchIn(viewModelScope)
    }

    /** Start (gating mic permission) or stop+attach a voice clip — audio-as-model-input. */
    fun toggleAudioClip() {
        viewModelScope.launch {
            if (clipRecorder.isRecording.value) {
                clipRecorder.stop()?.let { path -> _uiState.update { it.copy(pendingAudioPath = path) } }
            } else {
                val mic = gate.ensure(AppPermission.MICROPHONE)
                if (mic.isGranted) clipRecorder.start()
                else _uiState.update { it.copy(errorMessage = mic.deniedMessage) }
            }
        }
    }

    fun clearPendingAudio() {
        _uiState.value.pendingAudioPath?.let { path ->
            runCatching { FileSystem.SYSTEM.delete(path.toPath(), mustExist = false) }
        }
        _uiState.update { it.copy(pendingAudioPath = null) }
    }

    /** Discard an in-progress recording (and any just-captured draft) without attaching it — used when
     *  the record sheet is dismissed instead of stopped. */
    fun cancelAudioClip() {
        clipRecorder.cancel()
        _uiState.value.pendingAudioPath?.let { path ->
            runCatching { FileSystem.SYSTEM.delete(path.toPath(), mustExist = false) }
        }
        _uiState.update { it.copy(pendingAudioPath = null) }
    }

    /** Build this turn's content-IR parts through the text input channel (media leads the Text part). */
    private suspend fun userTurnParts(
        userText: String,
        imagePath: String?,
        audioPath: String?,
        file: PendingFileAttachment?,
    ): List<AidePart> {
        val attachments = buildList {
            imagePath?.let { add(AidePart.ImageFile(it, imageMediaType(it))) }
            audioPath?.let { add(AidePart.AudioFile(it)) }
            if (file?.kind == AttachmentKind.Pdf) {
                add(AidePart.DocumentFile(file.path, "application/pdf", file.name))
            }
        }
        // A text-extractable file inlines into the turn itself, so it reaches EVERY engine — remote and
        // on-device — through the plain text path, with no capability and no per-codec mapping.
        val text = if (file?.kind == AttachmentKind.Text) {
            val content = fileStore.readText(file.path).orEmpty()
            buildString {
                append(userText)
                if (userText.isNotBlank()) append("\n\n")
                append("[Attached file: ").append(file.name).append("]\n")
                append(content)
            }
        } else {
            userText
        }
        val final = TextInputChannel(text, attachments).capture().first()
        return (final as InputEvent.Final).parts
    }

    /** Media type from the stored file's extension — file-picked images keep theirs (png/webp/…); the
     *  camera/photos path always re-encodes to JPEG, so its .jpg falls through to the default. */
    private fun imageMediaType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heic"
        "avif" -> "image/avif"
        else -> AideMessage.JPEG_MEDIA_TYPE
    }

    private suspend fun runSend(
        chatId: String,
        /** Bound at the tap in [send] — a switch made while this turn waits on the mutex cannot redirect it. */
        modelId: String,
        recordUsage: Boolean,
        userText: String,
        imagePath: String?,
        audioPath: String?,
        file: PendingFileAttachment?,
    ) {
        collectSendEvents(
            output.render(
                sendChatMessage.invoke(
                    chatId = chatId,
                    modelId = modelId,
                    userParts = userTurnParts(userText, imagePath, audioPath, file),
                    holder = this,
                    enabledGated = currentEnabledGated(),
                    recordUsage = recordUsage,
                ),
            ),
        )
    }

    private suspend fun runIncognitoSend(
        modelId: String,
        recordUsage: Boolean,
        userText: String,
        imagePath: String?,
        audioPath: String?,
        file: PendingFileAttachment?,
    ) {
        val transcript = incognitoTranscriptFlow.value ?: return
        collectSendEvents(
            output.render(
                sendChatMessage.invoke(
                    transcript = transcript,
                    modelId = modelId,
                    userParts = userTurnParts(userText, imagePath, audioPath, file),
                    holder = this,
                    enabledGated = currentEnabledGated(),
                    recordUsage = recordUsage,
                ),
            ),
        )
    }

    fun setIncognito(enabled: Boolean) {
        val currentlyIncognito = incognitoTranscriptFlow.value != null
        if (currentlyIncognito == enabled) return
        // Resolve any pending tool-confirm await BEFORE cancelling the send job — otherwise the
        // suspended handler waits out the full timeout before the coroutine unwinds.
        sendChatMessage.cancelAllToolConfirms()
        sendJob?.cancel()
        resetSessionForRebind()
        resetStreamingPatch()
        inFlightToolCalls.value = emptyMap()
        inFlightThinking.value = emptyMap()
        chatIdFlow.value = ""
        incognitoTranscriptFlow.value = if (enabled) transcriptFactory.createInMemory() else null
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

    private fun currentEnabledGated(): Set<ToolGate> = buildSet {
        val state = _uiState.value
        if (!state.modelSupportsTools) return@buildSet
        if (state.webSearchEnabled) add(ToolGate.WEB_SEARCH)
    }

    /** Publish what the builder holds. A no-op when nothing streamed or the snapshot is already current. */
    private fun flushStreamingPatch() {
        if (streamingAssistantId == NO_STREAMING_ID) return
        if (streamingPatchFlow.value?.text?.length == streamingText.length) return
        streamingPatchFlow.value = StreamingPatch(streamingAssistantId, streamingText.toString())
        streamingPatchMark = TimeSource.Monotonic.markNow()
    }

    /** Drop the in-flight reply entirely — turn finished, errored, cancelled, or the surface rebound. */
    private fun resetStreamingPatch() {
        streamingText.setLength(0)
        streamingAssistantId = NO_STREAMING_ID
        streamingPatchMark = null
        streamingPatchFlow.value = null
    }

    private suspend fun collectSendEvents(flow: Flow<SendChatMessageUseCase.Event>) {
        try {
            flow.collect { event ->
                // Any non-token event closes a burst: publish the tail the interval gate may be holding,
                // so text never sits invisible behind a tool call or thinking chip.
                if (event !is SendChatMessageUseCase.Event.Streaming) flushStreamingPatch()
                when (event) {
                    SendChatMessageUseCase.Event.Warming -> {
                        _uiState.update { it.copy(engineState = EngineState.Warming) }
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
                        if (_uiState.value.engineState != EngineState.Generating) {
                            _uiState.update { it.copy(engineState = EngineState.Generating) }
                        }
                        // Accumulate HERE, in place. The event carries only the delta: appending to the
                        // builder is O(1), and the String snapshot the UI needs is published at most once
                        // per STREAM_PATCH_MIN_INTERVAL — so copy cost, list rebuild and markdown re-parse
                        // are bounded by stream duration, never by token count.
                        if (streamingAssistantId != event.assistantMessageId) {
                            streamingText.setLength(0)
                            streamingAssistantId = event.assistantMessageId
                            streamingPatchMark = null
                        }
                        streamingText.append(event.delta)
                        val mark = streamingPatchMark
                        if (mark == null || mark.elapsedNow() >= STREAM_PATCH_MIN_INTERVAL) {
                            flushStreamingPatch()
                        }
                    }
                    is SendChatMessageUseCase.Event.Done -> {
                        resetStreamingPatch()
                        inFlightToolCalls.value = emptyMap()
                        inFlightThinking.value = emptyMap()
                        _uiState.update { it.copy(engineState = EngineState.Idle) }
                    }
                    is SendChatMessageUseCase.Event.Error -> {
                        resetStreamingPatch()
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
            resetStreamingPatch()
            inFlightToolCalls.value = emptyMap()
            inFlightThinking.value = emptyMap()
            _uiState.update { it.copy(engineState = EngineState.Idle) }
            throw ce
        } catch (t: Throwable) {
            resetStreamingPatch()
            inFlightToolCalls.value = emptyMap()
            inFlightThinking.value = emptyMap()
            _uiState.update {
                it.copy(
                    engineState = EngineState.Idle,
                    errorMessage = t.message ?: t::class.simpleName ?: "Error",
                )
            }
        }
    }

    fun stop() {
        // Order matters. Release any suspended confirm first, so the handler unwinds instead of waiting out
        // its 60s timeout; ask the session to stop generating; then cancel the job that collects it.
        //
        // That last line is the one that was missing. `RemoteChatSession.cancel()` defers to coroutine
        // cancellation, and nothing was cancelling: the UI flipped to Idle and the next streaming event
        // flipped it straight back to Generating with the text restored, so the reply visibly resumed while
        // tokens kept billing and the database kept being written. On-device sessions stopped because THEY
        // implement cancel(); the cloud path had no second line of defence.
        sendChatMessage.cancelAllToolConfirms()
        runCatching { session?.cancel() }
        sendJob?.cancel()
        resetStreamingPatch()
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
        viewModelScope.launch { userPrefs.set(SearchPrefs.Enabled, enabled) }
    }

    fun setReasoningEnabled(enabled: Boolean) {
        viewModelScope.launch { userPrefs.set(ModelPrefs.ReasoningEnabled, enabled) }
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

    fun confirmToolOp(opId: String, accepted: Boolean, remember: Boolean = false) {
        _uiState.update { it.copy(pendingConfirm = null) }
        sendChatMessage.resolveToolConfirm(opId, accepted, remember)
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
        val provider: com.sabreware.aide.core.domain.model.ProviderId?,
        val supportsTools: Boolean,
        val supportsVision: Boolean,
        val supportsAudio: Boolean,
        val supportsDocuments: Boolean,
        val supportsThinking: Boolean,
        val unavailable: String?,
        val reroutedFrom: String?,
        /** The chosen model id this header was resolved FOR (the stand-in's own id differs when rerouted). */
        val choiceId: String,
    )
}
