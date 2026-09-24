package com.sabreware.aide.ui.settings

import com.sabreware.aide.core.common.prefs.enumKey
import com.sabreware.aide.core.domain.prefs.ThemeMode

/** Appearance settings. Key names match the legacy `UserPreferencesRepository` keys — data carries over. */
object AppearanceKeys {
    val Theme = enumKey("theme_mode", default = ThemeMode.System)
}
