package com.sabreware.aide.aisdk.providers.bedrock

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.ImageResult
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.NoImageGeneratedError
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.AwsCredentials
import com.sabreware.aide.aisdk.util.ProviderHttp
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Amazon Nova Canvas, over `InvokeModel`.
 *
 * One body shape per task, and the task is chosen by what the caller supplied rather than by a separate
 * edit endpoint: no input image is `TEXT_IMAGE`; an input image with a mask — an image, or a
 * `maskPrompt` naming the region — is `INPAINTING`; an input image with neither is `IMAGE_VARIATION`.
 * `OUTPAINTING` and `BACKGROUND_REMOVAL` are only ever asked for by name, through
 * `providerOptions["amazon-bedrock"].taskType`, because nothing in the neutral call distinguishes them
 * from the inferred pair.
 *
 * Every option keeps the vendor's own spelling (`negativeText`, `cfgScale`, `outPaintingMode`,
 * `similarityStrength`) and goes out exactly where Nova reads it: the generation knobs under
 * `imageGenerationConfig` — which `BACKGROUND_REMOVAL` alone does not take — and the task's own
 * parameters under its own key. Numbers are passed through as the caller wrote them rather than
 * re-serialized, so a `cfgScale` of `7` does not become `7.0` on the wire. The legacy `bedrock` options
 * key is read after ours, the way the rest of this package reads it.
 *
 * Input images are base64 on the wire, so an [ImageFile.Url] is refused rather than fetched: Nova takes
 * no link, and downloading on the caller's behalf would put this model in the business of reaching
 * arbitrary hosts with the caller's credentials. The reference refuses for the same reason.
 *
 * Verified against the Nova Canvas request structure on 2026-09-02:
 * https://docs.aws.amazon.com/nova/latest/userguide/image-gen-req-resp-structure.html
 */
internal class BedrockImageModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val credentials: () -> AwsCredentials,
    private val region: String,
    private val now: () -> Long,
    /** The runtime endpoint; defaults to the region's own, and a host with an override passes it. */
    private val baseUrl: String = bedrockBaseUrl(region),
) : ImageModel {

    override val provider: String = BEDROCK_PROVIDER_ID

    /**
     * Nova Canvas takes up to five per call; anything else reached through this endpoint is assumed to
     * take one.
     *
     * Matched by family rather than by the reference's exact-id table, because a cross-region profile
     * (`us.amazon.nova-canvas-v1:0`) is the same model with a prefix, and the docs put the ceiling on
     * the model, not on the id it was reached through.
     */
    override suspend fun maxImagesPerCall(): Int =
        if (NOVA_CANVAS_FAMILY in modelId) NOVA_CANVAS_MAX_IMAGES else 1

    override suspend fun doGenerate(options: ImageCallOptions): ImageResult {
        val warnings = mutableListOf<Warning>()
        if (options.aspectRatio != null) {
            warnings += Warning.Unsupported(
                feature = "aspectRatio",
                details = "This model does not support aspect ratio. Use `size` instead.",
            )
        }
        val vendor = options.providerOptions.bedrockEntry() ?: JsonObject(emptyMap())
        val body = buildBody(options, vendor, warnings)

        val result = http.bedrockSignedPost(
            url = bedrockInvokeUrl(baseUrl, modelId),
            body = body,
            extraHeaders = options.headers,
            credentials = credentials(),
            region = region,
            timestampMillis = now(),
        )
        val response = result.value
        val identity = result.modalityResponse(modelId = modelId)

        // A moderated prompt comes back as a 200 carrying a status line and no images. Read before the
        // image check so the caller learns WHY there is nothing, not merely that there is nothing.
        if (response.optString("status") == MODERATED_STATUS) {
            val reasons = response.optObject("details")?.optArray("Moderation Reasons")?.strings().orEmpty()
            throw NoImageGeneratedError(
                message = "Amazon Bedrock request was moderated: " +
                    reasons.ifEmpty { listOf("Unknown") }.joinToString(", "),
                responses = listOf(identity),
            )
        }
        val images = response.optArray("images")?.strings().orEmpty()
        if (images.isEmpty()) {
            // Nova reports a per-image content-policy block in `error`, beside an `images` list one
            // shorter than asked for; when the list is EMPTY that field is the whole explanation.
            val detail = listOfNotNull(
                response.optString("status")?.let { "Status: $it" },
                response.optString("error"),
            ).joinToString(" ")
            throw NoImageGeneratedError(
                message = "Amazon Bedrock returned no images." + (if (detail.isEmpty()) "" else " $detail"),
                responses = listOf(identity),
            )
        }

        return ImageResult(
            images = images.map { BinaryData.Base64(it) },
            warnings = warnings,
            response = identity,
            request = RequestInfo(result.requestBody),
        )
    }

    private fun buildBody(
        options: ImageCallOptions,
        vendor: JsonObject,
        warnings: MutableList<Warning>,
    ): JsonObject {
        val files = options.files.orEmpty()
        if (files.isEmpty()) return textToImage(options, vendor, warnings)

        val maskPrompt = vendor.optString("maskPrompt")
        val taskType = vendor.optString("taskType")
            ?: if (options.mask != null || maskPrompt != null) INPAINTING else IMAGE_VARIATION
        val source = files.first().base64()
        return when (taskType) {
            INPAINTING, OUTPAINTING -> buildJsonObject {
                put("taskType", taskType)
                putJsonObject(if (taskType == INPAINTING) "inPaintingParams" else "outPaintingParams") {
                    put("image", source)
                    options.prompt?.let { put("text", it) }
                    vendor.optString("negativeText")?.let { put("negativeText", it) }
                    if (taskType == OUTPAINTING) {
                        vendor.optString("outPaintingMode")?.let { put("outPaintingMode", it) }
                    }
                    // Nova takes one of the two, never both; the image wins when the caller gave both,
                    // as in the reference.
                    val mask = options.mask
                    if (mask != null) put("maskImage", mask.base64()) else maskPrompt?.let { put("maskPrompt", it) }
                }
                put("imageGenerationConfig", generationConfig(options, vendor, warnings))
            }

            BACKGROUND_REMOVAL -> buildJsonObject {
                // The one task that takes no generation config: there is nothing to generate.
                put("taskType", BACKGROUND_REMOVAL)
                putJsonObject("backgroundRemovalParams") { put("image", source) }
            }

            IMAGE_VARIATION -> buildJsonObject {
                put("taskType", IMAGE_VARIATION)
                putJsonObject("imageVariationParams") {
                    put("images", JsonArray(files.map { file -> JsonPrimitive(file.base64()) }))
                    options.prompt?.let { put("text", it) }
                    vendor.optString("negativeText")?.let { put("negativeText", it) }
                    vendor.number("similarityStrength")?.let { put("similarityStrength", it) }
                }
                put("imageGenerationConfig", generationConfig(options, vendor, warnings))
            }

            else -> throw InvalidArgumentError("Unsupported task type: $taskType", argument = "taskType")
        }
    }

    private fun textToImage(
        options: ImageCallOptions,
        vendor: JsonObject,
        warnings: MutableList<Warning>,
    ): JsonObject {
        if (options.mask != null) {
            // The reference drops it silently; a mask with nothing to mask is a call that will not do
            // what its author expected, and the caller should hear that.
            warnings += Warning.Unsupported("mask", "A mask applies to an input image, and none was given.")
        }
        return buildJsonObject {
            put("taskType", TEXT_IMAGE)
            putJsonObject("textToImageParams") {
                options.prompt?.let { put("text", it) }
                vendor.optString("negativeText")?.let { put("negativeText", it) }
                vendor.optString("style")?.let { put("style", it) }
            }
            put("imageGenerationConfig", generationConfig(options, vendor, warnings))
        }
    }

    /**
     * The block every task but background removal carries.
     *
     * `seed` goes out whenever the caller set one, INCLUDING zero — a valid seed the reference drops
     * through a JavaScript truthiness check (`seed ? { seed } : {}`), which silently turns "reproduce
     * image zero" into "pick a seed".
     */
    private fun generationConfig(
        options: ImageCallOptions,
        vendor: JsonObject,
        warnings: MutableList<Warning>,
    ): JsonObject = buildJsonObject {
        options.size?.let { size -> putDimensions(size, warnings) }
        options.seed?.let { put("seed", it) }
        put("numberOfImages", options.n)
        vendor.optString("quality")?.let { put("quality", it) }
        vendor.number("cfgScale")?.let { put("cfgScale", it) }
    }

    private fun JsonObjectBuilder.putDimensions(size: String, warnings: MutableList<Warning>) {
        val dims = size.split('x').map { part -> part.toIntOrNull()?.takeIf { it > 0 } }
        val width = dims.getOrNull(0)
        val height = dims.getOrNull(1)
        if (width != null && height != null) {
            put("width", width)
            put("height", height)
        } else {
            warnings += Warning.Compatibility(
                feature = "size",
                details = "'$size' is not WIDTHxHEIGHT; the request was sent without dimensions.",
            )
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun ImageFile.base64(): String = when (this) {
        is ImageFile.Url -> throw UnsupportedFunctionalityError(
            functionality = "URL-based images",
            message = "URL-based images are not supported for Amazon Bedrock image editing. " +
                "Please provide the image data directly.",
        )

        is ImageFile.Data -> when (val payload = data) {
            is BinaryData.Base64 -> payload.value
            is BinaryData.Bytes -> Base64.encode(payload.value)
        }
    }

    /** A numeric option, passed through VERBATIM so `7` stays `7` and `1.2` stays `1.2` on the wire. */
    private fun JsonObject.number(key: String): JsonPrimitive? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it.content.toDoubleOrNull() != null }

    private fun JsonArray.strings(): List<String> =
        mapNotNull { element -> (element as? JsonPrimitive)?.takeIf { it.isString }?.content }

    private companion object {
        const val NOVA_CANVAS_FAMILY = "amazon.nova-canvas"
        const val NOVA_CANVAS_MAX_IMAGES = 5
        const val MODERATED_STATUS = "Request Moderated"
        const val TEXT_IMAGE = "TEXT_IMAGE"
        const val INPAINTING = "INPAINTING"
        const val OUTPAINTING = "OUTPAINTING"
        const val BACKGROUND_REMOVAL = "BACKGROUND_REMOVAL"
        const val IMAGE_VARIATION = "IMAGE_VARIATION"
    }
}
