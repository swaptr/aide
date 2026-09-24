package com.sabreware.aide.ui.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.sabreware.aide.core.common.prefs.PrefSnapshot
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.prefs.select
import com.sabreware.aide.core.designsystem.theme.AideTheme
import com.sabreware.aide.core.designsystem.theme.LocalChatFontStyle
import com.sabreware.aide.core.designsystem.theme.LocalFontScale
import com.sabreware.aide.core.domain.prefs.ChatFontStyle
import com.sabreware.aide.core.domain.prefs.ThemeMode
import com.sabreware.aide.ui.settings.AppearanceKeys
import com.sabreware.aide.ui.settings.fonts.FontKeys

/**
 * The user's appearance — theme, chat text size and typeface — applied to [content]. Every root that draws
 * our UI wraps itself in this: the app window, the desktop window, the assistant overlay.
 *
 * The first composition paints from [PreferenceStore.current], so the first frame is already in the user's
 * theme (each host holds that frame until the store is loaded); later commits recompose from one snapshot.
 */
@Composable
fun AppAppearance(prefs: PreferenceStore, content: @Composable () -> Unit) {
    val appearance by remember(prefs) { prefs.select(::Appearance) }
        .collectAsState(initial = remember(prefs) { Appearance(prefs.current) })
    val dark = when (appearance.theme) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    AideTheme(darkTheme = dark) {
        CompositionLocalProvider(
            LocalFontScale provides appearance.fontScale,
            LocalChatFontStyle provides appearance.chatFont,
            content = content,
        )
    }
}

private data class Appearance(val theme: ThemeMode, val fontScale: Float, val chatFont: ChatFontStyle) {
    constructor(p: PrefSnapshot) : this(p[AppearanceKeys.Theme], p[FontKeys.Scale], p[FontKeys.ChatStyle])
}
