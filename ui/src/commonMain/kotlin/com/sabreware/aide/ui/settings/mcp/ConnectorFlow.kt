package com.sabreware.aide.ui.settings.mcp

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppDialogSize
import com.sabreware.aide.core.designsystem.rememberNavDialogBackStack
import com.sabreware.aide.ui.navigation.page

/**
 * The connector flow's host wiring. The same [ConnectorPages] are registered ONCE per host — as full-screen
 * NavHost destinations ([connectorDestinations], called from the app graph) and as headerless sheet pages
 * ([ConnectorDialog], opened from chat). The page composables are the single source; only the host differs.
 */
fun NavGraphBuilder.connectorDestinations() {
    page<ConnectorRoute.Home> { ConnectorHomePage() }
    page<ConnectorRoute.Catalog> { ConnectorCatalogPage() }
    page<ConnectorRoute.Detail> { ConnectorDetailPage(it.connectorId) }
    page<ConnectorRoute.AddCustom> { AddConnectorPage(it.initialUrl) }
}

/**
 * The connector flow as a sheet (chat): the same pages, hosted headerless (each draws its own header via
 * `PageScaffold(Sheet)`). Expandable (60% peek ↔ 100%). Opened from the chat composer; dismiss closes it.
 */
@Composable
fun ConnectorDialog(onDismiss: () -> Unit) {
    val backStack = rememberNavDialogBackStack<ConnectorRoute>(ConnectorRoute.Home)
    AppDialog(backStack = backStack, onDismiss = onDismiss, size = AppDialogSize.Expandable) {
        page<ConnectorRoute.Home> { _, _ -> ConnectorHomePage() }
        page<ConnectorRoute.Catalog> { _, _ -> ConnectorCatalogPage() }
        page<ConnectorRoute.Detail> { route, _ -> ConnectorDetailPage(route.connectorId) }
        page<ConnectorRoute.AddCustom> { route, _ -> AddConnectorPage(route.initialUrl) }
    }
}
