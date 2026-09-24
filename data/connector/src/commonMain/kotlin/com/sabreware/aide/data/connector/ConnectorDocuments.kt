package com.sabreware.aide.data.connector

import com.sabreware.aide.core.common.persist.PersistedDocument
import com.sabreware.aide.data.connector.directory.DocumentConnectorDirectoryCache

/**
 * The persisted documents this module owns. `PersistedDocumentSchemaTest` checks each against
 * `data/connector/schemas/documents/` — a document not listed here has no ledger.
 */
object ConnectorDocuments {
    val all: List<PersistedDocument<*>> = listOf(DocumentConnectorDirectoryCache.Document)
}
