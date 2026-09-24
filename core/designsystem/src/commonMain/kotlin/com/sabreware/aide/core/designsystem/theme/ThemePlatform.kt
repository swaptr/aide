package com.sabreware.aide.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** Tints the system bar icons to match the app theme. Android: WindowInsetsController on the activity
 *  window (no-op off an activity, e.g. the assistant overlay). Desktop: no-op. */
@Composable
expect fun PlatformSystemBarTint(darkTheme: Boolean)

/** The platform dynamic color scheme (Android 12+ Material You), or null when not applicable (desktop,
 *  or Android without dynamic color). Callers fall back to the brand [aideColorScheme]. */
@Composable
expect fun dynamicColorScheme(darkTheme: Boolean): ColorScheme?
