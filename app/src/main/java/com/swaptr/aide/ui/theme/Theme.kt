package com.swaptr.aide.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import com.swaptr.aide.R

@Composable
private fun aideColorScheme(darkTheme: Boolean): ColorScheme {
    val primary = colorResource(R.color.aide_accent)
    val onPrimary = colorResource(R.color.aide_on_primary)
    val surface = colorResource(R.color.aide_surface)
    val onSurface = colorResource(R.color.aide_on_surface)
    val onSurfaceVariant = colorResource(R.color.aide_on_surface_variant)
    val surfaceContainer = colorResource(R.color.aide_surface_container)
    val surfaceContainerHigh = colorResource(R.color.aide_surface_container_high)
    val outline = colorResource(R.color.aide_outline)
    val outlineVariant = colorResource(R.color.aide_outline_variant)
    val secondaryContainer = colorResource(R.color.aide_secondary_container)
    val onSecondaryContainer = colorResource(R.color.aide_on_secondary_container)

    // Named-arg factory (not ColorScheme(...)) so M3-1.4 *Fixed slots keep library defaults.
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primary,
        onPrimaryContainer = onPrimary,
        inversePrimary = primary,
        secondary = primary,
        onSecondary = onPrimary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = primary,
        onTertiary = onPrimary,
        tertiaryContainer = secondaryContainer,
        onTertiaryContainer = onSecondaryContainer,
        background = surface,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceContainerHigh,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = primary,
        inverseSurface = onSurface,
        inverseOnSurface = surface,
        outline = outline,
        outlineVariant = outlineVariant,
        // scrim stays M3 default black; onSurface in dark mode is near-white and would invert.
        surfaceBright = surfaceContainerHigh,
        surfaceDim = surface,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHigh,
        surfaceContainerLow = surfaceContainer,
        surfaceContainerLowest = surface,
    )
}

@Composable
fun AideTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (dynamicColor) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        aideColorScheme(darkTheme)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AideTypography,
        content = content,
    )
}
