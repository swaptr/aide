package com.sabreware.aide.data

import com.sabreware.aide.core.common.persist.SchemaLedger
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.fail

/** Pins every document in [DataDocuments.all] to its ledger entry under `data/schemas/documents/`. */
class PersistedDocumentSchemaTest {
    @Test
    fun `every data document matches its committed ledger entry`() {
        val problems = SchemaLedger.verify(FileSystem.SYSTEM, "schemas".toPath(), DataDocuments.all)
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }
}
