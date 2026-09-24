package com.sabreware.aide.core.domain.model

import com.sabreware.aide.core.common.persist.SchemaLedger
import com.sabreware.aide.core.domain.connection.ConnectionDocuments
import com.sabreware.aide.core.domain.label.LabelDocuments
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.fail

/**
 * Pins every document in [ModelDocuments.all], [ConnectionDocuments.all] and [LabelDocuments.all] to its ledger entry under `core/domain/schemas/documents/`.
 *
 * A new document or a bumped version writes its entry and fails once, asking for a commit; a class that
 * drifted from its committed entry fails with "bump the version". `./gradlew schemaCheck` then refuses a
 * rewritten entry. Runs on desktop only because it touches the real working tree (Gradle runs JVM tests
 * with the module directory as the working directory).
 */
class PersistedDocumentSchemaTest {
    @Test
    fun `every core domain document matches its committed ledger entry`() {
        val problems = SchemaLedger.verify(
            FileSystem.SYSTEM, "schemas".toPath(),
            ModelDocuments.all + ConnectionDocuments.all + LabelDocuments.all,
        )
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }
}
