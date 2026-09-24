package com.sabreware.aide.core.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavior lock for [ModelSpec.requiresDownload]: true iff a download URL is present. A
 * [RemoteLlmModel] never requires download; a [LocalLlmModel] does ONLY when its artifact has a
 * `downloadUrl` — orphan/AICore locals (artifact present, url null) must report false.
 */
class ModelSpecTest {

    private fun local(): ModelSpec = LocalLlmModel(
        id = "test",
        displayName = "Test",
        family = "test",
        params = "1B",
        quantization = "q4",
        artifact = ModelArtifact(downloadUrl = "https://host/model.bin", fileName = "model.bin", sizeBytes = null),
        minRamGb = 2,
        recommendedRamGb = 4,
        capabilities = ChatCapabilities(maxContext = 4096, maxOutput = 1024),
        defaultBackend = ModelBackend.CPU,
        licenseName = "test",
        licenseUrl = "https://example.com/license",
        sourceUrl = "https://example.com/source",
    )

    private fun remote(): ModelSpec = RemoteLlmModel(
        id = "ollama:llama3",
        displayName = "Test",
        family = "test",
        params = "1B",
        quantization = "q4",
        remoteName = "llama3",
        cloud = false,
        minRamGb = 2,
        recommendedRamGb = 4,
        capabilities = ChatCapabilities(maxContext = 4096, maxOutput = 1024),
        defaultBackend = ModelBackend.CPU,
        licenseName = "test",
        licenseUrl = "https://example.com/license",
        sourceUrl = "https://example.com/source",
        provider = ProviderId("openai-test01"),
    )

    @Test
    fun local_requiresDownload() {
        assertTrue(local().requiresDownload)
        assertTrue(local().downloadUrl != null)
    }

    @Test
    fun remote_doesNotRequireDownload() {
        assertFalse(remote().requiresDownload)
        assertTrue(remote().downloadUrl == null)
    }

    // Orphan/AICore local: artifact present but no downloadUrl -> visible but un-downloadable.
    @Test
    fun localWithoutDownloadUrl_doesNotRequireDownload() {
        val orphan = (local() as LocalLlmModel).copy(
            artifact = ModelArtifact(downloadUrl = null, fileName = null, sizeBytes = null),
        )
        assertFalse(orphan.requiresDownload)
    }
}
