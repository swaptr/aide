package com.sabreware.aide.ui.navigation

import androidx.compose.runtime.Stable
import androidx.navigation.NavHostController
import com.sabreware.aide.core.designsystem.navigation.Navigator

/**
 * [Navigator] backed by the app's [NavHostController]: `navigate` pushes a full screen, `goBack` pops the app
 * back stack. Provided once around the top-level `NavHost` (see `AppShell`). Reusable destinations call
 * `navigator().navigate(route)`; app-only navigation with options (popUpTo, replacement, …) keeps using the
 * [NavHostController] directly.
 */
@Stable
class AppNavigator(private val nav: NavHostController) : Navigator {
    override val canGoBack: Boolean get() = nav.previousBackStackEntry != null
    override fun navigate(route: Any) {
        nav.navigate(route) { launchSingleTop = true }
    }
    override fun goBack(): Boolean = nav.popBackStack()
}
