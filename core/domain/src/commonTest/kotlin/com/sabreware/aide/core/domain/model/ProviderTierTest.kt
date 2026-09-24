package com.sabreware.aide.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Behavior lock for [ProviderTier.of] against the sealed hierarchy: [LocalLlmModel] -> LOCAL;
 * [RemoteLlmModel] -> `<provider>-cloud` when `cloud`, else `<provider>` (the provider is the serving connection).
 */
class ProviderTierTest {

    private fun local(): ModelSpec = LocalLlmModel(
        id = "test",
        displayName = "test",
        family = "test",
        params = "1B",
        quantization = "q4",
        artifact = ModelArtifact(downloadUrl = "https://host/m.bin", fileName = "m.bin", sizeBytes = null),
        minRamGb = 2,
        recommendedRamGb = 4,
        capabilities = ChatCapabilities(maxContext = 4096, maxOutput = 1024),
        defaultBackend = ModelBackend.CPU,
        licenseName = "test",
        licenseUrl = "https://example.com/license",
        sourceUrl = "https://example.com/source",
    )

    private fun remote(cloud: Boolean): ModelSpec = RemoteLlmModel(
        id = "openai-test01:test",
        displayName = "test",
        family = "test",
        params = "1B",
        quantization = "q4",
        remoteName = "test",
        cloud = cloud,
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
    fun localModel_mapsTo_LOCAL() {
        assertEquals(ProviderTier.LOCAL, ProviderTier.of(local()))
    }

    @Test
    fun remoteCloud_mapsTo_providerCloudTier() {
        assertEquals(ProviderTier("openai-test01-cloud"), ProviderTier.of(remote(cloud = true)))
    }

    @Test
    fun remoteNonCloud_mapsTo_providerTier() {
        assertEquals(ProviderTier("openai-test01"), ProviderTier.of(remote(cloud = false)))
    }
}
