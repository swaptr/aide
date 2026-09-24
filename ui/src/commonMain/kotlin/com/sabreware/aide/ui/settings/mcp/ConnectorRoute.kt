package com.sabreware.aide.ui.settings.mcp

import kotlinx.serialization.Serializable

/**
 * The connector flow as ONE host-agnostic route family. The same routes render as full screens (registered in
 * the app NavHost from Settings) or as sheet pages (in [ConnectorDialog] from chat) — see [ConnectorPages].
 * [Detail] carries the connector *id* (a plain String) so it works as a type-safe NavHost arg without a custom
 * NavType; the page resolves the [com.sabreware.aide.core.domain.connector.Connector] from the catalog.
 */
@Serializable
sealed interface ConnectorRoute {
    @Serializable data object Home : ConnectorRoute
    @Serializable data object Catalog : ConnectorRoute
    @Serializable data class Detail(val connectorId: String) : ConnectorRoute
    @Serializable data class AddCustom(val initialUrl: String = "") : ConnectorRoute
}
