package com.sabreware.aide.ui.settings.mcp

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.sabreware.aide.core.designsystem.navigation.Navigator
import com.sabreware.aide.core.designsystem.navigation.openModal

/**
 * The connector flow's pages, registered ONCE. Settings pushes them as screens; the chat composer opens them
 * in a modal ([openConnectorFlow]) — the same entries either way, only the container differs.
 */
fun EntryProviderScope<NavKey>.connectorEntries() {
    entry<ConnectorRoute.Home> { ConnectorHomePage() }
    entry<ConnectorRoute.Catalog> { ConnectorCatalogPage() }
    entry<ConnectorRoute.Detail> { ConnectorDetailPage(it.connectorId) }
    entry<ConnectorRoute.AddCustom> { AddConnectorPage(it.initialUrl) }
}

/** Open the connector flow in a modal over the current page. */
fun Navigator.openConnectorFlow() = openModal(ConnectorFlowId, ConnectorRoute.Home)

private const val ConnectorFlowId = "connectors"
