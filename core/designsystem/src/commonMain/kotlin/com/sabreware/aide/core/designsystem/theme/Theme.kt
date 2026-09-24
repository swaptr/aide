package com.sabreware.aide.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic download-state colors. M3's ColorScheme has no status slots, so these ride their own
 * CompositionLocal — sourced from the same brand palette (light/dark split) as [aideColorScheme].
 */
data class AideStatusColors(val downloading: Color, val paused: Color, val error: Color)

val LocalAideStatusColors = staticCompositionLocalOf<AideStatusColors> {
    error("LocalAideStatusColors not provided — wrap content in AideTheme")
}

/** Colors for a control drawn over arbitrary media. See [com.sabreware.aide.core.designsystem.theme] docs. */
data class AideMediaColors(val scrim: Color, val onScrim: Color)

val LocalAideMediaColors = staticCompositionLocalOf<AideMediaColors> {
    error("LocalAideMediaColors not provided — wrap content in AideTheme")
}

/**
 * The app theme. Kotlin `Color` light/dark constants are the source of truth (see [aideColorScheme]);
 * [dynamicColor] pulls the platform dynamic scheme where available (Android 12+), else the brand palette.
 * System-bar icon tinting + dynamic scheme resolution are platform shims ([PlatformSystemBarTint] /
 * [dynamicColorScheme]) so the same UI runs on Android (activity window) and desktop.
 */
@Composable
fun AideTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    // ColorScheme has no equals, so a fresh instance would re-provide to the static LocalColorScheme and
    // recompose the whole subtree even when nothing changed. Recompute only when the inputs actually flip.
    val dynamic: ColorScheme? = if (dynamicColor) dynamicColorScheme(darkTheme) else null
    val colorScheme = remember(darkTheme, dynamic) { dynamic ?: aideColorScheme(darkTheme) }
    val statusColors = remember(darkTheme) { aideStatusColors(darkTheme) }

    // Edge-to-edge bars are transparent, so the system-bar ICON tint must follow the app theme (light
    // theme → dark icons). No-op off an activity window (assistant overlay / desktop).
    PlatformSystemBarTint(darkTheme)

    // ProvideAideFonts builds the CMP-resource font families in composition and exposes them via
    // LocalAppSans/LocalAppSerif; aideTypography() reads them, so it must run inside the provider.
    ProvideAideFonts {
        CompositionLocalProvider(
            LocalAideStatusColors provides statusColors,
            LocalAideMediaColors provides aideMediaColors(darkTheme),
        ) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = aideTypography(),
                content = content,
            )
        }
    }
}
