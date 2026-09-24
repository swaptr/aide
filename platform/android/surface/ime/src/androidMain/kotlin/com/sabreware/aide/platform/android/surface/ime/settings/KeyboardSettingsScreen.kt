package com.sabreware.aide.platform.android.surface.ime.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.AppMenuToggle
import com.sabreware.aide.core.designsystem.AppPage
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.platform.android.surface.ime.prefs.KeyStyle
import com.sabreware.aide.platform.android.surface.ime.prefs.KeyboardHeight
import com.sabreware.aide.platform.android.surface.ime.domain.AideKeyboardStatus
import kotlinx.coroutines.flow.filter
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun KeyboardSettingsScreen(
    onOpenTasks: () -> Unit,
    viewModel: KeyboardSettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Re-read the live IME state whenever the window regains focus — the official Compose signal
    // (WindowInfo.isWindowFocused) for "the user came back". Covers returning from the system
    // input-method picker (an overlay that returns focus, not a resume) and from another app where the
    // keyboard may have been changed. snapshotFlow keeps the focus reads off the composition; the
    // uncached currentStatus() read means the screen never shows stale state.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo, viewModel) {
        snapshotFlow { windowInfo.isWindowFocused }
            .filter { it }
            .collect { viewModel.refresh() }
    }

    AppPage(
        title = "Keyboard",
    ) {
        AppMenu(
            title = "Setup",
            items = listOf(setupRow(uiState.status, viewModel)),
        )
        AppMenu(
            title = "Key style",
            items = keyStyleRows(uiState.keyStyle, viewModel::setKeyStyle),
        )
        AppMenu(
            title = "Keyboard height",
            items = keyboardHeightRows(uiState.keyboardHeight, viewModel::setKeyboardHeight),
        )
        AppMenu(
            title = "Features",
            items = listOf(
                AppMenuEntry(
                    title = "Number row",
                    subtitle = "Show a row of digits above the letters.",
                    toggle = AppMenuToggle(
                        checked = uiState.numberRowEnabled,
                        onCheckedChange = viewModel::setNumberRowEnabled,
                    ),
                ),
                AppMenuEntry(
                    title = "AI features",
                    subtitle = "Show the rewrite, tasks and mic bar above the keys.",
                    toggle = AppMenuToggle(
                        checked = uiState.aiEnabled,
                        onCheckedChange = viewModel::setAiEnabled,
                    ),
                ),
                AppMenuEntry(
                    title = "Keyboard mic",
                    subtitle = "Show a mic for voice typing.",
                    toggle = AppMenuToggle(
                        checked = uiState.dictationEnabled,
                        onCheckedChange = viewModel::setDictationEnabled,
                    ),
                ),
                AppMenuEntry(
                    title = "Tasks",
                    subtitle = "Create and organize your tasks.",
                    onClick = onOpenTasks,
                ),
            ),
        )
    }
}

/**
 * The single Setup row for the current [status] — one action when there's something to do, or a
 * "you're all set" confirmation when Aide is already active. Each state surfaces exactly one next step.
 */
private fun setupRow(
    status: AideKeyboardStatus,
    viewModel: KeyboardSettingsViewModel,
): AppMenuEntry = when (status) {
    AideKeyboardStatus.NotEnabled -> AppMenuEntry(
        title = "Enable Aide keyboard",
        subtitle = "Turn Aide on in the system keyboard list.",
        leadingIconRes = Res.drawable.ic_lc_circle_plus,
        onClick = viewModel::enableKeyboard,
    )

    AideKeyboardStatus.EnabledInactive -> AppMenuEntry(
        title = "Switch to Aide",
        subtitle = "Set Aide as the active keyboard.",
        leadingIconRes = Res.drawable.ic_lc_arrow_left_right,
        onClick = viewModel::switchKeyboard,
    )

    AideKeyboardStatus.Active -> AppMenuEntry(
        title = "Aide is your default keyboard",
        subtitle = "You're all set.",
        leadingIconRes = Res.drawable.ic_lc_circle_check,
    )
}

/** Radio-style rows for the key-style picker (one selected). */
private fun keyStyleRows(
    selected: KeyStyle,
    onSelect: (KeyStyle) -> Unit,
): List<AppMenuEntry> = KeyStyle.entries.map { style ->
    AppMenuEntry(
        title = when (style) {
            KeyStyle.Borderless -> "Borderless"
            KeyStyle.Bordered -> "Bordered"
        },
        subtitle = when (style) {
            KeyStyle.Borderless -> "Flat keys; a highlight appears on press."
            KeyStyle.Bordered -> "Each key is a filled rounded box."
        },
        selected = style == selected,
        onClick = { onSelect(style) },
    )
}

/** Radio-style rows for the keyboard-height picker (one selected). */
private fun keyboardHeightRows(
    selected: KeyboardHeight,
    onSelect: (KeyboardHeight) -> Unit,
): List<AppMenuEntry> = KeyboardHeight.entries.map { height ->
    AppMenuEntry(
        title = when (height) {
            KeyboardHeight.Short -> "Short"
            KeyboardHeight.Default -> "Default"
            KeyboardHeight.Tall -> "Tall"
        },
        subtitle = when (height) {
            KeyboardHeight.Short -> "Compact. More room for content."
            KeyboardHeight.Default -> null
            KeyboardHeight.Tall -> "Bigger keys, easier to hit."
        },
        selected = height == selected,
        onClick = { onSelect(height) },
    )
}
