package com.sabreware.aide.data.connection

import com.sabreware.aide.core.domain.connection.ConnectionRepository
import com.sabreware.aide.core.domain.connection.ProviderDirectory
import com.sabreware.aide.core.domain.connection.ProviderInfo
import com.sabreware.aide.core.domain.connection.VendorRegistry
import com.sabreware.aide.core.domain.connection.connections
import com.sabreware.aide.core.domain.connection.connectionsNow
import com.sabreware.aide.core.domain.connection.kind
import com.sabreware.aide.core.domain.label.LabelStore
import com.sabreware.aide.core.domain.label.LabelSubject
import com.sabreware.aide.core.domain.label.Labels
import com.sabreware.aide.core.domain.label.labels
import com.sabreware.aide.core.domain.label.labelsNow
import com.sabreware.aide.core.domain.connection.Connections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** [ProviderDirectory] over the connections document, the labels document and the contributed vendors. */
class ProviderDirectoryImpl(
    repository: ConnectionRepository,
    labels: LabelStore,
    private val vendors: VendorRegistry,
    scope: CoroutineScope,
) : ProviderDirectory {

    override val connections: StateFlow<Map<String, ProviderInfo>?> =
        combine(repository.connections, labels.labels, ::build)
            .stateIn(scope, SharingStarted.Eagerly, repository.connectionsNow?.let { build(it, labels.labelsNow) })

    private fun build(connections: Connections, labels: Labels): Map<String, ProviderInfo> =
        connections.list.associate { connection ->
            val label = labels[LabelSubject.connection(connection.id)]
            // Named by its SERVICE ("OpenRouter"), not its wire ("OpenAI compatible").
            val service = vendors.serviceOf(connection)
            connection.id to ProviderInfo(
                id = connection.providerId,
                name = label.alias ?: connection.label,
                kind = connection.kind,
                vendorName = service?.name ?: connection.vendor,
                vendor = connection.vendorId,
                tags = label.tags,
                baseUrl = connection.baseUrl,
            )
        }
}
