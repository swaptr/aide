package com.sabreware.aide.app.assistant.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabreware.aide.app.assistant.domain.AideAssistantManager
import com.sabreware.aide.app.assistant.domain.AideAssistantStatus
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.prefs.selectState
import com.sabreware.aide.core.domain.speech.SpeechPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The assistant's voice switches. They live here, not on Voice, because only the assistant uses them. */
data class AssistantVoiceState(
    val speakReplies: Boolean = true,
    val announceToolCalls: Boolean = false,
)

class AssistantSettingsViewModel(
    private val assistant: AideAssistantManager,
    private val prefs: PreferenceStore,
) : ViewModel() {

    private val _status = MutableStateFlow(assistant.currentStatus())

    /** Aide's standing as the device assistant; drives which Setup action (if any) the screen shows. */
    val status: StateFlow<AideAssistantStatus> = _status.asStateFlow()

    val voice: StateFlow<AssistantVoiceState> = prefs.selectState(viewModelScope) { p ->
        AssistantVoiceState(
            speakReplies = p[SpeechPrefs.SpeakAssistantReplies],
            announceToolCalls = p[SpeechPrefs.AnnounceToolCalls],
        )
    }

    /**
     * Re-read the live assistant state. The screen calls this every time it regains window focus — the
     * moment the user returns from the system Assist settings where they may have switched the default.
     * [AideAssistantManager.currentStatus] is an uncached system query, so a read here always reflects
     * the latest state (no stale data).
     */
    fun refresh() {
        _status.value = assistant.currentStatus()
    }

    fun openAssistantSettings() = assistant.openAssistantSettings()

    fun setSpeakReplies(enabled: Boolean) {
        viewModelScope.launch { prefs.set(SpeechPrefs.SpeakAssistantReplies, enabled) }
    }

    fun setAnnounceToolCalls(enabled: Boolean) {
        viewModelScope.launch { prefs.set(SpeechPrefs.AnnounceToolCalls, enabled) }
    }
}
