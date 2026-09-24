package com.sabreware.aide.core.domain.model

import com.sabreware.aide.core.domain.speech.SpeechAssetFamily
import com.sabreware.aide.core.domain.speech.SpeechAssetSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The point of the [ModelDescriptor] spine: modality-agnostic machinery can hold any model, and a
 * non-chat one can be declared without inventing chat facts for it. Both were impossible while
 * `ModelSpec` was sealed over two LLM subtypes and every spec carried [ChatCapabilities].
 */
class ModelDescriptorTest {

    /** A model that generates images: no context window, no thinking mode, no sampler defaults. */
    private data class ImageModel(
        override val id: String,
        override val displayName: String,
        override val remoteName: String,
        override val provider: ProviderId,
    ) : ModelSpec {
        override val modality = Modality.Image
        override val family = "test-diffusion"
        override val params = ""
        override val quantization = ""
        override val cloud = true
        override val minRamGb = 0
        override val recommendedRamGb = 0
        override val defaultBackend = ModelBackend.CPU
        override val licenseName = "(provider terms)"
        override val licenseUrl = ""
        override val sourceUrl = ""
        override val taskTypes = emptyList<String>()
        override val runtimeType: String? = null
        override val minDeviceMemoryInGb: Int? = null
        override val description: String? = null
        override val learnMoreUrl: String? = null
        override val updateInfo: String? = null
        override val parentModelName: String? = null
        override val artifact: ModelArtifact? = null
    }

    private val image = ImageModel("img-1", "Test Image", "test-image-1", OPENAI)

    private val speech = SpeechAssetSpec(
        id = "vad-silero",
        displayName = "Silero VAD",
        modality = Modality.Vad,
        family = SpeechAssetFamily.SILERO_VAD,
        provider = ProviderId.SHERPA,
        downloadUrl = "https://example.invalid/silero.onnx",
        fileName = "silero.onnx",
        extractedDirName = "silero",
        sizeBytes = 2_000_000,
        locale = "",
        sampleRate = 16_000,
        licenseName = "MIT",
        licenseUrl = "",
        sourceUrl = "",
    )

    @Test
    fun `a non-chat model needs no chat capabilities to exist`() {
        assertEquals(Modality.Image, image.modality)
        assertFalse(image is ChatModelSpec)
    }

    @Test
    fun `both spec hierarchies answer the same modality-agnostic questions`() {
        val descriptors: List<ModelDescriptor> = listOf(image, speech)

        assertEquals(listOf(Modality.Image, Modality.Vad), descriptors.map { it.modality })
        assertEquals(listOf(OPENAI, ProviderId.SHERPA), descriptors.map { it.provider })
    }

    // The property speech could not express before it joined the spine.
    @Test
    fun `requiresDownload follows the download URL, not the hierarchy`() {
        assertTrue(speech.requiresDownload)
        assertFalse(image.requiresDownload)
    }

    @Test
    fun `a chat model is still a model, and still carries its chat capabilities`() {
        val chat: ModelSpec = RemoteLlmModel(
            id = "openai-test01:gpt-test",
            displayName = "GPT Test",
            family = "gpt",
            params = "",
            quantization = "(cloud)",
            remoteName = "gpt-test",
            cloud = true,
            minRamGb = 0,
            recommendedRamGb = 0,
            capabilities = ChatCapabilities(maxContext = 128_000, maxOutput = 4_096),
            defaultBackend = ModelBackend.CPU,
            licenseName = "(provider terms)",
            licenseUrl = "",
            sourceUrl = "",
            provider = OPENAI,
        )

        assertEquals(Modality.Chat, chat.modality)
        assertIs<ChatModelSpec>(chat)
        assertEquals(128_000, chat.capabilities.maxContext)
    }

    private companion object {
        /** A user connection: cloud provider ids are connection ids. */
        val OPENAI = ProviderId("openai-test01")
    }
}
