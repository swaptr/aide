package com.sabreware.aide.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.flow.emptyFlow

/**
 * [CustomProvider]'s lookup order and its adaptation of the reference's throwing contract to this
 * spec's null-versus-throw rule: the map answers first, the fallback second, and only a modality
 * somebody actually serves may throw [NoSuchModelError].
 */
class CustomProviderTest {

    private class StubModel(override val modelId: String) : LanguageModel {
        override val provider: String = "stub"
        override suspend fun doGenerate(options: CallOptions): GenerateResult =
            GenerateResult(
                content = emptyList(),
                finishReason = FinishReason(FinishReason.Unified.Stop),
                usage = Usage(),
            )

        override suspend fun doStream(options: CallOptions): StreamResult = StreamResult(emptyFlow())
    }

    private class StubEmbedder : EmbeddingModel {
        override val provider: String = "stub"
        override val modelId: String = "fallback-embed"
        override suspend fun doEmbed(options: EmbeddingCallOptions): EmbeddingResult =
            EmbeddingResult(embeddings = emptyList())
    }

    /** Serves language models (throwing on unknown ids) and embeddings; nothing else. */
    private class FallbackProvider : Provider {
        override val providerId: String = "fallback"
        val served = mutableListOf<String>()

        override fun languageModel(modelId: String): LanguageModel {
            served += modelId
            if (modelId == "fallback-only") return StubModel(modelId)
            throw NoSuchModelError(modelId, NoSuchModelError.ModelType.LanguageModel)
        }

        override fun embeddingModel(modelId: String): EmbeddingModel = StubEmbedder()
    }

    @Test
    fun `the map answers before the fallback is consulted`() {
        val alias = StubModel("vendor-model-9")
        val fallback = FallbackProvider()
        val provider = CustomProvider(
            languageModels = mapOf("fast" to alias),
            fallbackProvider = fallback,
        )

        assertSame(alias, provider.languageModel("fast"))
        assertEquals(emptyList(), fallback.served)
    }

    @Test
    fun `a miss falls through to the fallback, including its own error`() {
        val provider = CustomProvider(
            languageModels = mapOf("fast" to StubModel("vendor-model-9")),
            fallbackProvider = FallbackProvider(),
        )

        assertEquals("fallback-only", provider.languageModel("fallback-only")?.modelId)
        // The fallback serves language models, so ITS NoSuchModelError is the answer for a stranger.
        assertFailsWith<NoSuchModelError> { provider.languageModel("nobody") }
    }

    @Test
    fun `a non-empty map with no fallback throws on an unknown id`() {
        val provider = CustomProvider(languageModels = mapOf("fast" to StubModel("m")))

        val error = assertFailsWith<NoSuchModelError> { provider.languageModel("typo") }
        assertEquals("typo", error.modelId)
    }

    @Test
    fun `a modality nobody serves is null, not an exception`() {
        // No image map, and the fallback serves no images either: absence, per the Provider contract.
        assertNull(CustomProvider(fallbackProvider = FallbackProvider()).imageModel("anything"))
        assertNull(CustomProvider().videoModel("anything"))
    }

    @Test
    fun `a modality served only by the map throws for strangers even with a fallback that serves none`() {
        val provider = CustomProvider(
            imageModels = mapOf("draw" to StubImage()),
            fallbackProvider = FallbackProvider(),
        )

        // The fallback has no images to offer, but THIS provider does — so the unknown id is loud.
        assertFailsWith<NoSuchModelError> { provider.imageModel("typo") }
    }

    @Test
    fun `a modality the fallback serves passes through an empty map`() {
        val provider = CustomProvider(fallbackProvider = FallbackProvider())

        assertEquals("fallback-embed", provider.embeddingModel("any")?.modelId)
    }

    private class StubImage : ImageModel {
        override val provider: String = "stub"
        override val modelId: String = "img"
        override suspend fun doGenerate(options: ImageCallOptions): ImageResult =
            ImageResult(images = emptyList(), response = ModalityResponse())
    }
}
