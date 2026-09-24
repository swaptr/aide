package com.sabreware.aide.ui.settings.mcp

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The connector flow as ONE host-agnostic route family. The same routes render as screens (pushed from
 * Settings) or as pages in a modal ([openConnectorFlow], from chat) — see [ConnectorPages]. [Detail] carries
 * the connector *id* (a plain String, like every route arg); the page resolves the
 * [com.sabreware.aide.core.domain.connector.Connector] from the catalog.
 */
@Serializable
sealed interface ConnectorRoute : NavKey {
    @Serializable data object Home : ConnectorRoute
    @Serializable data object Catalog : ConnectorRoute
    @Serializable data class Detail(val connectorId: String) : ConnectorRoute
    @Serializable data class AddCustom(val initialUrl: String = "") : ConnectorRoute
}
