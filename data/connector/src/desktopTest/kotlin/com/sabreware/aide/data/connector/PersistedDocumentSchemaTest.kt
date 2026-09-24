package com.sabreware.aide.data.connector

import com.sabreware.aide.core.common.persist.SchemaLedger
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.fail

/** Pins every document in [ConnectorDocuments.all] to its ledger entry under `data/connector/schemas/documents/`. */
class PersistedDocumentSchemaTest {
    @Test
    fun `every connector document matches its committed ledger entry`() {
        val problems = SchemaLedger.verify(FileSystem.SYSTEM, "schemas".toPath(), ConnectorDocuments.all)
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }
}
