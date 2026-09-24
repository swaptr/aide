package com.sabreware.aide.ui.settings.speech

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.AppMenuToggle
import com.sabreware.aide.core.designsystem.AppPage
import org.koin.compose.viewmodel.koinViewModel

/**
 * Voice settings every host shares: which engine speaks and listens, and the chat mic. Switches that only
 * mean something on one surface live on that surface's page (the keyboard mic on Keyboard, the hands-free
 * and spoken-reply switches on Digital Assistant), so a host without that surface never draws them.
 */
@Composable
fun SpeechSettingsScreen(
    viewModel: SpeechSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    var showProviderSheet by rememberSaveable { mutableStateOf(false) }
    AppPage(
        title = "Voice",
    ) {
        AppMenu(
            items = listOf(
                AppMenuEntry(
                    title = "Voice engine",
                    subtitle = voiceProviderTitle(state.providerPreference?.let(viewModel::infoOf)),
                    onClick = { showProviderSheet = true },
                ),
                AppMenuEntry(
                    title = "Chat mic",
                    subtitle = "Show a mic button next to send in chat.",
                    toggle = AppMenuToggle(
                        checked = state.mainChatDictationEnabled,
                        onCheckedChange = viewModel::setMainChatDictationEnabled,
                    ),
                ),
            ),
        )
        if (showProviderSheet) {
            VoiceProviderSheet(
                selected = state.providerPreference,
                providers = providers,
                onSelect = viewModel::setProviderPreference,
                onDismiss = { showProviderSheet = false },
            )
        }
    }
}
