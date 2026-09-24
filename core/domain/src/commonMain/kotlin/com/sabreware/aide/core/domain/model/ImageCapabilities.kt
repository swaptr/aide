package com.sabreware.aide.core.domain.model

/**
 * What an image model can do — the image modality's peer of [ChatCapabilities], and the reason neither is
 * declared on [ModelSpec]: a model that draws pictures has no context window, and one that holds a
 * conversation has no output sizes.
 */
data class ImageCapabilities(
    /** The `size` strings this model accepts. Empty = the model takes whatever the provider defaults to. */
    val sizes: Set<String> = emptySet(),
    /** Accepts a transparent background request. */
    val transparentBackground: Boolean = false,
    /** Output formats the model can emit (`png`, `webp`, `jpeg`). */
    val outputFormats: Set<String> = setOf("png"),
)

/** A model served through the image modality. */
interface ImageModelSpec : ModelSpec {
    override val modality: Modality get() = Modality.Image
    val capabilities: ImageCapabilities
}

/** A remote image model — addressed by [remoteName]; nothing is downloaded. */
data class RemoteImageModel(
    override val id: String,
    override val displayName: String,
    override val remoteName: String,
    override val provider: ProviderId,
    override val capabilities: ImageCapabilities,
    override val description: String? = null,
    override val licenseName: String = "(provider terms)",
    override val licenseUrl: String = "",
    override val sourceUrl: String = "",
) : ImageModelSpec {
    override val family: String get() = ""
    override val params: String get() = ""
    override val quantization: String get() = ""
    override val cloud: Boolean get() = true
    override val minRamGb: Int get() = 0
    override val recommendedRamGb: Int get() = 0
    override val defaultBackend: ModelBackend get() = ModelBackend.CPU
    override val taskTypes: List<String> get() = emptyList()
    override val runtimeType: String? get() = null
    override val minDeviceMemoryInGb: Int? get() = null
    override val learnMoreUrl: String? get() = null
    override val updateInfo: String? get() = null
    override val parentModelName: String? get() = null
    override val artifact: ModelArtifact? get() = null
}
