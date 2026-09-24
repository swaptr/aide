package com.sabreware.aide.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
actual fun PlatformSystemBarTint(darkTheme: Boolean) {
    // Desktop has no system bars.
}

@Composable
actual fun dynamicColorScheme(darkTheme: Boolean): ColorScheme? = null
