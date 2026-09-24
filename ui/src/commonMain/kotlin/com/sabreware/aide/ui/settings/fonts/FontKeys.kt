package com.sabreware.aide.ui.settings.fonts

import com.sabreware.aide.core.common.prefs.enumKey
import com.sabreware.aide.core.common.prefs.floatKey
import com.sabreware.aide.core.domain.prefs.ChatFontStyle

/** Fonts settings. Key names match the legacy `UserPreferencesRepository` keys — data carries over; the
 *  0.8–1.4 clamp moves INTO the key (applies on read and write, one definition). */
object FontKeys {
    val Scale = floatKey("font_scale", default = 1f, range = 0.8f..1.4f)
    val ChatStyle = enumKey("chat_font_style", default = ChatFontStyle.Serif)
}
