package com.sabreware.aide.ui.chat

import com.sabreware.aide.core.designsystem.AppMenuToggle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabreware.aide.core.common.media.PendingFileAttachment
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppIconButton
import com.sabreware.aide.core.designsystem.VoiceBars
import com.sabreware.aide.core.designsystem.VoiceRecordButton
import com.sabreware.aide.core.designsystem.currentTimeMillis
import com.sabreware.aide.core.designsystem.rememberMicActivity
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.theme.LocalAppSans
import com.sabreware.aide.ui.settings.mcp.openConnectorFlow
import com.sabreware.aide.core.designsystem.navigation.navigator
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import org.jetbrains.compose.resources.painterResource

/**
 * The three attachment kinds (image, voice clip, file): pending state, the callbacks that mutate it, and
 * the capability flags that gate each affordance. Grouped so the next attachment kind grows this class,
 * not [Composer]'s parameter list. Constructed plainly at the call site — it holds ViewModel method refs,
 * so construction is cheap and `remember` would be noise.
 */
internal data class ComposerAttachments(
    val visionAvailable: Boolean,
    /** Whether the platform has a camera at all — omits the Camera tile rather than disabling it. */
    val cameraAvailable: Boolean,
    val pendingImagePath: String?,
    /** Null where the host offers no image picker — the Photos tile is then omitted. */
    val onPickPhoto: (() -> Unit)?,
    /** Null where the host offers no camera launcher — the Camera tile is then omitted. */
    val onTakePhoto: (() -> Unit)?,
    val onClearPendingImage: () -> Unit,
    val audioInAvailable: Boolean,
    val isRecordingClip: Boolean,
    val pendingAudioPath: String?,
    val onToggleAudioClip: () -> Unit,
    val onClearPendingAudio: () -> Unit,
    val onCancelAudioClip: () -> Unit,
    val pendingFile: PendingFileAttachment?,
    val onFilePicked: (fileName: String, readBytes: suspend () -> ByteArray?) -> Unit,
    val onClearPendingFile: () -> Unit,
)

/** The composer's turn-level actions: send/stop, dictation, the per-chat toggles, and edit cancel. */
internal data class ComposerActions(
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onStartDictation: () -> Unit,
    val onStopDictation: () -> Unit,
    val onToggleWebSearch: (Boolean) -> Unit,
    val onToggleReasoning: (Boolean) -> Unit,
    val onCancelEdit: () -> Unit,
)

@Composable
internal fun Composer(
    composerState: TextFieldState,
    isGenerating: Boolean,
    busy: Boolean,
    modelReady: Boolean,
    isDictating: Boolean,
    webSearchEnabled: Boolean,
    webSearchAvailable: Boolean,
    reasoningEnabled: Boolean,
    thinkingAvailable: Boolean,
    isEditing: Boolean,
    attachments: ComposerAttachments,
    actions: ComposerActions,
    focusRequester: FocusRequester,
) {
    // Auto-focus is driven by the caller (ChatScreen) via [focusRequester] so it can release/re-acquire
    // focus as overlays (drawer, model sheet) come and go instead of fighting them on every mount.
    // VM-owned composerState lets dictation writes mutate without value/onValueChange
    // clobbering the cursor; state-based BasicTextField owns selection so caret tracks tail.
    val isBlank by remember(composerState) {
        derivedStateOf { composerState.text.isBlank() }
    }

    // Action availability — single source of truth. A control is live only when the model can do that
    // thing AND the engine is idle; capability flags are already false with no model, so "no model"
    // collapses to "no features". "+" always opens the Attach sheet (items gated inside); in-progress
    // toggles stay enabled so an active dictation / recording can always be stopped.
    val canOpenAttach = !busy                                    // the + button only opens the sheet
    val canAttachImage = attachments.visionAvailable && !busy    // gallery + camera, gated inside the sheet
    val canRecordVoice = attachments.audioInAvailable && !busy   // live mic record, gated inside the sheet
    val canDictate = !busy || isDictating                        // STT writes to the composer; no LLM needed
    val canSend = modelReady && !busy &&
        (!isBlank || attachments.pendingAudioPath != null || attachments.pendingFile != null)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(28.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        if (isEditing) {
            EditingMessagePill(onCancel = actions.onCancelEdit)
        }
        if (attachments.pendingImagePath != null) {
            AttachmentPreviewRow(
                path = attachments.pendingImagePath,
                onRemove = attachments.onClearPendingImage,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        if (attachments.isRecordingClip) {
            // Record is started from the Attach sheet, which then dismisses — so the only stop
            // affordance lives here, shown for the duration of the recording.
            RecordingClipChip(
                onStop = attachments.onToggleAudioClip,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        } else if (attachments.pendingAudioPath != null) {
            // Draft clip preview: playable + removable before it's sent.
            AudioClipChip(
                path = attachments.pendingAudioPath,
                onRemove = attachments.onClearPendingAudio,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        if (attachments.pendingFile != null) {
            PendingFileChip(
                file = attachments.pendingFile,
                onRemove = attachments.onClearPendingFile,
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
                    fontFamily = LocalAppSans.current,
                    fontSize = 17.sp,
                ),
                lineLimits = TextFieldLineLimits.MultiLine(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                onKeyboardAction = { if (canSend) actions.onSend() },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            if (isBlank) {
                Text(
                    text = "Message Aide",
                    style = TextStyle(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = LocalAppSans.current,
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
            var attachSheetOpen by rememberSaveable { mutableStateOf(false) }
            val nav = navigator()
            var recordSheetOpen by rememberSaveable { mutableStateOf(false) }
            // FileKit = the native picker on every target (SAF / UIDocumentPicker / NSOpenPanel / XDG
            // portal). Any type — the VM classifies and gates with a reason, so nothing is pre-filtered.
            val filePicker = rememberFilePickerLauncher(type = FileKitType.File()) { picked ->
                if (picked != null) attachments.onFilePicked(picked.name) { picked.readBytes() }
            }
            IconButton(
                onClick = { attachSheetOpen = true },
                enabled = canOpenAttach,
            ) {
                Icon(
                    painterResource(Res.drawable.ic_lc_plus),
                    contentDescription = "Attach",
                    tint = LocalContentColor.current.copy(alpha = if (canOpenAttach) 0.7f else 0.35f),
                )
            }
            if (attachSheetOpen) {
                // Tiles call sheet.close { … }, which animates out then runs the action and fires
                // onDismiss — so the raw VM actions are passed (no separate close needed here).
                AddToChatSheet(
                    onDismiss = { attachSheetOpen = false },
                    canAttachImage = canAttachImage,
                    cameraAvailable = attachments.cameraAvailable,
                    canAttachAudio = canRecordVoice,
                    onCamera = attachments.onTakePhoto,
                    onPhotos = attachments.onPickPhoto,
                    onRecordAudio = { recordSheetOpen = true },
                    onPickFile = { filePicker.launch() },
                    webSearchEnabled = webSearchEnabled,
                    webSearchAvailable = webSearchAvailable,
                    onToggleWebSearch = actions.onToggleWebSearch,
                    thinking = AppMenuToggle(checked = reasoningEnabled, onCheckedChange = actions.onToggleReasoning)
                        .takeIf { thinkingAvailable },
                    // The same connector pages Settings shows as screens, in a modal over the chat.
                    onOpenConnectors = { nav.openConnectorFlow() },
                )
            }
            Spacer(Modifier.weight(1f))
            // Dictation ONLY — speech in, text into the composer. Recording an audio *attachment* is a
            // different act with a different output (a file the model receives), so it lives in the attach
            // sheet with the other attachments rather than behind a hidden swipe on this button.
            val mic by rememberMicActivity(active = isDictating)
            VoiceRecordButton(
                recording = isDictating,
                level = mic.level,
                speaking = mic.speaking,
                onClick = { if (isDictating) actions.onStopDictation() else actions.onStartDictation() },
                iconRes = Res.drawable.ic_lc_mic,
                contentDescription = if (isDictating) "Stop transcribing" else "Transcribe speech to text",
                enabled = canDictate,
                idleTint = LocalContentColor.current.copy(alpha = if (canDictate) 0.7f else 0.35f),
            )
            if (recordSheetOpen) {
                RecordingSheet(
                    isRecording = attachments.isRecordingClip,
                    onToggleRecord = attachments.onToggleAudioClip,
                    onCancelRecord = attachments.onCancelAudioClip,
                    onDismiss = { recordSheetOpen = false },
                )
            }
            Spacer(Modifier.width(4.dp))
            SendOrStopButton(
                isGenerating = isGenerating,
                canSend = canSend,
                onSend = actions.onSend,
                onStop = actions.onStop,
            )
        }
    }
}

/** The "Editing message" affordance shown atop the composer while editing a prior turn: a pencil +
 *  label, with an X that cancels edit mode (clears the prefilled draft, restores the normal composer). */
@Composable
private fun EditingMessagePill(onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(Res.drawable.ic_lc_pencil),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Editing message",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
            Icon(
                painterResource(Res.drawable.ic_lc_x),
                contentDescription = "Cancel editing",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Bottom sheet for recording a voice clip, opened from the attach sheet's Record tile. Recording
 * auto-starts on open (mic permission is gated upstream); the big button stops + attaches the clip as
 * a draft and closes. Dismissing any other way (scrim / back) discards the in-progress recording.
 */
@Composable
private fun RecordingSheet(
    isRecording: Boolean,
    onToggleRecord: () -> Unit,
    onCancelRecord: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Set when the user explicitly stops to keep the clip, so the dismiss path knows NOT to discard it.
    var committed by remember { mutableStateOf(false) }
    AppDialog(
        onDismiss = {
            if (!committed) onCancelRecord()
            onDismiss()
        },
        title = "Record audio",
    ) { sheet ->
        LaunchedEffect(Unit) { if (!isRecording) onToggleRecord() }
        val elapsedMs by produceState(0L, isRecording) {
            if (!isRecording) return@produceState
            val start = currentTimeMillis()
            while (true) {
                value = currentTimeMillis() - start
                kotlinx.coroutines.delay(100)
            }
        }
        val mic by rememberMicActivity(active = isRecording)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = formatClipTime(elapsedMs.toInt()),
                fontSize = 40.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (isRecording) "Recording…" else "Tap to record",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The meter carries "we're live", so the button itself stays at full opacity — pulsing the
            // control's alpha reads as disabled, not as recording.
            VoiceBars(
                level = mic.level,
                speaking = mic.speaking,
                modifier = Modifier.height(28.dp),
                barCount = 5,
                barWidth = 4.dp,
                barGap = 4.dp,
                color = if (isRecording) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
            )
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        if (isRecording) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                    )
                    .clickable {
                        if (isRecording) {
                            committed = true
                            sheet.close { onToggleRecord() }
                        } else {
                            onToggleRecord()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(if (isRecording) Res.drawable.ic_lc_square else Res.drawable.ic_lc_mic),
                    contentDescription = if (isRecording) "Stop and attach" else "Start recording",
                    modifier = Modifier.size(28.dp),
                    tint = if (isRecording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** Shown while a voice clip is recording (started from the mic long-press menu). Live meter + timer + the
 *  only stop control; stopping saves the clip and hands off to a draft [AudioClipChip]. */
@Composable
private fun RecordingClipChip(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Live elapsed time since the chip appeared (the recording started with it).
    val elapsedMs by produceState(0L) {
        val start = currentTimeMillis()
        while (true) {
            value = currentTimeMillis() - start
            kotlinx.coroutines.delay(200)
        }
    }
    val mic by rememberMicActivity(active = true)
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(50))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The live meter, not a blinking dot — it shows the mic is actually hearing something.
        VoiceBars(
            level = mic.level,
            speaking = mic.speaking,
            modifier = Modifier.height(14.dp),
            barCount = 3,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(8.dp))
        Text("Recording", fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatClipTime(elapsedMs.toInt()),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.width(8.dp))
        AppIconButton(
            onClick = onStop,
            iconRes = Res.drawable.ic_lc_square,
            contentDescription = "Stop recording",
            size = 28.dp,
            iconSize = 14.dp,
            tint = MaterialTheme.colorScheme.error,
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
    AppIconButton(
        onClick = { if (isGenerating) onStop() else if (canSend) onSend() },
        iconRes = if (isGenerating) Res.drawable.ic_lc_square else Res.drawable.ic_lc_arrow_right,
        contentDescription = if (isGenerating) "Stop" else "Send",
        enabled = active,
        // Theme tokens, not literals. The accent pair already IS "near-black on near-white" in dark and the
        // reverse in light, so this keeps the dark look it was hand-coding and stops being invisible in
        // light; the inactive pair is the scheme's own disabled-control pair.
        tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        containerColor = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    )
}
