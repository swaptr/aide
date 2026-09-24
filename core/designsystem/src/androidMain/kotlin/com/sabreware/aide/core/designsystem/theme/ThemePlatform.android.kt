package com.sabreware.aide.core.designsystem.theme

import androidx.activity.compose.LocalActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
actual fun PlatformSystemBarTint(darkTheme: Boolean) {
    // Only meaningful inside an Activity window: in the assistant overlay (VoiceInteractionSession)
    // LocalContext is a WindowContext with no Activity — and that overlay floats over the host app,
    // which owns the bars — so skip rather than crash on the cast (memory: overlay-safety).
    val view = LocalView.current
    val activity = LocalActivity.current
    if (!view.isInEditMode && activity != null) {
        SideEffect {
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

@Composable
actual fun dynamicColorScheme(darkTheme: Boolean): ColorScheme? {
    // minSdk 35 → dynamic color is always available; no version branch needed.
    val context = LocalContext.current
    return if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}
