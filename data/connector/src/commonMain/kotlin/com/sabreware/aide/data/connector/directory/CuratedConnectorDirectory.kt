package com.sabreware.aide.data.connector.directory

import com.sabreware.aide.core.domain.connector.Connector
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectory
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryDescriptor
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryId
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryKind
import com.sabreware.aide.core.domain.connector.directory.ConnectorSearchKind
import com.sabreware.aide.core.domain.connector.directory.matchingQuery
import com.sabreware.aide.data.connector.CuratedConnectors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The hand-curated overlay as a directory: the authoritative source of popularity, categories, and the correct
 * OAuth/remote URLs the public registry lacks (see [CuratedConnectors]). Bundled + on-device search; highest
 * merge precedence (priority 10) so it wins URL/auth/category/rank over any registry entry for the same brand.
 */
class CuratedConnectorDirectory : ConnectorDirectory {

    override val descriptor = ConnectorDirectoryDescriptor(
        id = ID,
        title = "Curated",
        description = "Hand-picked popular connectors with verified sign-in URLs.",
        kind = ConnectorDirectoryKind.BUNDLED,
        searchKind = ConnectorSearchKind.LOCAL,
        priority = 10,
    )

    override fun seed(): List<Connector> = CuratedConnectors.all
    override fun catalog(): Flow<List<Connector>> = flowOf(CuratedConnectors.all)
    override suspend fun refresh() = Unit
    override suspend fun search(query: String): List<Connector> = CuratedConnectors.all.matchingQuery(query)

    companion object { val ID = ConnectorDirectoryId("curated") }
}
