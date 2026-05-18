package com.swaptr.aide.ui.settings.speech

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swaptr.aide.R
import com.swaptr.aide.domain.speech.SpeechProviderId
import com.swaptr.aide.ui.common.AppMenuEntry
import com.swaptr.aide.ui.common.AppMenuList
import com.swaptr.aide.ui.common.AppMenuToggle
import com.swaptr.aide.ui.common.AppPage

@Composable
fun SpeechSettingsScreen(
    onClose: () -> Unit,
    viewModel: SpeechSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AppPage(
        title = "Voice",
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.ic_lc_arrow_left),
                    contentDescription = "Back",
                )
            }
        },
    ) {
        val providerLabel = when (state.providerPreference) {
            null -> "Auto (Sherpa when available, else System)"
            SpeechProviderId.SHERPA_ONNX -> "Sherpa-ONNX (on-device)"
            SpeechProviderId.ANDROID_SYSTEM -> "Android System"
        }
        val providerEntries = listOf(
            AppMenuEntry(
                title = "Provider",
                subtitle = providerLabel,
                onClick = { viewModel.setProviderPreference(cycleProvider(state.providerPreference)) },
            ),
        )
        AppMenuList(items = providerEntries)

        Text(
            text = "Surfaces",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        val toggleEntries = listOf(
            AppMenuEntry(
                title = "Assistant voice loop",
                subtitle = "Long-press home → tap mic → converse.",
                toggle = AppMenuToggle(
                    checked = state.voiceLoopEnabled,
                    onCheckedChange = viewModel::setVoiceLoopEnabled,
                ),
            ),
            AppMenuEntry(
                title = "Keyboard mic",
                subtitle = "Mic button in Aide IME (ChatPage + TransformPage).",
                toggle = AppMenuToggle(
                    checked = state.imeDictationEnabled,
                    onCheckedChange = viewModel::setImeDictationEnabled,
                ),
            ),
            AppMenuEntry(
                title = "Main chat mic",
                subtitle = "Mic button next to send in the main chat composer.",
                toggle = AppMenuToggle(
                    checked = state.mainChatDictationEnabled,
                    onCheckedChange = viewModel::setMainChatDictationEnabled,
                ),
            ),
            AppMenuEntry(
                title = "Speak assistant replies",
                subtitle = "Stream LLM responses through TTS in the assistant overlay.",
                toggle = AppMenuToggle(
                    checked = state.speakAssistantReplies,
                    onCheckedChange = viewModel::setSpeakAssistantReplies,
                ),
            ),
            AppMenuEntry(
                title = "Announce tool calls",
                subtitle = "Brief TTS interjection when the model triggers a tool.",
                toggle = AppMenuToggle(
                    checked = state.announceToolCalls,
                    onCheckedChange = viewModel::setAnnounceToolCalls,
                ),
            ),
        )
        AppMenuList(items = toggleEntries)
    }
}

private fun cycleProvider(current: SpeechProviderId?): SpeechProviderId? = when (current) {
    null -> SpeechProviderId.SHERPA_ONNX
    SpeechProviderId.SHERPA_ONNX -> SpeechProviderId.ANDROID_SYSTEM
    SpeechProviderId.ANDROID_SYSTEM -> null
}
