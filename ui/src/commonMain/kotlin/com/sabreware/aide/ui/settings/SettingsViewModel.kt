package com.sabreware.aide.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.prefs.selectState
import com.sabreware.aide.core.domain.model.ModelFallback
import com.sabreware.aide.core.domain.model.ModelFallbackPrefs
import com.sabreware.aide.core.domain.prefs.ChatFontStyle
import com.sabreware.aide.core.domain.prefs.ThemeMode
import com.sabreware.aide.ui.settings.fonts.FontKeys
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.System,
    val fontScale: Float = 1f,
    val chatFontStyle: ChatFontStyle = ChatFontStyle.Serif,
    val modelFallback: ModelFallback = ModelFallback.Never,
)

class SettingsViewModel(private val prefs: PreferenceStore) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = prefs.selectState(viewModelScope) { p ->
        SettingsUiState(
            themeMode = p[AppearanceKeys.Theme],
            fontScale = p[FontKeys.Scale],
            chatFontStyle = p[FontKeys.ChatStyle],
            modelFallback = p[ModelFallbackPrefs.Policy],
        )
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.set(AppearanceKeys.Theme, mode) }
    }

    fun setModelFallback(policy: ModelFallback) {
        viewModelScope.launch { prefs.set(ModelFallbackPrefs.Policy, policy) }
    }

    fun setFontScale(scale: Float) {
        viewModelScope.launch { prefs.set(FontKeys.Scale, scale) }
    }

    fun setChatFontStyle(style: ChatFontStyle) {
        viewModelScope.launch { prefs.set(FontKeys.ChatStyle, style) }
    }
}
