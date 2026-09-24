package com.sabreware.aide.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import com.sabreware.aide.core.designsystem.AppNotice
import com.sabreware.aide.core.designsystem.NoticeSeverity
import com.sabreware.aide.core.designsystem.AppPullToRefreshBox
import com.sabreware.aide.core.designsystem.ConfirmDialog
import com.sabreware.aide.core.designsystem.HeaderAction
import com.sabreware.aide.core.designsystem.HeaderMenu
import com.sabreware.aide.core.designsystem.AppDropdownDivider
import com.sabreware.aide.core.designsystem.AppMenuAction
import com.sabreware.aide.core.designsystem.RenameChatDialog
import com.sabreware.aide.core.designsystem.browse.ActionRunner
import com.sabreware.aide.core.designsystem.browse.rememberActionRunner
import com.sabreware.aide.core.domain.chat.Chat
import com.sabreware.aide.core.domain.label.LabelSubject
import com.sabreware.aide.ui.chats.chatActions
import com.sabreware.aide.ui.chats.chatSheetHeader
import com.sabreware.aide.ui.labels.LabelsViewModel
import com.sabreware.aide.ui.labels.rememberLabelEditor
import com.sabreware.aide.core.designsystem.rememberToaster
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.theme.aideMarkdownTypography
import com.sabreware.aide.core.domain.llm.gates.WriteConfirmGate
import com.sabreware.aide.ui.models.openModelFlow
import com.sabreware.aide.ui.navigation.Route
import com.sabreware.aide.core.designsystem.navigation.navigator
import org.koin.core.parameter.parametersOf
import com.sabreware.aide.ui.platform.LocalPlatformAffordances
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    route: Route.Chat,
    onOpenDrawer: () -> Unit,
    onNavigateToChat: (String) -> Unit = {},
    onNewChat: () -> Unit = {},
    isDrawerOpen: Boolean = false,
    viewModel: ChatViewModel = koinViewModel { parametersOf(route) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val reasoningEnabled by viewModel.reasoningEnabled.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val toaster = rememberToaster()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val openDrawer: () -> Unit = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onOpenDrawer()
    }
    // Drop the composer's focus + keyboard before opening the model flow: a focused composer under the sheet
    // would pull the keyboard back up the moment the sheet's own search releases it. Mirrors openDrawer above.
    val nav = navigator()
    val openModelSheet: () -> Unit = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        // The same Models pages Settings shows as screens, in a modal over the chat. Selecting a model records
        // it as active; chat switches reactively.
        nav.openModelFlow()
    }

    val composerFocusRequester = remember { FocusRequester() }
    // Focus/keyboard choreography is isolated in a leaf composable: it reads lifecycle, IME-visibility
    // and window-focus state, which change on every nav-slide frame and IME animation frame. Reading
    // them here (in this heavy LazyColumn + markdown screen) would recompose the whole screen mid
    // nav-transition — slowing it. The leaf emits no UI, so those reads invalidate only itself.
    ComposerFocusController(
        isDrawerOpen = isDrawerOpen,
        focusRequester = composerFocusRequester,
    )

    LaunchedEffect(state.openPickerRequest) {
        if (state.openPickerRequest) {
            openModelSheet()
            viewModel.consumeOpenPicker()
        }
    }

    // What this host lets the chat reach outside the app. Each is nullable and each null removes exactly
    // one affordance — the branches below are stable for the composition's lifetime, since the whole set is
    // provided once at the application root.
    val affordances = LocalPlatformAffordances.current

    // PickVisualMedia on Android, a native image dialog on desktop. Null nowhere so far, but the composer
    // already asks `canAttachImage` before offering it.
    val onPickPhoto = affordances.photoPicker?.rememberLauncher { it?.let(viewModel::attachImageFromUri) }

    // Staged into a cache URI then re-imported to filesDir/attachments (outlives cache eviction). Absent on
    // any host with no camera pipeline — which is every desktop, and is also what `state.cameraAvailable`
    // independently reports to the attach sheet.
    val launchCamera = affordances.cameraCapture?.rememberLauncher { success ->
        viewModel.onCameraCaptured(success)
    }
    val onTakePhoto: (() -> Unit)? = launchCamera?.let { { viewModel.requestCameraAccess() } }
    if (launchCamera != null) {
        LaunchedEffect(Unit) {
            viewModel.cameraReady.collect { result ->
                if (result.isGranted) launchCamera(viewModel.prepareCameraCapture())
                else result.deniedMessage?.let { toaster(it) }
            }
        }
    }

    // ACTION_PICK grants temporary URI permission so querying doesn't need READ_CONTACTS. A host with no
    // contacts database also binds no phone toolset, so nothing can ask for a pick in the first place —
    // this null is the second half of a gate the tool side already closes.
    val pendingContactPick by viewModel.pendingContactPick.collectAsStateWithLifecycle()
    val isRecordingClip by viewModel.isRecordingClip.collectAsStateWithLifecycle()
    var activeContactPickOpId by remember { mutableStateOf<String?>(null) }
    val launchContactPick = affordances.contactPicker?.rememberLauncher { result ->
        val opId = activeContactPickOpId
        activeContactPickOpId = null
        if (opId != null) viewModel.resolveContactPick(opId, result)
    }
    if (launchContactPick != null) {
        LaunchedEffect(pendingContactPick) {
            val opId = pendingContactPick ?: return@LaunchedEffect
            if (activeContactPickOpId == opId) return@LaunchedEffect
            activeContactPickOpId = opId
            launchContactPick()
        }
    }

    // While editing a turn, preview the restart: hide everything after the edited message (sending
    // discards those turns anyway). Truncate by position — non-user row ids aren't monotonic.
    val displayedMessages = remember(state.messages, state.editingMessageId) {
        val editId = state.editingMessageId
        if (editId == null) {
            state.messages
        } else {
            val idx = state.messages.indexOfFirst { it.id == editId }
            if (idx < 0) state.messages else state.messages.subList(0, idx + 1)
        }
    }
    // reverseLayout anchors item 0 to viewport bottom — streaming pins naturally without
    // per-token scrollToItem races. Only programmatic scroll is on new user send.
    val reversedMessages = remember(displayedMessages) { displayedMessages.asReversed() }

    // Hoist Markdown styling so per-token recomps reuse instances; else stability check
    // is invalidated and Compose can't skip frozen blocks.
    val onBg = MaterialTheme.colorScheme.onBackground
    val markdownColors = markdownColor(text = onBg)
    val markdownTypography = aideMarkdownTypography()
    // Real reply-row behaviour, wired once and shared by every row (the preview uses ReplyActions.None).
    val replyActions = rememberReplyActions()

    // Keyed on the SIZE, not the list instance. The list is a fresh instance on every streamed token — the
    // streaming row is patched into it — so keying on identity re-ran this scan a thousand times per reply
    // to answer a question whose answer only changes when a message is added.
    val newestUserId = remember(reversedMessages.size) {
        reversedMessages.firstOrNull { it is ChatMessage.User }?.id
    }
    LaunchedEffect(newestUserId) {
        if (newestUserId != null) listState.scrollToItem(0)
    }

    var selectedToolCall by remember { mutableStateOf<ChatMessage.ToolInvocation?>(null) }
    var selectedThinkingId by remember { mutableStateOf<Long?>(null) }
    // Long-press-a-user-turn affordances: the action menu target, and the text shown in the select sheet.
    var messageMenuTarget by remember { mutableStateOf<ChatMessage.User?>(null) }
    var selectTextTarget by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    // The chat's actions: the same list the drawer and the Chats page offer, over this chat.
    val labelsViewModel: LabelsViewModel = koinViewModel()
    val labels by labelsViewModel.labels.collectAsStateWithLifecycle()
    val labelEditor = rememberLabelEditor(labelsViewModel)
    val chatRunner = rememberActionRunner(
        chatActions(
            editor = labelEditor,
            labels = { labels },
            onRename = { showRenameDialog = true },
            setPinned = { _, pinned -> viewModel.setStarredCurrent(pinned) },
            setArchived = { _, archived ->
                // Archiving the open chat moves to the next one; unarchiving stays put.
                if (archived) viewModel.setArchivedCurrent(true) { replacementId -> onNavigateToChat(replacementId) }
                else viewModel.setArchivedCurrent(false)
            },
            onDelete = { targets ->
                viewModel.deleteCurrent { replacementId -> onNavigateToChat(replacementId) }
                labelsViewModel.forget(targets.map { LabelSubject.chat(it.id) })
            },
        ),
    )

    ChatScaffold(
        titleContent = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                // The recorded choice is enough to draw the pill — a name, or a settled "No model" — see
                // ModelResolution. The skeleton covers only the few ms before the choice document is read.
                //
                // AnimatedContent, not Crossfade: Crossfade lays both states out at TOP-START inside a box
                // sized to the LARGER of the two, so any difference in size between them pinned the smaller
                // state off-centre for the whole transition and then snapped it back — the jitter in the
                // header. The pill's width follows the band, not the label (see ModelPillFrame) so there is nothing left to
                // snap, and this centres both states and animates any residual size change with the fade.
                AnimatedContent(
                    targetState = state.headerKnown,
                    transitionSpec = {
                        (fadeIn(tween(180)) togetherWith fadeOut(tween(180)))
                            .using(SizeTransform(clip = false) { _, _ -> tween(180) })
                    },
                    contentAlignment = Alignment.Center,
                    label = "modelPill",
                ) { nameable ->
                    if (nameable) {
                        ModelSelectorPill(
                            // A chosen-but-unavailable model keeps its name (dimmed) — "No model" would hide
                            // which one is gone.
                            label = state.modelDisplayName.ifBlank { state.unavailableModel ?: "No model" },
                            provider = state.modelProvider,
                            onClick = openModelSheet,
                            pending = state.modelResolution == ModelResolution.Cached ||
                                state.unavailableModel != null,
                        )
                    } else {
                        ModelSelectorPillSkeleton()
                    }
                }
            }
        },
        headerColor = MaterialTheme.colorScheme.background,
        leadingAction = HeaderAction.drawer(openDrawer),
        trailingActions = chatHeaderActions(
            state = state,
            onSetIncognito = viewModel::setIncognito,
            runner = chatRunner,
            onNewChat = onNewChat,
        ),
    ) {
            ChatMessagesPane(
                state = state,
                reversedMessages = reversedMessages,
                listState = listState,
                markdownColors = markdownColors,
                markdownTypography = markdownTypography,
                replyActions = replyActions,
                refreshingFlow = viewModel.messagesRefreshing,
                onRefresh = viewModel::refreshMessages,
                onSetUpModel = openModelSheet,
                onToolClick = { selectedToolCall = it },
                onThinkingClick = { selectedThinkingId = it.id },
                onUserLongPress = { messageMenuTarget = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            // Rerouting is the user's own setting, but it still sends their words somewhere they did not pick:
            // say so for as long as it lasts, not as a toast that fades while it keeps happening.
            state.reroutedFrom?.let { chosen ->
                AppNotice(
                    text = "$chosen isn't available. ${state.modelDisplayName} answers until it's back.",
                    severity = NoticeSeverity.Info,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            // In an empty chat the greeting says this; in a conversation it has to be said here.
            if (state.unavailableModel != null && state.messages.isNotEmpty()) {
                AppNotice(
                    text = "${state.unavailableModel} isn't available right now. Tap the model to choose another.",
                    severity = NoticeSeverity.Warning,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (state.errorMessage != null && state.messages.isNotEmpty()) {
                AppNotice(
                    text = state.errorMessage,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (state.editingMessageId != null) {
                Text(
                    text = "Editing this message will restart the conversation from this point.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                )
            }
            Composer(
                composerState = viewModel.composerState,
                isGenerating = state.isGenerating,
                busy = state.composerBusy,
                modelReady = state.hasModel,
                isDictating = state.isDictating,
                webSearchEnabled = state.webSearchEnabled,
                webSearchAvailable = state.modelSupportsTools,
                reasoningEnabled = reasoningEnabled,
                thinkingAvailable = state.modelSupportsThinking,
                isEditing = state.editingMessageId != null,
                attachments = ComposerAttachments(
                    visionAvailable = state.modelSupportsVision,
                    cameraAvailable = state.cameraAvailable,
                    pendingImagePath = state.pendingImagePath,
                    onPickPhoto = onPickPhoto,
                    onTakePhoto = onTakePhoto,
                    onClearPendingImage = viewModel::clearPendingImage,
                    audioInAvailable = state.modelSupportsAudio,
                    isRecordingClip = isRecordingClip,
                    pendingAudioPath = state.pendingAudioPath,
                    onToggleAudioClip = viewModel::toggleAudioClip,
                    onClearPendingAudio = viewModel::clearPendingAudio,
                    onCancelAudioClip = viewModel::cancelAudioClip,
                    pendingFile = state.pendingFile,
                    onFilePicked = viewModel::attachPickedFile,
                    onClearPendingFile = viewModel::clearPendingFile,
                ),
                actions = ComposerActions(
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                    onStartDictation = viewModel::startDictation,
                    onStopDictation = viewModel::stopDictation,
                    onToggleWebSearch = viewModel::setWebSearchEnabled,
                    onToggleReasoning = viewModel::setReasoningEnabled,
                    onCancelEdit = viewModel::cancelEdit,
                ),
                focusRequester = composerFocusRequester,
            )
    }

    selectedToolCall?.let { invocation ->
        ToolCallDetailSheet(
            toolName = invocation.toolName,
            argsJson = invocation.argsJson,
            resultJson = invocation.resultJson,
            error = invocation.error,
            onDismiss = { selectedToolCall = null },
        )
    }

    selectedThinkingId?.let { id ->
        val trace = state.messages.firstOrNull { it is ChatMessage.Thinking && it.id == id }
            as? ChatMessage.Thinking
        if (trace != null) {
            ThinkingDetailSheet(
                text = trace.text,
                durationMs = trace.durationMs,
                isStreaming = trace.isStreaming,
                colors = markdownColors,
                typography = markdownTypography,
                onDismiss = { selectedThinkingId = null },
            )
        } else {
            // Trace vanished (e.g. stream errored before persistence). Close the sheet.
            LaunchedEffect(id) { selectedThinkingId = null }
        }
    }

    messageMenuTarget?.let { target ->
        UserMessageActionSheet(
            onCopy = { clipboard.setText(AnnotatedString(target.text)) },
            onSelectText = { selectTextTarget = target.text },
            onEdit = { viewModel.beginEditUserMessage(target.id, target.text) },
            onDismiss = { messageMenuTarget = null },
        )
    }

    selectTextTarget?.let { text ->
        SelectMessageTextSheet(text = text, onDismiss = { selectTextTarget = null })
    }

    if (showRenameDialog) {
        RenameChatDialog(
            initialTitle = state.title,
            onConfirm = { newTitle ->
                viewModel.renameCurrent(newTitle)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    state.pendingConfirm?.let { prompt ->
        ToolConfirmDialog(
            prompt = prompt,
            onResolve = { accepted, remember ->
                viewModel.confirmToolOp(prompt.opId, accepted = accepted, remember = remember)
            },
        )
    }
}

/**
 * The chat header's trailing actions, by state: exit incognito, start incognito on a fresh chat, or a saved
 * chat's Pin toggle plus its action sheet, the same [chatActions] its drawer row offers. The header
 * crossfades between them (a label is its identity).
 */
private fun chatHeaderActions(
    state: ChatUiState,
    onSetIncognito: (Boolean) -> Unit,
    runner: ActionRunner<Chat>,
    onNewChat: () -> Unit,
): List<HeaderAction> = when {
    state.isIncognito -> listOf(HeaderAction(Res.drawable.ic_lc_x, "Exit incognito", onClick = { onSetIncognito(false) }))
    state.chatId.isBlank() ->
        listOf(HeaderAction(Res.drawable.ic_lc_ghost, "Start incognito chat", onClick = { onSetIncognito(true) }))
    else -> {
        // The open chat as the action list sees it: only id, title, pin and archive are read.
        val chat = Chat(
            id = state.chatId,
            title = state.title,
            createdAt = 0L,
            updatedAt = 0L,
            isStarred = state.isStarred,
            isArchived = state.isArchived,
        )
        listOf(
            HeaderAction(
                iconRes = if (state.isStarred) Res.drawable.ic_lc_pin_off else Res.drawable.ic_lc_pin,
                label = if (state.isStarred) "Unpin" else "Pin",
                // The list's own Pin, so the toggle and the sheet can never disagree.
                onClick = { runner.entriesFor(chat).firstOrNull { it.key == "pin" }?.onClick?.invoke() },
            ),
            HeaderAction(
                iconRes = Res.drawable.ic_lc_ellipsis_vertical,
                label = "More",
                // The chat's actions, then the one page action a phone would otherwise reach only through the drawer.
                menu = HeaderMenu(
                    header = chatSheetHeader(chat),
                    items = runner.menuFor(chat) + AppDropdownDivider +
                        AppMenuAction(label = "New chat", iconRes = Res.drawable.ic_lc_plus, onClick = onNewChat),
                ),
            ),
        )
    }
}

/** The scrolling middle of the chat: empty states (normal / incognito) or the reversed message list. */
@Composable
private fun ChatMessagesPane(
    state: ChatUiState,
    reversedMessages: List<ChatMessage>,
    listState: LazyListState,
    markdownColors: MarkdownColors,
    markdownTypography: MarkdownTypography,
    replyActions: ReplyActions,
    refreshingFlow: StateFlow<Boolean>,
    onRefresh: () -> Unit,
    onSetUpModel: () -> Unit,
    onToolClick: (ChatMessage.ToolInvocation) -> Unit,
    onThinkingClick: (ChatMessage.Thinking) -> Unit,
    onUserLongPress: (ChatMessage.User) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = state.isIncognito,
        modifier = modifier,
        transitionSpec = {
            val enterSlide = if (targetState) -1 else 1
            val exitSlide = if (targetState) 1 else -1
            (
                slideInVertically(animationSpec = tween(220)) { it / 8 * enterSlide } +
                    fadeIn(animationSpec = tween(220))
            ).togetherWith(
                slideOutVertically(animationSpec = tween(180)) { it / 8 * exitSlide } +
                    fadeOut(animationSpec = tween(180)),
            )
        },
        label = "incognitoContent",
    ) { incognito ->
        if (reversedMessages.isEmpty()) {
            // Rows not read yet: draw nothing for that one query rather than the greeting.
            if (!state.messagesLoaded) {
                Box(modifier = Modifier.fillMaxSize())
            } else if (incognito) {
                IncognitoEmptyState(modifier = Modifier.fillMaxSize())
            } else {
                EmptyStateGreeting(
                    errorMessage = state.errorMessage,
                    // Mirrors the "No model" pill: prompt whenever nothing is SELECTED, not just
                    // when nothing is downloaded — a connected provider makes remote models
                    // available without a `Use` tap, which left chat unusable and unexplained.
                    // Only a SETTLED answer may prompt: the choice document saying nothing is picked (known on
                    // the first frame), or the registry saying the pick is unusable. One label either way —
                    // the picker's own first page covers "nothing set up yet" — so the button never re-words
                    // itself when the registry lands.
                    modelPrompt = when {
                        !state.promptsForModel -> null
                        state.unavailableModel != null -> "${state.unavailableModel} isn't available right now."
                        else -> "Select a model to begin."
                    },
                    modelActionLabel = "Choose a model",
                    onSetUpModel = onSetUpModel,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            val refreshing by refreshingFlow.collectAsStateWithLifecycle()
            AppPullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 16.dp,
                        bottom = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(reversedMessages, key = { it.id }) { msg ->
                        MessageItem(
                            msg = msg,
                            markdownColors = markdownColors,
                            markdownTypography = markdownTypography,
                            onToolClick = onToolClick,
                            onThinkingClick = onThinkingClick,
                            onUserLongPress = onUserLongPress,
                            replyActions = replyActions,
                        )
                    }
                }
            }
        }
    }
}

/** The write-gate's confirm dialog, with per-op "don't ask again" state. */
@Composable
private fun ToolConfirmDialog(
    prompt: ToolConfirmPrompt,
    onResolve: (accepted: Boolean, remember: Boolean) -> Unit,
) {
    val accent = when (prompt.severity) {
        WriteConfirmGate.Severity.INFO -> MaterialTheme.colorScheme.primary
        WriteConfirmGate.Severity.WARN -> MaterialTheme.colorScheme.tertiary
        WriteConfirmGate.Severity.DANGER -> MaterialTheme.colorScheme.error
    }
    var dontAskAgain by remember(prompt.opId) { mutableStateOf(false) }
    ConfirmDialog(
        title = "Allow ${prompt.toolName}?",
        message = prompt.summary,
        confirmLabel = "Allow",
        confirmColor = accent,
        onConfirm = { onResolve(true, dontAskAgain) },
        onCancel = { onResolve(false, dontAskAgain) },
        onDismiss = {},
        extraContent = {
            prompt.details.forEach { kv ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${kv.key}: ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(text = kv.value, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { dontAskAgain = !dontAskAgain },
            ) {
                Checkbox(checked = dontAskAgain, onCheckedChange = { dontAskAgain = it })
                Text("Don't ask again for this tool", style = MaterialTheme.typography.bodyMedium)
            }
        },
    )
}

/**
 * Drives the chat composer's focus + soft keyboard. A leaf with no UI so its reactive reads (lifecycle
 * state, window focus, IME visibility — all of which tick every nav-slide / IME-animation frame) only
 * invalidate this node, never the heavy [ChatScreen] body.
 *
 * Two gates:
 *  - "Don't fight the system" — [composerShouldFocus]: this window holds input focus (no Dialog/Popup
 *    sheet covering it), the in-window drawer is closed, AND chat is the settled top nav destination.
 *    The RESUMED check matters because a drawer item taps `nav.navigate` before the drawer finishes
 *    closing; NavDisplay caps the outgoing entry below RESUMED, so we don't flash the keyboard open under
 *    the screen sliding in.
 *  - "Don't fight the user" — [wantsKeyboard]: remembered intent, seeded true so a freshly opened chat
 *    auto-focuses, then mirrors the user's own IME toggles. If they dismissed the keyboard we must not
 *    force it back open when an overlay closes; only someone who was typing gets it restored.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposerFocusController(
    isDrawerOpen: Boolean,
    focusRequester: FocusRequester,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val windowHasFocus = LocalWindowInfo.current.isWindowFocused
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isChatResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val composerShouldFocus = windowHasFocus && !isDrawerOpen && isChatResumed

    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var wantsKeyboard by remember { mutableStateOf(true) }
    var keyboardWasShown by remember { mutableStateOf(false) }

    // Acquire / release only on the active-surface edge — NOT on every imeVisible change, else
    // dismissing the keyboard would immediately re-trigger show() and reopen it.
    LaunchedEffect(composerShouldFocus) {
        if (composerShouldFocus) {
            if (wantsKeyboard) {
                // requestFocus throws if the field node is detaching (e.g. mid nav-away); ignore —
                // the effect re-runs with a fresh node when the chat is active again.
                runCatching { focusRequester.requestFocus() }
                keyboardController?.show()
            }
        } else {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            keyboardWasShown = false // reset the latch for the next activation
        }
    }

    // While chat is the active surface, mirror the user's keyboard toggles into intent. The
    // keyboardWasShown latch ignores the transient pre-show frame so our own show() above isn't
    // misread as a dismiss; once it has actually shown, a later hide is the user closing it.
    LaunchedEffect(composerShouldFocus, imeVisible) {
        if (!composerShouldFocus) return@LaunchedEffect
        if (imeVisible) {
            keyboardWasShown = true
            wantsKeyboard = true
        } else if (keyboardWasShown) {
            wantsKeyboard = false
        }
    }
}
