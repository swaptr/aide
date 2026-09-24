package com.sabreware.aide.platform.android.surface.ime.prefs

import com.sabreware.aide.core.common.prefs.boolKey
import com.sabreware.aide.core.common.prefs.enumKey

/**
 * Keyboard look/behaviour knobs, each observed live by the IME (see [KeyboardAppearance]), plus the page the
 * IME reopens on. Declared next to the enums they store; key names and defaults are what
 * `UserPreferencesRepository` used.
 */
object KeyboardPrefs {
    val Page = enumKey("ime_page", default = ImePage.KEYBOARD)
    val KeyStyle = enumKey("kb_key_style", default = com.sabreware.aide.platform.android.surface.ime.prefs.KeyStyle.Borderless)
    val Height = enumKey("kb_height", default = KeyboardHeight.Default)
    val NumberRowEnabled = boolKey("kb_number_row", default = false)
    val AiEnabled = boolKey("kb_ai_enabled", default = true)
}
