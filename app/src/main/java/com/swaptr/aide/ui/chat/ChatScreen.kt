package com.swaptr.aide.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import coil.compose.AsyncImage
import com.mikepenz.markdown.compose.Markdown
import com.swaptr.aide.R
import com.mikepenz.markdown.m3.markdownColor
import com.swaptr.aide.ui.common.AidePill
import com.swaptr.aide.ui.common.AppDropdownDivider
import com.swaptr.aide.ui.common.AppDropdownMenu
import com.swaptr.aide.ui.common.AppMenuAction
import com.swaptr.aide.ui.common.AppPage
import com.swaptr.aide.ui.common.AppSheet
import com.swaptr.aide.ui.common.RenameChatDialog
import com.swaptr.aide.ui.models.ModelsContent
import com.swaptr.aide.ui.models.ModelsViewModel
import com.swaptr.aide.ui.theme.aideMarkdownTypography
import java.io.File
import java.util.Calendar

private enum class TopBarActionMode { IncognitoExit, GhostStart, Overflow }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    onOpenDrawer: () -> Unit,
    onOpenModels: () -> Unit,
    onNavigateToChat: (String) -> Unit = {},
    onNewChat: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val openDrawer: () -> Unit = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onOpenDrawer()
    }
    var showModelSheet by remember { mutableStateOf(false) }

    LaunchedEffect(state.openPickerRequest) {
        if (state.openPickerRequest) {
            showModelSheet = true
            viewModel.consumeOpenPicker()
        }
    }

    // PickVisualMedia variant — no runtime READ_MEDIA_IMAGES needed at minSdk 31.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::attachImageFromUri) }

    // FileProvider cache URI then re-import to filesDir/attachments (outlives cache eviction).
    var pendingCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        pendingCameraUri = null
        viewModel.onCameraCaptured(success)
    }
    val launchCamera: () -> Unit = {
        val uri = viewModel.prepareCameraCapture()
        pendingCameraUri = uri
        cameraLauncher.launch(uri)
    }
    val onTakePhoto: () -> Unit = {
        viewModel.requestCameraAccess()
    }
    LaunchedEffect(Unit) {
        viewModel.cameraReady.collect { ready ->
            if (ready) launchCamera()
            else Toast.makeText(context, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }
    }
    val onPickPhoto: () -> Unit = {
        photoPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    // ACTION_PICK grants temporary URI permission so querying doesn't need READ_CONTACTS.
    val pendingContactPick by viewModel.pendingContactPick.collectAsStateWithLifecycle()
    var activeContactPickOpId by remember { mutableStateOf<String?>(null) }
    val contactPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val opId = activeContactPickOpId
        activeContactPickOpId = null
        if (opId == null) return@rememberLauncherForActivityResult
        val data = result.data?.data
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            viewModel.resolveContactPick(opId, null)
            return@rememberLauncherForActivityResult
        }
        val picked = runCatching {
            context.contentResolver.query(
                data,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val number = cursor.getString(0).orEmpty()
                    val name = cursor.getString(1)
                    if (number.isBlank()) null
                    else com.swaptr.aide.domain.tools.phone.ContactPickGate.ContactPickResult(
                        displayName = name,
                        number = number,
                    )
                } else null
            }
        }.getOrNull()
        viewModel.resolveContactPick(opId, picked)
    }
    LaunchedEffect(pendingContactPick) {
        val opId = pendingContactPick ?: return@LaunchedEffect
        if (activeContactPickOpId == opId) return@LaunchedEffect
        activeContactPickOpId = opId
        val intent = android.content.Intent(
            android.content.Intent.ACTION_PICK,
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        )
        runCatching { contactPickerLauncher.launch(intent) }.onFailure {
            activeContactPickOpId = null
            viewModel.resolveContactPick(opId, null)
        }
    }

    // reverseLayout anchors item 0 to viewport bottom — streaming pins naturally without
    // per-token scrollToItem races. Only programmatic scroll is on new user send.
    val reversedMessages = remember(state.messages) { state.messages.asReversed() }

    // Hoist Markdown styling so per-token recomps reuse instances; else stability check
    // is invalidated and Compose can't skip frozen blocks.
    val onBg = MaterialTheme.colorScheme.onBackground
    val markdownColors = markdownColor(text = onBg)
    val markdownTypography = aideMarkdownTypography()

    val newestUserId = remember(reversedMessages) {
        reversedMessages.firstOrNull { it is ChatMessage.User }?.id
    }
    LaunchedEffect(newestUserId) {
        if (newestUserId != null) listState.scrollToItem(0)
    }

    var selectedToolCall by remember { mutableStateOf<ChatMessage.ToolInvocation?>(null) }
    var selectedThinkingId by remember { mutableStateOf<Long?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    AppPage(
        titleContent = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                ModelSelectorPill(
                    label = state.modelDisplayName.ifBlank { "No model" },
                    provider = state.modelProvider,
                    onClick = { showModelSheet = true },
                )
            }
        },
        topAppBarColors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
        navigationIcon = {
            IconButton(onClick = openDrawer) {
                Icon(painterResource(R.drawable.ic_lc_menu), contentDescription = "Menu")
            }
        },
        actions = {
                    val mode = when {
                        state.isIncognito -> TopBarActionMode.IncognitoExit
                        state.chatId.isBlank() -> TopBarActionMode.GhostStart
                        else -> TopBarActionMode.Overflow
                    }
                    Crossfade(
                        targetState = mode,
                        animationSpec = tween(180),
                        label = "topBarActionMode",
                    ) { current ->
                        when (current) {
                            TopBarActionMode.IncognitoExit ->
                                IconButton(onClick = { viewModel.setIncognito(false) }) {
                                    Icon(
                                        painterResource(R.drawable.ic_lc_x),
                                        contentDescription = "Exit incognito",
                                    )
                                }
                            TopBarActionMode.GhostStart ->
                                IconButton(onClick = { viewModel.setIncognito(true) }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_lc_ghost),
                                        contentDescription = "Start incognito chat",
                                    )
                                }
                            TopBarActionMode.Overflow ->
                                Box {
                                    IconButton(onClick = { overflowOpen = true }) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_lc_ellipsis_vertical),
                                            contentDescription = "Chat options",
                                        )
                                    }
                                    AppDropdownMenu(
                                        expanded = overflowOpen,
                                        onDismissRequest = { overflowOpen = false },
                                        items = listOf(
                                            AppMenuAction(
                                                label = "Rename",
                                                iconRes = R.drawable.ic_lc_pencil,
                                            ) { showRenameDialog = true },
                                            AppMenuAction(
                                                label = if (state.isStarred) "Unstar" else "Star",
                                                iconRes = R.drawable.ic_lc_star,
                                            ) { viewModel.setStarredCurrent(!state.isStarred) },
                                            AppMenuAction(
                                                label = if (state.isArchived) "Unarchive" else "Archive",
                                                iconRes = R.drawable.ic_lc_folder,
                                            ) {
                                                if (state.isArchived) {
                                                    viewModel.setArchivedCurrent(false)
                                                } else {
                                                    viewModel.setArchivedCurrent(true) { replacementId ->
                                                        onNavigateToChat(replacementId)
                                                    }
                                                }
                                            },
                                            AppMenuAction(
                                                label = "Delete",
                                                iconRes = R.drawable.ic_lc_trash,
                                                destructive = true,
                                            ) { showDeleteDialog = true },
                                            AppDropdownDivider,
                                            AppMenuAction(
                                                label = "New chat",
                                                iconRes = R.drawable.ic_lc_plus,
                                            ) { onNewChat() },
                                        ),
                                    )
                                }
                        }
                    }
                },
        scrollable = false,
    ) {
        if (state.noModelDownloaded) {
                NoModelBanner(onOpenModels = onOpenModels)
            }
            AnimatedContent(
                targetState = state.isIncognito,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
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
                    if (incognito) {
                        IncognitoEmptyState(modifier = Modifier.fillMaxSize())
                    } else {
                        EmptyStateGreeting(
                            errorMessage = state.errorMessage,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
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
                                onToolClick = { selectedToolCall = it },
                                onThinkingClick = { selectedThinkingId = it.id },
                            )
                        }
                    }
                }
            }

            if (state.errorMessage != null && state.messages.isNotEmpty()) {
                ErrorBanner(message = state.errorMessage!!)
            }
            Composer(
                composerState = viewModel.composerState,
                isGenerating = state.isGenerating,
                busy = state.composerBusy,
                isDictating = state.isDictating,
                webSearchEnabled = state.webSearchEnabled,
                webSearchAvailable = state.modelSupportsTools,
                visionAvailable = state.modelSupportsVision,
                pendingImagePath = state.pendingImagePath,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                onStartDictation = viewModel::startDictation,
                onStopDictation = viewModel::stopDictation,
                onToggleWebSearch = viewModel::setWebSearchEnabled,
                onPickPhoto = onPickPhoto,
                onTakePhoto = onTakePhoto,
                onClearPendingImage = viewModel::clearPendingImage,
            )
    }

    if (showModelSheet) {
        ModelPickerSheet(
            onDismiss = { showModelSheet = false },
            onSelect = { id ->
                viewModel.selectModel(id)
                showModelSheet = false
            },
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
                onDismiss = { selectedThinkingId = null },
            )
        } else {
            // Trace vanished (e.g. stream errored before persistence). Close the sheet.
            LaunchedEffect(id) { selectedThinkingId = null }
        }
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

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete chat?") },
            text = { Text("\"${state.title.ifBlank { "New chat" }}\" and its messages will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteCurrent { replacementId ->
                        onNavigateToChat(replacementId)
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }

    state.pendingConfirm?.let { prompt ->
        val severity = prompt.severity
        val accent = when (severity) {
            com.swaptr.aide.domain.llm.gates.WriteConfirmGate.Severity.INFO ->
                MaterialTheme.colorScheme.primary
            com.swaptr.aide.domain.llm.gates.WriteConfirmGate.Severity.WARN ->
                MaterialTheme.colorScheme.tertiary
            com.swaptr.aide.domain.llm.gates.WriteConfirmGate.Severity.DANGER ->
                MaterialTheme.colorScheme.error
        }
        AlertDialog(
            onDismissRequest = { viewModel.confirmToolOp(prompt.opId, accepted = false) },
            title = { Text("Allow ${prompt.toolName}?") },
            text = {
                Column {
                    Text(prompt.summary)
                    if (prompt.details.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        prompt.details.forEach { kv ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${kv.key}: ",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = kv.value,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmToolOp(prompt.opId, accepted = true) },
                    colors = ButtonDefaults.textButtonColors(contentColor = accent),
                ) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.confirmToolOp(prompt.opId, accepted = false) }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ModelPickerSheet(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    modelsViewModel: ModelsViewModel = hiltViewModel(),
) {
    AppSheet(
        onDismiss = onDismiss,
        title = "Select model",
        contentPadding = PaddingValues(0.dp),
        contentSpacing = 0.dp,
        scrollableContent = false,
    ) { controller ->
        ModelsContent(
            viewModel = modelsViewModel,
            containerColor = Color.Transparent,
            onRowClick = { picked ->
                controller.close { onSelect(picked.spec.id) }
            },
        )
    }
}

@Composable
private fun ModelSelectorPill(
    label: String,
    provider: com.swaptr.aide.data.catalog.ProviderId?,
    onClick: () -> Unit,
) {
    val providerIcon = when (provider) {
        com.swaptr.aide.data.catalog.ProviderId.LOCAL -> R.drawable.ic_lc_smartphone
        com.swaptr.aide.data.catalog.ProviderId.OLLAMA -> R.drawable.ic_lc_cloud
        null -> null
    }
    AidePill(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentPadding = PaddingValues(
            start = if (providerIcon != null) 12.dp else 16.dp,
            end = 12.dp, top = 8.dp, bottom = 8.dp,
        ),
        leading = providerIcon?.let { res ->
            {
                Icon(
                    painterResource(res),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        },
        trailing = {
            Icon(
                painterResource(R.drawable.ic_lc_chevron_down),
                contentDescription = "Switch model",
                modifier = Modifier.size(18.dp),
            )
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun NoModelBanner(onOpenModels: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Set up a model to begin.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenModels) { Text("Open") }
        }
    }
}

@Composable
private fun IncognitoEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lc_ghost),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Incognito chats aren't saved to history or used to train models. " +
                    "Closing the chat discards it.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun EmptyStateGreeting(
    errorMessage: String?,
    modifier: Modifier = Modifier,
) {
    val greeting = rememberGreeting()
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            if (errorMessage != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun MessageItem(
    msg: ChatMessage,
    markdownColors: com.mikepenz.markdown.model.MarkdownColors,
    markdownTypography: com.mikepenz.markdown.model.MarkdownTypography,
    onToolClick: (ChatMessage.ToolInvocation) -> Unit,
    onThinkingClick: (ChatMessage.Thinking) -> Unit,
) {
    when (msg) {
        is ChatMessage.User -> UserMessageRow(msg)
        is ChatMessage.Assistant -> AssistantMessageBlock(
            msg = msg,
            colors = markdownColors,
            typography = markdownTypography,
        )
        is ChatMessage.ToolInvocation -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            ToolCallChip(
                toolName = msg.toolName,
                isRunning = msg.isRunning,
                hasError = msg.error != null,
                onClick = { onToolClick(msg) },
            )
        }
        is ChatMessage.Thinking -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            ThinkingChip(
                isStreaming = msg.isStreaming,
                durationMs = msg.durationMs,
                onClick = { onThinkingClick(msg) },
            )
        }
    }
}

@Composable
private fun UserMessageRow(msg: ChatMessage.User) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(22.dp))
                .padding(6.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (msg.imagePath != null) {
                AsyncImage(
                    model = File(msg.imagePath),
                    contentDescription = "Attached image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
                if (msg.text.isNotEmpty()) Spacer(Modifier.height(6.dp))
            }
            if (msg.text.isNotEmpty() || msg.imagePath == null) {
                Text(
                    text = msg.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun AttachmentPreviewRow(
    path: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AsyncImage(
                model = File(path),
                contentDescription = "Pending attachment",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(20.dp)
                    .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_lc_x),
                    contentDescription = "Remove attachment",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun AssistantMessageBlock(
    msg: ChatMessage.Assistant,
    colors: com.mikepenz.markdown.model.MarkdownColors,
    typography: com.mikepenz.markdown.model.MarkdownTypography,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when {
            msg.isStreaming && msg.text.isEmpty() -> {
                Text(
                    text = "…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            msg.isStreaming -> {
                // Finished blocks → stable Markdown (skipped on recompose); tail → plain Text
                // (mikepenz Markdown remount-flashes on every token; Text just remeasures tail).
                val blocks = remember(msg.text) { splitMarkdownBlocks(msg.text) }
                blocks.forEachIndexed { index, block ->
                    val isTail = index == blocks.lastIndex
                    if (isTail) {
                        Text(
                            text = block,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    } else {
                        key(index) {
                            Markdown(
                                content = block,
                                colors = colors,
                                typography = typography,
                            )
                        }
                    }
                }
            }
            else -> {
                Markdown(
                    content = msg.text,
                    colors = colors,
                    typography = typography,
                )
            }
        }
    }
}

private fun splitMarkdownBlocks(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val blocks = mutableListOf<String>()
    val buf = StringBuilder()
    var inFence = false
    for (line in text.split('\n')) {
        val isFence = line.trimStart().startsWith("```")
        if (line.isBlank() && !inFence) {
            if (buf.isNotEmpty()) {
                blocks += buf.toString()
                buf.clear()
            }
        } else {
            if (buf.isNotEmpty()) buf.append('\n')
            buf.append(line)
            if (isFence) inFence = !inFence
        }
    }
    if (buf.isNotEmpty()) blocks += buf.toString()
    return blocks
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    composerState: TextFieldState,
    isGenerating: Boolean,
    busy: Boolean,
    isDictating: Boolean,
    webSearchEnabled: Boolean,
    webSearchAvailable: Boolean,
    visionAvailable: Boolean,
    pendingImagePath: String?,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onStartDictation: () -> Unit,
    onStopDictation: () -> Unit,
    onToggleWebSearch: (Boolean) -> Unit,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onClearPendingImage: () -> Unit,
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    // VM-owned composerState lets dictation writes mutate without value/onValueChange
    // clobbering the cursor; state-based BasicTextField owns selection so caret tracks tail.
    val isBlank by remember(composerState) {
        derivedStateOf { composerState.text.isBlank() }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(28.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        if (pendingImagePath != null) {
            AttachmentPreviewRow(
                path = pendingImagePath,
                onRemove = onClearPendingImage,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                state = composerState,
                enabled = !busy,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 17.sp,
                ),
                lineLimits = TextFieldLineLimits.MultiLine(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                onKeyboardAction = { if (!isBlank && !busy) onSend() },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            if (isBlank) {
                Text(
                    text = if (busy) "…" else "Let Aide assist you...",
                    style = TextStyle(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 17.sp,
                    ),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var attachSheetOpen by remember { mutableStateOf(false) }
            // Keep tappable on no-vision models so the user sees the "why" toast.
            val attachAlpha = if (visionAvailable) 0.7f else 0.35f
            IconButton(
                onClick = {
                    if (!visionAvailable) {
                        Toast.makeText(
                            context,
                            "This model can't read images. Pick a vision-capable model to attach.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        attachSheetOpen = true
                    }
                },
                enabled = !busy,
            ) {
                Icon(
                    painterResource(R.drawable.ic_lc_plus),
                    contentDescription = "Attach",
                    tint = LocalContentColor.current.copy(alpha = attachAlpha),
                )
            }
            if (attachSheetOpen) {
                AttachmentPickerSheet(
                    onDismiss = { attachSheetOpen = false },
                    onCamera = {
                        attachSheetOpen = false
                        onTakePhoto()
                    },
                    onPhotos = {
                        attachSheetOpen = false
                        onPickPhoto()
                    },
                )
            }
            WebSearchChip(
                enabled = webSearchEnabled,
                available = webSearchAvailable,
                busy = busy,
                onToggle = onToggleWebSearch,
            )
            Spacer(Modifier.weight(1f))
            MicButton(
                isDictating = isDictating,
                enabled = !busy || isDictating,
                onStart = onStartDictation,
                onStop = onStopDictation,
            )
            Spacer(Modifier.width(4.dp))
            SendOrStopButton(
                isGenerating = isGenerating,
                canSend = !busy && !isBlank,
                onSend = onSend,
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun WebSearchChip(
    enabled: Boolean,
    available: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val tappable = available && !busy
    FilterChip(
        selected = available && enabled,
        onClick = { onToggle(!enabled) },
        enabled = tappable,
        label = { Text("Web", fontSize = 13.sp, fontWeight = FontWeight.Medium) },
        leadingIcon = {
            Icon(
                painterResource(R.drawable.ic_lc_globe),
                contentDescription = "Web search",
                modifier = Modifier.size(16.dp),
            )
        },
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier.padding(start = 2.dp),
    )
}

@Composable
private fun MicButton(
    isDictating: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "mic-pulse")
    val alpha by infinite.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            tween(700, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "mic-pulse-alpha",
    )
    val tint = if (isDictating) MaterialTheme.colorScheme.primary
               else LocalContentColor.current.copy(alpha = 0.7f)
    val displayAlpha = if (isDictating) alpha else 1f
    IconButton(
        onClick = { if (isDictating) onStop() else onStart() },
        enabled = enabled,
    ) {
        Icon(
            painterResource(R.drawable.ic_lc_mic),
            contentDescription = if (isDictating) "Stop dictation" else "Voice input",
            tint = tint.copy(alpha = displayAlpha.coerceIn(0f, 1f)),
        )
    }
}

@Composable
private fun SendOrStopButton(
    isGenerating: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val active = isGenerating || canSend
    val bg = if (active) Color.White else Color.White.copy(alpha = 0.25f)
    val fg = if (active) Color.Black else Color.White.copy(alpha = 0.6f)
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(bg, CircleShape)
            .clickable(enabled = active) {
                if (isGenerating) onStop() else if (canSend) onSend()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isGenerating) {
            Icon(painterResource(R.drawable.ic_lc_square), contentDescription = "Stop", tint = fg, modifier = Modifier.size(20.dp))
        } else {
            Icon(
                painterResource(R.drawable.ic_lc_arrow_right),
                contentDescription = "Send",
                tint = fg,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private enum class TimeOfDay { Morning, Afternoon, Evening, Night, LateNight }

private fun timeOfDay(hour: Int): TimeOfDay = when (hour) {
    in 5..11 -> TimeOfDay.Morning
    in 12..16 -> TimeOfDay.Afternoon
    in 17..20 -> TimeOfDay.Evening
    in 21..23 -> TimeOfDay.Night
    else -> TimeOfDay.LateNight
}

private val GreetingsByTime: Map<TimeOfDay, List<String>> = mapOf(
    TimeOfDay.Morning to listOf(
        "Fresh start?",
        "First thought of the day?",
        "Coffee kicking in yet?",
        "What's on the docket?",
        "Ready when you are.",
        "Morning brain, let's go.",
    ),
    TimeOfDay.Afternoon to listOf(
        "Mid-day momentum?",
        "What are we cracking?",
        "Pick up where you left off?",
        "Post-lunch thoughts?",
        "Let's make the afternoon count.",
        "Back at it?",
    ),
    TimeOfDay.Evening to listOf(
        "Wrapping up loose ends?",
        "One more thing before dinner?",
        "End-of-day puzzle?",
        "Golden hour ideas?",
        "What's the evening project?",
        "Time to think out loud?",
    ),
    TimeOfDay.Night to listOf(
        "Quiet hours, big thoughts.",
        "Night shift?",
        "Let's keep it low and slow.",
        "What's keeping you up?",
        "Brain still buzzing?",
        "After-hours brainstorm?",
    ),
    TimeOfDay.LateNight to listOf(
        "Burning the midnight oil?",
        "We meet again, night owl.",
        "Still up? Same.",
        "3 a.m. epiphany?",
        "Sleep can wait.",
        "Just you, me, and the dark.",
    ),
)

@Composable
private fun rememberGreeting(): String = remember {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    GreetingsByTime[timeOfDay(hour)].orEmpty().randomOrNull() ?: "Hello"
}
