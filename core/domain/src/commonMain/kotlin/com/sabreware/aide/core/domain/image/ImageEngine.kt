package com.sabreware.aide.core.domain.image

/**
 * Generates images. A peer of [com.sabreware.aide.core.domain.llm.LlmEngine], not an extension of it: the
 * model that holds the conversation and the model that draws the picture are independent.
 *
 * Transport-free — no HTTP types, no vendor fields. Editing and variations are deliberately absent until
 * something calls them; they arrive as capability-gated additions, not as methods that throw today.
 */
interface ImageEngine {
    suspend fun generate(
        modelName: String,
        prompt: String,
        options: ImageOptions = ImageOptions(),
    ): List<ImageResult>
}

/**
 * Neutral generation knobs. [size] and [quality] stay opaque strings on purpose: a newer model that accepts
 * arbitrary `WIDTHxHEIGHT` must not require a code change here.
 */
data class ImageOptions(
    val count: Int = 1,
    val size: String? = null,
    val quality: String? = null,
    val outputFormat: String = "png",
    val background: String? = null,
)

/**
 * One generated image. The bytes-vs-URL split is real and lives here rather than being flattened: some
 * models only ever return base64, others only a URL. Consumers normalise to a stored file — this is not a
 * reason to widen [com.sabreware.aide.core.domain.chat.AidePart].
 */
sealed interface ImageResult {
    data class Bytes(val bytes: ByteArray, val format: String) : ImageResult {
        // ByteArray equality is identity by default, which would make two equal results compare unequal.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Bytes && format == other.format && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + format.hashCode()
    }

    data class Url(val url: String) : ImageResult
}
