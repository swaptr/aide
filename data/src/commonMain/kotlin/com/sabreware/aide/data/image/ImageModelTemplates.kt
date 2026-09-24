package com.sabreware.aide.data.image

import com.sabreware.aide.core.domain.connection.VendorId
import com.sabreware.aide.core.domain.model.ImageCapabilities
import com.sabreware.aide.core.domain.model.ImageModelSpec
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.model.RemoteImageModel

/**
 * The image models each vendor serves. A hand-written list rather than a live `/v1/models` fetch: the
 * image endpoints do not advertise which of their models draw, and the set changes rarely enough that a
 * wrong guess would be worse than a short list.
 *
 * Templates, not models: a row becomes a model only through a connection ([forConnection]), which names it
 * `"<connectionId>:<remoteName>"` and routes it to that connection's key. What the connection's endpoint
 * actually serves is its vendor's decision (an Ollama endpoint on the OpenAI-compatible wire draws nothing).
 */
object ImageModelTemplates {

    /** [vendor]'s image models, named for connection [provider]; empty for a vendor that does not draw. */
    fun forConnection(vendor: VendorId, provider: ProviderId): List<ImageModelSpec> =
        rows.filter { it.first == vendor }.map { (_, row) -> row.copy(id = "${provider.value}:${row.remoteName}", provider = provider) }

    // Declared before [rows]: object properties initialise in order.
    private val TEMPLATE = ProviderId("template")

    // Ids and providers here are placeholders; [forConnection] re-mints both for the connection.
    private val rows: List<Pair<VendorId, RemoteImageModel>> = listOf(
        VendorId.OPENAI_COMPATIBLE to RemoteImageModel(
            id = "gpt-image-1",
            displayName = "GPT Image 1",
            remoteName = "gpt-image-1",
            provider = TEMPLATE,
            capabilities = ImageCapabilities(
                sizes = setOf("1024x1024", "1024x1536", "1536x1024", "auto"),
                transparentBackground = true,
                outputFormats = setOf("png", "webp", "jpeg"),
            ),
            description = "OpenAI's general-purpose image model. Always returns image bytes.",
            sourceUrl = "https://platform.openai.com/docs/models/gpt-image-1",
        ),
        VendorId.OPENAI_COMPATIBLE to RemoteImageModel(
            id = "dall-e-3",
            displayName = "DALL·E 3",
            remoteName = "dall-e-3",
            provider = TEMPLATE,
            capabilities = ImageCapabilities(
                sizes = setOf("1024x1024", "1024x1792", "1792x1024"),
                outputFormats = setOf("png"),
            ),
            sourceUrl = "https://platform.openai.com/docs/models/dall-e-3",
        ),
        // A Gemini image model is a Gemini chat model asked to answer with an image, so it thinks in
        // aspect ratios rather than pixel sizes: no size list, and a requested size is ignored with a
        // warning rather than cropped to the nearest ratio.
        VendorId.GEMINI to RemoteImageModel(
            id = "gemini-2.5-flash-image",
            displayName = "Gemini 2.5 Flash Image",
            remoteName = "gemini-2.5-flash-image",
            provider = TEMPLATE,
            capabilities = ImageCapabilities(outputFormats = setOf("png")),
            description = "Google's native image generation and editing model. Returns image bytes.",
            sourceUrl = "https://ai.google.dev/gemini-api/docs/image-generation",
        ),
    )
}
