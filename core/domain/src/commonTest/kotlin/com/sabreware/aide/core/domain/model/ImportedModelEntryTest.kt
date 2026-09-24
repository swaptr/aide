package com.sabreware.aide.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** Locks the imported-model document round-trip + upsert/remove + the entry → runnable LocalLlmModel mapping. */
class ImportedModelEntryTest {

    private val a = ImportedModelEntry(id = "import-a", displayName = "A", fileName = "a.litertlm", sizeBytes = 123)
    private val b = ImportedModelEntry(
        id = "import-b",
        displayName = "B",
        fileName = "b.litertlm",
        temperature = 0.6f,
        maxTokens = 2048,
        audioIn = true,
        thinking = true,
        preferGpu = false,
    )

    @Test
    fun documentRoundTripsTheList() {
        val doc = ImportedModels(listOf(a, b))
        val serializer = ModelDocuments.ImportedModels.serializer
        assertEquals(doc, Json.decodeFromString(serializer, Json.encodeToString(serializer, doc)))
    }

    @Test
    fun upsertReplacesBySameIdAndWithoutRemoves() {
        val renamed = a.copy(displayName = "A2")
        val doc = ImportedModels(listOf(a, b)).upsert(renamed)
        assertEquals(listOf(b, renamed), doc.models)
        assertEquals(listOf(renamed), doc.without("import-b").models)
        assertEquals(doc, doc.without("missing"))
    }

    @Test
    fun toSpecBuildsAnOnDiskLocalModelWithNoDownload() {
        val spec = ImportedModelEntry(
            id = "import-gemma",
            displayName = "My Gemma",
            fileName = "gemma.litertlm",
            sizeBytes = 999,
            topK = 64,
            temperature = 0.7f,
            maxTokens = 4096,
            audioIn = true,
            thinking = true,
            preferGpu = false,
        ).toSpec()

        assertEquals("import-gemma", spec.id)
        assertEquals("My Gemma", spec.displayName)
        assertEquals("gemma.litertlm", spec.artifact.fileName)
        assertEquals(null, spec.artifact.downloadUrl)
        assertFalse(spec.requiresDownload, "no download URL ⇒ already on disk")
        assertEquals(ModelBackend.CPU, spec.defaultBackend)
        assertTrue(spec.capabilities.audioIn)
        assertEquals(ChatCapabilities.ThinkingMode.Toggle, spec.capabilities.thinking)
        assertEquals(64, spec.defaultConfig?.topK)
        assertEquals(0.7f, spec.defaultConfig?.temperature)
    }
}
