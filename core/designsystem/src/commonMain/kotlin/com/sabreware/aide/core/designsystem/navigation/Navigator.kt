package com.sabreware.aide.core.designsystem.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * One navigation concept that works the same whether a composable renders as a full app screen or as a page
 * inside a sheet. Call sites use [navigator] — the nearest [Navigator] in composition — and `navigate(route)`
 * with a **host-agnostic** route; the *host* (the app NavHost via [AppNavigator], or a sheet via
 * `com.sabreware.aide.core.designsystem.SheetNavigator`) decides whether the destination renders as a screen or a sheet
 * page. A nested host re-provides [LocalNavigator], so the nearest one always wins — which is how the same
 * call site routes correctly from either surface (and how N nested navigators compose).
 *
 * Kept intentionally thin and back-stack-shaped (Navigation3 style) so the app side can later move to nav3 by
 * swapping the implementation, not the call sites. App-only operations (popUpTo, chat replacement, …) keep
 * going through [androidx.navigation.NavHostController] directly; this unifies the reusable-across-hosts case.
 */
@Stable
interface Navigator {
    /** Whether [goBack] would pop a destination (vs. being at the host's root). */
    val canGoBack: Boolean

    /** Push [route] onto the nearest host's back stack (a full screen in the app, a page in a sheet). */
    fun navigate(route: Any)

    /** Pop the nearest host's back stack. Returns true if it popped; false if it couldn't (e.g. at root). */
    fun goBack(): Boolean
}

/**
 * The nearest [Navigator]. `static` because a navigator is a stable per-subtree reference (so changing it
 * recomposes the subtree, which it never does at runtime — we get the perf benefit); the `error` default
 * mirrors androidx owner locals (`LocalLifecycleOwner` etc.) — every consumer must sit under a provided one.
 */
val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("No Navigator in scope — provide LocalNavigator (AppNavigator around the NavHost, or a sheet navigator).")
}

/** Sugar for `LocalNavigator.current`: the nearest navigator (the enclosing sheet if inside one, else the app). */
@Composable
fun navigator(): Navigator = LocalNavigator.current
