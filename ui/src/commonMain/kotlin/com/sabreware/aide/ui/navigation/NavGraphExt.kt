package com.sabreware.aide.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.sabreware.aide.core.designsystem.LocalPageCanGoBack
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute

/**
 * Register a host-agnostic page as a type-safe NavHost destination — the screen-side mirror of the dialog
 * DSL's `page<T>` (see `AppDialogNav`). It decodes the route from the back-stack entry so the page
 * composable receives the route directly (object routes just ignore it). Use it inside a feature's
 * `*Destinations()` extension; the same `*Page` composables register as dialog pages in that feature's
 * `*Sheet`, so a flow's pages are declared once and only the host (NavHost vs sheet) differs.
 */
inline fun <reified T : Any> NavGraphBuilder.page(
    crossinline content: @Composable (T) -> Unit,
) = composable<T> { entry ->
    // The entry's OWN depth, as the sheet host gives its pages: fixed for the entry, so predictive back
    // (which composes the page underneath before the pop) never flashes the wrong navigation icon.
    val root = generateSequence(entry.destination.parent) { it.parent }.lastOrNull()
    val isStart = root?.startDestinationId == entry.destination.id
    CompositionLocalProvider(LocalPageCanGoBack provides !isStart) { content(entry.toRoute<T>()) }
}
