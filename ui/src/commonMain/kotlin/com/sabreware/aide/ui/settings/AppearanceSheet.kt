package com.sabreware.aide.ui.settings

import androidx.compose.runtime.Composable
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.domain.prefs.ThemeMode

/** Human label for a [ThemeMode] — "Auto" for [ThemeMode.System]. Shared by the Settings row and the sheet. */
internal fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.System -> "Auto"
    ThemeMode.Light -> "Light"
    ThemeMode.Dark -> "Dark"
}

/**
 * Appearance (theme) picker — our reusable [AppDialog] with a single-select Auto/Light/Dark list.
 * Tapping a row applies the theme and closes the sheet, like every single-choice picker.
 */
@Composable
fun AppearanceSheet(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismiss = onDismiss,
        title = "Color theme",
    ) { controller ->
        AppMenu(
            items = listOf(
                AppMenuEntry(
                    title = "Auto",
                    subtitle = "Match the system setting.",
                    selected = selected == ThemeMode.System,
                    onClick = { onSelect(ThemeMode.System); controller.close() },
                ),
                AppMenuEntry(
                    title = "Light",
                    subtitle = "Always use the light theme.",
                    selected = selected == ThemeMode.Light,
                    onClick = { onSelect(ThemeMode.Light); controller.close() },
                ),
                AppMenuEntry(
                    title = "Dark",
                    subtitle = "Always use the dark theme.",
                    selected = selected == ThemeMode.Dark,
                    onClick = { onSelect(ThemeMode.Dark); controller.close() },
                ),
            ),
        )
    }
}
