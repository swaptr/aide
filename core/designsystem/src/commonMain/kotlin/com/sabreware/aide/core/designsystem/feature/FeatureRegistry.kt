package com.sabreware.aide.core.designsystem.feature

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The features THIS application installs. Each entry point (`:app`, `:desktopApp`) composes its own list and
 * puts it in the graph via [featureModules]; the shell then iterates it uniformly for the settings menu, the
 * nav graph and deep-link routing — no `when` over platform or feature identity anywhere.
 *
 * This replaced an `expect val platformFeatures` / `actual` pair. `expect`/`actual` expresses *variation* —
 * every target must supply an actual — so a platform that simply has no keyboard or assistant surface was
 * forced to write `emptyList()`, i.e. to model **absence** as a declaration. Composing the set at startup
 * says the same thing by omission, and it is what lets a platform-only feature live in a module that other
 * platforms never put on their classpath at all. (Official KMP guidance: with a DI framework in the project,
 * prefer DI over expect/actual for platform variation.)
 */
class FeatureRegistry(val features: List<Feature>) {
    /** The subset that renders a row in the Settings menu. */
    val settingsFeatures: List<SettingsFeature> get() = features.filterIsInstance<SettingsFeature>()
}

/**
 * DI for a set of features: every feature's own module (ViewModels, repositories, its own database …) plus
 * the [FeatureRegistry] the shell resolves. An app's Koin start-up is then
 * `commonModules + platformModule + featureModules(myFeatures)` — see CLAUDE.md, "The gating rule".
 */
fun featureModules(features: List<Feature>): List<Module> =
    features.map { it.koinModule } + module { single { FeatureRegistry(features) } }
