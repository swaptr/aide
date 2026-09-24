package com.sabreware.aide.app.assistant.settings

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
import com.sabreware.aide.app.assistant.domain.AideAssistantStatus
import kotlinx.coroutines.flow.filter
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AssistantSettingsScreen(
    viewModel: AssistantSettingsViewModel = koinViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val voice by viewModel.voice.collectAsStateWithLifecycle()

    // Re-read the live assistant role whenever the window regains focus — the official Compose signal
    // (WindowInfo.isWindowFocused) for "the user came back". Covers returning from the system Assist &
    // voice-input settings, where the user may have changed the default assistant. snapshotFlow keeps
    // the focus reads off the composition; the uncached currentStatus() read means no stale state.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo, viewModel) {
        snapshotFlow { windowInfo.isWindowFocused }
            .filter { it }
            .collect { viewModel.refresh() }
    }

    AppPage(
        title = "Digital Assistant",
    ) {
        AppMenu(
            title = "Setup",
            items = listOf(setupRow(status, viewModel)),
        )
        AppMenu(
            title = "Voice",
            items = listOf(
                AppMenuEntry(
                    title = "Read replies aloud",
                    subtitle = "Speak each answer as it arrives.",
                    toggle = AppMenuToggle(
                        checked = voice.speakReplies,
                        onCheckedChange = viewModel::setSpeakReplies,
                    ),
                ),
                AppMenuEntry(
                    title = "Say what it's doing",
                    subtitle = "Speak a short note when the assistant uses a tool.",
                    toggle = AppMenuToggle(
                        checked = voice.announceToolCalls,
                        onCheckedChange = viewModel::setAnnounceToolCalls,
                    ),
                ),
            ),
        )
    }
}

/**
 * The single Setup row for the current [status] — one action when Aide isn't the assistant yet, or a
 * "you're all set" confirmation when it already is. Each state surfaces exactly one next step.
 */
private fun setupRow(
    status: AideAssistantStatus,
    viewModel: AssistantSettingsViewModel,
): AppMenuEntry = when (status) {
    AideAssistantStatus.NotDefault -> AppMenuEntry(
        title = "Set Aide as your assistant",
        subtitle = "Make Aide the device's default digital assistant.",
        leadingIconRes = Res.drawable.ic_lc_sparkles,
        onClick = viewModel::openAssistantSettings,
    )

    AideAssistantStatus.Active -> AppMenuEntry(
        title = "Aide is your digital assistant",
        subtitle = "You're all set.",
        leadingIconRes = Res.drawable.ic_lc_circle_check,
    )
}
