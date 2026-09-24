package com.sabreware.aide.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The brand palette as Kotlin `Color` constants — the SINGLE SOURCE OF TRUTH for the Compose UI (Phase 4).
 * The `:app` `res/color` XML is a mirror of these values, kept only for the View-based IME
 * (`Theme.Aide.Ime` + keyboard widgets that read `R.color.aide_*`). Keep the two in sync
 * (memory: theme-override-config-context / use-theme-colors-only).
 */
private class AidePalette(
    val accent: Color,
    val onPrimary: Color,
    val surface: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val statusDownloading: Color,
    val statusPaused: Color,
    val statusError: Color,
    /**
     * A control drawn on top of ARBITRARY MEDIA — the remove badge on an attachment thumbnail, a camera
     * overlay. Deliberately the same in both themes: what is underneath is a photo, not a surface, so
     * contrast cannot come from the scheme, and picking `onSurface` there would be legible in one theme
     * and invisible in the other. Named so the four `Color.Black`/`Color.White` literals that used to do
     * this by hand have somewhere to live.
     */
    val mediaScrim: Color,
    val onMediaScrim: Color,
)

private val AideLight = AidePalette(
    accent = Color(0xFF1C1C1C),
    onPrimary = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF6F6F6),
    surfaceContainerHigh = Color(0xFFECECEC),
    onSurface = Color(0xFF161616),
    onSurfaceVariant = Color(0xFF6E6E6E),
    outline = Color(0xFFCBCBCB),
    outlineVariant = Color(0xFFE3E3E3),
    secondaryContainer = Color(0xFFE4E4E4),
    onSecondaryContainer = Color(0xFF161616),
    statusDownloading = Color(0xFF1E8E3E),
    statusPaused = Color(0xFF9A6700),
    statusError = Color(0xFFC5500D),
    mediaScrim = Color(0xA6000000),
    onMediaScrim = Color(0xFFFFFFFF),
)

private val AideDark = AidePalette(
    accent = Color(0xFFECECEC),
    onPrimary = Color(0xFF141414),
    surface = Color(0xFF0E0E0E),
    surfaceContainer = Color(0xFF1A1A1A),
    surfaceContainerHigh = Color(0xFF252525),
    onSurface = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF9A9A9A),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF262626),
    secondaryContainer = Color(0xFF323232),
    onSecondaryContainer = Color(0xFFF2F2F2),
    statusDownloading = Color(0xFF5FD068),
    statusPaused = Color(0xFFF2C744),
    statusError = Color(0xFFFF9457),
    mediaScrim = Color(0xA6000000),
    onMediaScrim = Color(0xFFFFFFFF),
)

/** Build the in-app M3 [ColorScheme] from the brand palette. Named-arg `copy` on the M3 factory scheme so
 *  the M3-1.4 `*Fixed` slots keep library defaults. Mirrors the old `res/color`-sourced scheme exactly. */
internal fun aideColorScheme(dark: Boolean): ColorScheme {
    val p = if (dark) AideDark else AideLight
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.accent,
        onPrimary = p.onPrimary,
        primaryContainer = p.accent,
        onPrimaryContainer = p.onPrimary,
        inversePrimary = p.accent,
        secondary = p.accent,
        onSecondary = p.onPrimary,
        secondaryContainer = p.secondaryContainer,
        onSecondaryContainer = p.onSecondaryContainer,
        tertiary = p.accent,
        onTertiary = p.onPrimary,
        tertiaryContainer = p.secondaryContainer,
        onTertiaryContainer = p.onSecondaryContainer,
        background = p.surface,
        onBackground = p.onSurface,
        surface = p.surface,
        onSurface = p.onSurface,
        surfaceVariant = p.surfaceContainerHigh,
        onSurfaceVariant = p.onSurfaceVariant,
        surfaceTint = p.accent,
        inverseSurface = p.onSurface,
        inverseOnSurface = p.surface,
        outline = p.outline,
        outlineVariant = p.outlineVariant,
        surfaceBright = p.surfaceContainerHigh,
        surfaceDim = p.surface,
        surfaceContainer = p.surfaceContainer,
        surfaceContainerHigh = p.surfaceContainerHigh,
        surfaceContainerHighest = p.surfaceContainerHigh,
        surfaceContainerLow = p.surfaceContainer,
        surfaceContainerLowest = p.surface,
    )
}

internal fun aideStatusColors(dark: Boolean): AideStatusColors {
    val p = if (dark) AideDark else AideLight
    return AideStatusColors(
        downloading = p.statusDownloading,
        paused = p.statusPaused,
        error = p.statusError,
    )
}

internal fun aideMediaColors(dark: Boolean): AideMediaColors {
    val p = if (dark) AideDark else AideLight
    return AideMediaColors(scrim = p.mediaScrim, onScrim = p.onMediaScrim)
}
