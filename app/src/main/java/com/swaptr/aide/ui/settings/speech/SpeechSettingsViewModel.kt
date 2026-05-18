package com.swaptr.aide.ui.settings.speech

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import com.swaptr.aide.domain.speech.SpeechProviderId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpeechSettingsViewModel @Inject constructor(
    private val prefs: UserPreferencesRepository,
) : ViewModel() {

    data class UiState(
        val providerPreference: SpeechProviderId? = null,
        val voiceLoopEnabled: Boolean = true,
        val imeDictationEnabled: Boolean = true,
        val mainChatDictationEnabled: Boolean = true,
        val speakAssistantReplies: Boolean = true,
        val announceToolCalls: Boolean = false,
    )

    private val coreState = combine(
        prefs.speechProviderPreferenceFlow,
        prefs.voiceLoopEnabledFlow,
        prefs.imeDictationEnabledFlow,
        prefs.mainChatDictationEnabledFlow,
    ) { provider, voiceLoop, ime, mainChat ->
        UiState(
            providerPreference = provider,
            voiceLoopEnabled = voiceLoop,
            imeDictationEnabled = ime,
            mainChatDictationEnabled = mainChat,
        )
    }

    val state: StateFlow<UiState> = combine(
        coreState,
        prefs.speakAssistantRepliesFlow,
        prefs.announceToolCallsFlow,
    ) { core, speak, announce ->
        core.copy(speakAssistantReplies = speak, announceToolCalls = announce)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun setProviderPreference(id: SpeechProviderId?) {
        viewModelScope.launch { prefs.setSpeechProviderPreference(id) }
    }

    fun setVoiceLoopEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setVoiceLoopEnabled(enabled) }
    }

    fun setImeDictationEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setImeDictationEnabled(enabled) }
    }

    fun setMainChatDictationEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setMainChatDictationEnabled(enabled) }
    }

    fun setSpeakAssistantReplies(enabled: Boolean) {
        viewModelScope.launch { prefs.setSpeakAssistantReplies(enabled) }
    }

    fun setAnnounceToolCalls(enabled: Boolean) {
        viewModelScope.launch { prefs.setAnnounceToolCalls(enabled) }
    }
}
