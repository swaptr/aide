package com.sabreware.aide.core.designsystem.feature

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import org.koin.core.module.Module
import org.koin.dsl.module

/** Shared empty module for features that register no DI of their own. */
internal val EmptyFeatureModule: Module = module { }

/**
 * A self-contained, navigable app feature. **A feature exists on a platform iff that platform's source set
 * contributes it** to the registry (see `platformFeatures` / `commonFeatures`). Everything a feature needs
 * to plug into the app derives from this one object, and the app iterates the feature list uniformly — no
 * central `when`/`if` over platform or feature identity:
 *
 *  - [register] — hosts its NavHost destination(s); may be a **nested graph** (`navigation<Root>{ … }`) so a
 *    feature with sub-screens keeps them encapsulated (outsiders navigate to the graph, internals stay private),
 *  - [koinModule] — its DI (ViewModels, repositories, even its own database); most features add none,
 *  - [handleDeepLink] — lets a feature claim a deep-link destination string (returns true if it consumed it).
 *
 * Gating is by **source set**: a shared feature lives in `commonMain`; a feature only some platforms ship
 * lives in that platform's source set (a leaf like `androidMain`, or an intermediate set shared by a subset),
 * and is listed only in those platforms' registry contribution. A feature a platform omits is neither shown
 * nor compiled there — the compiler enforces it. [SettingsFeature] specializes this for settings-menu entries.
 */
interface Feature {
    /** Host this feature's destination(s) in the NavHost. Use `navigation<Root>{ … }` for a nested graph. */
    fun register(builder: NavGraphBuilder, nav: NavHostController)

    /** DI this feature owns (ViewModels, repos, its own DB DAOs, …). Default: none. */
    val koinModule: Module get() = EmptyFeatureModule

    /** Claim a deep-link destination string; return true if this feature navigated to it. Default: not mine. */
    fun handleDeepLink(dest: String, nav: NavHostController): Boolean = false
}
