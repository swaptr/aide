package com.sabreware.aide.core.designsystem.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The one navigation port every page talks to, whether it renders as a full screen, a sheet page or a dialog
 * page. There is ONE back stack (Navigation 3); the nearest [Navigator] in composition decides what a push
 * means — the app's navigator pushes a screen, a modal flow's navigator pushes a page into its container
 * ([ModalSceneStrategy]). Pages call [navigator] and never know which host they are in.
 */
@Stable
interface Navigator {
    /** Whether [goBack] would pop a destination (vs. being at the host's root). */
    val canGoBack: Boolean

    /** Push [route] onto the nearest host's back stack (a full screen in the app, a page in a sheet). */
    fun navigate(route: Any)

    /** Pop the nearest host's back stack. Returns true if it popped; false if it couldn't (e.g. at root). */
    fun goBack(): Boolean

    /** Replace the current page with [route] in place — back then skips it (a clone's editor replacing it). */
    fun replace(route: Any)
}

/**
 * The nearest [Navigator]. `static` because a navigator is a stable per-subtree reference (so changing it
 * recomposes the subtree, which it never does at runtime — we get the perf benefit); the `error` default
 * mirrors androidx owner locals (`LocalLifecycleOwner` etc.) — every consumer must sit under a provided one.
 */
val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("No Navigator in scope — provide LocalNavigator (the app navigator around NavDisplay, or a modal flow's).")
}

/** Sugar for `LocalNavigator.current`: the nearest navigator (the enclosing sheet if inside one, else the app). */
@Composable
fun navigator(): Navigator = LocalNavigator.current
