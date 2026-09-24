package com.sabreware.aide.core.designsystem.feature

import androidx.compose.runtime.Immutable
import org.jetbrains.compose.resources.DrawableResource

/**
 * The Settings menu's top-level groups, in display order. A [SettingsFeature] names the section it
 * belongs to; the menu renders one [com.sabreware.aide.core.designsystem.AppMenu] per section that has rows.
 */
enum class SettingsSection { Appearance, System, Assistant, Connectors, About;

    /** The section header shown above its rows (equals the constant name today). */
    val title: String get() = name
}

/** The presentation of a feature's Settings row — pure data, so it stays a stable `@Immutable` value. */
@Immutable
data class SettingsFeatureRow(
    val title: String,
    val subtitle: String? = null,
    val leadingIconRes: DrawableResource? = null,
)

/**
 * A [Feature] that also surfaces a row in the Settings menu. Adds where the row sits ([section]/[order]),
 * the row's [row] text/icon, and the [route] its row navigates to. Everything else (nav destination, DI,
 * deep-links) comes from [Feature]. The menu renders the [SettingsFeature]s in the registry, grouped by
 * [section]; platform-only ones appear only where their platform contributes them.
 */
interface SettingsFeature : Feature {
    val section: SettingsSection
    val order: Int
    val row: SettingsFeatureRow
    val route: Any
}
