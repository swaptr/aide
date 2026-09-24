package com.sabreware.aide.data

import com.sabreware.aide.core.common.persist.PersistedDocument
import com.sabreware.aide.data.catalog.RemoteCatalogCache

/**
 * The persisted documents this module owns. `PersistedDocumentSchemaTest` checks each against
 * `data/schemas/documents/` — a document not listed here has no ledger.
 */
object DataDocuments {
    val all: List<PersistedDocument<*>> = listOf(RemoteCatalogCache.Document)
}
