package com.sabreware.aide.platform.android.surface.ime.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabreware.aide.core.common.prefs.PrefSnapshot
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.platform.android.surface.ime.prefs.keyboardAppearance
import com.sabreware.aide.platform.android.surface.ime.prefs.KeyStyle
import com.sabreware.aide.platform.android.surface.ime.prefs.KeyboardHeight
import com.sabreware.aide.platform.android.surface.ime.prefs.KeyboardPrefs
import com.sabreware.aide.core.domain.speech.SpeechPrefs
import com.sabreware.aide.platform.android.surface.ime.domain.AideKeyboardManager
import com.sabreware.aide.platform.android.surface.ime.domain.AideKeyboardStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the keyboard-settings screen renders, in one snapshot. */
data class KeyboardSettingsUiState(
    val status: AideKeyboardStatus,
    val dictationEnabled: Boolean = true,
    val keyStyle: KeyStyle = KeyStyle.Borderless,
    val keyboardHeight: KeyboardHeight = KeyboardHeight.Default,
    val numberRowEnabled: Boolean = false,
    val aiEnabled: Boolean = true,
)

class KeyboardSettingsViewModel(
    private val keyboard: AideKeyboardManager,
    private val prefs: PreferenceStore,
) : ViewModel() {

    // Imperative source: the IME's live standing with the system, re-read on window focus (see [refresh]).
    private val _status = MutableStateFlow(keyboard.currentStatus())

    // One builder for the first frame (the prefs snapshot) and every update; an unreadable store degrades
    // to the defaults rather than cancelling the sharing coroutine.
    val uiState: StateFlow<KeyboardSettingsUiState> =
        combine(_status, prefs.snapshots, ::state)
            .distinctUntilChanged()
            .catch { emit(state(_status.value, PrefSnapshot.Defaults)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state(_status.value, prefs.current))

    private fun state(status: AideKeyboardStatus, p: PrefSnapshot): KeyboardSettingsUiState {
        val look = p.keyboardAppearance()
        return KeyboardSettingsUiState(
            status,
            p[SpeechPrefs.ImeDictationEnabled],
            look.keyStyle,
            look.height,
            look.numberRow,
            look.aiEnabled,
        )
    }

    /**
     * Re-read the live IME state. The screen calls this every time it regains window focus (and on
     * resume) — the moment the user comes back from the system input-method picker or another app where
     * they may have changed the keyboard. [AideKeyboardManager.currentStatus] is an uncached system
     * query, so a read here always reflects the latest state (no stale data).
     */
    fun refresh() {
        _status.value = keyboard.currentStatus()
    }

    fun enableKeyboard() = keyboard.openEnableSettings()

    fun switchKeyboard() = keyboard.openSwitcher()

    fun setDictationEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.set(SpeechPrefs.ImeDictationEnabled, enabled) }
    }

    fun setKeyStyle(style: KeyStyle) {
        viewModelScope.launch { prefs.set(KeyboardPrefs.KeyStyle, style) }
    }

    fun setKeyboardHeight(height: KeyboardHeight) {
        viewModelScope.launch { prefs.set(KeyboardPrefs.Height, height) }
    }

    fun setNumberRowEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.set(KeyboardPrefs.NumberRowEnabled, enabled) }
    }

    fun setAiEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.set(KeyboardPrefs.AiEnabled, enabled) }
    }
}
