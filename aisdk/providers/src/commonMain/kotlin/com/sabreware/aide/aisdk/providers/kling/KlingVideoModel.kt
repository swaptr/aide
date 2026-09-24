package com.sabreware.aide.aisdk.providers.kling

import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.NoSuchModelError
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoFile
import com.sabreware.aide.aisdk.VideoFrameType
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.VideoStartResult
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Which generation Kling is being asked for.
 *
 * The mode is not a parameter — it decides the ENDPOINT, and each endpoint takes a different body. A
 * client that guesses one path and posts every shape at it gets a 404 for three of the four modes.
 */
internal enum class KlingVideoMode(val endpointPath: String) {
    TextToVideo("/v1/videos/text2video"),
    ImageToVideo("/v1/videos/image2video"),
    MultiImageToVideo("/v1/videos/multi-image2video"),
    MotionControl("/v1/videos/motion-control"),
}

/**
 * Reads the mode out of the model id's suffix.
 *
 * @throws NoSuchModelError for an id with no mode suffix — Kling serves no model whose endpoint cannot
 * be derived, so an unrecognised id is a typo rather than a capability this port lacks.
 */
internal fun klingVideoMode(modelId: String): KlingVideoMode = when {
    modelId.endsWith("-t2v") -> KlingVideoMode.TextToVideo
    modelId.endsWith("-i2v") -> KlingVideoMode.ImageToVideo
    modelId.endsWith("-motion-control") -> KlingVideoMode.MotionControl
    else -> throw NoSuchModelError(modelId, NoSuchModelError.ModelType.VideoModel)
}

/**
 * The `model_name` Kling's API wants, which is not the id it is asked for.
 *
 * The suffix names the endpoint and must come off; the version is written with hyphens on the wire and
 * dots in the id; and a trailing `.0` is dropped entirely — `kling-v3.0-t2v` is `kling-v3`, not
 * `kling-v3-0`. Each of the three is a "model not found" that reads like an unavailable model.
 */
internal fun klingApiModelName(modelId: String, mode: KlingVideoMode): String {
    val suffix = when (mode) {
        KlingVideoMode.MotionControl -> "-motion-control"
        KlingVideoMode.MultiImageToVideo, KlingVideoMode.ImageToVideo -> "-i2v"
        KlingVideoMode.TextToVideo -> "-t2v"
    }
    return modelId.removeSuffix(suffix).removeSuffix(".0").replace('.', '-')
}

/** Options this model spends itself; anything else a caller passes goes through to the body verbatim. */
private val KLING_HANDLED_OPTIONS = setOf(
    "mode", "pollIntervalMs", "pollTimeoutMs", "negativePrompt", "sound", "cfgScale", "cameraControl",
    "multiShot", "shotType", "multiPrompt", "elementList", "voiceList", "imageTail", "staticMask",
    "dynamicMasks", "videoUrl", "characterOrientation", "keepOriginalSound", "watermarkEnabled",
)

/**
 * Kling AI video generation.
 *
 * The operation is `{taskId, endpointPath}` and not the task id alone: a status query goes to the same
 * endpoint the job was submitted to, so a handle carrying only the id cannot be polled after the process
 * that knew which mode was used has gone.
 */
internal class KlingVideoModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val token: () -> String,
) : VideoModel {

    override val provider: String = KLING_PROVIDER_ID

    override suspend fun maxVideosPerCall(): Int = 1

    override suspend fun doStart(options: VideoCallOptions, webhookUrl: String?): VideoStartResult {
        val warnings = mutableListOf<Warning>()
        val declaredMode = klingVideoMode(modelId)
        val klingOptions = options.providerOptions?.get(KLING_PROVIDER_ID)
        val referenceImages = referenceImages(options, warnings)

        // Reference-to-video is not its own model id: it is the image-to-video model handed several
        // guidance images, and it lives on a different endpoint.
        val mode = if (declaredMode == KlingVideoMode.ImageToVideo && referenceImages != null) {
            KlingVideoMode.MultiImageToVideo
        } else {
            declaredMode
        }

        val requested = when (mode) {
            KlingVideoMode.MotionControl -> motionControlBody(options, klingOptions, warnings)
            KlingVideoMode.TextToVideo -> textToVideoBody(options, klingOptions, warnings)
            KlingVideoMode.MultiImageToVideo ->
                multiImageBody(options, klingOptions, referenceImages.orEmpty(), warnings)
            KlingVideoMode.ImageToVideo -> imageToVideoBody(options, klingOptions, warnings)
        }
        if (referenceImages != null && mode != KlingVideoMode.MultiImageToVideo) {
            warnings += Warning.Unsupported(
                "references",
                "KlingAI only supports reference images on image-to-video models. " +
                    "The reference images were ignored.",
            )
        }
        universalWarnings(options, warnings)
        val body = requested.withCallbackUrl(webhookUrl)

        val result = http.postJson(
            url = "$baseUrl${mode.endpointPath}",
            body = body,
            headers = combineHeaders(klingAuthHeaders(token()), options.headers),
        )
        // `as? JsonObject`, not `.jsonObject`: Kling answers a rejected submit with HTTP 200 and
        // `"data": null`, and the throwing accessor turns that into a raw IllegalArgumentException
        // about JsonNull rather than the "no task_id" this actually is.
        val taskId = (result.value.jsonObject["data"] as? JsonObject)
            ?.get("task_id")?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("Kling returned no task_id")

        return VideoStartResult(
            operation = buildJsonObject {
                put("taskId", taskId)
                put("endpointPath", mode.endpointPath)
            },
            warnings = warnings,
            response = result.modalityResponse(modelId = modelId),
        )
    }

    override suspend fun doStatus(operation: JsonElement, headers: Map<String, String>?): VideoStatusResult {
        val handle = operation.jsonObject
        val taskId = handle["taskId"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("The Kling operation carries no taskId")
        val endpointPath = handle["endpointPath"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("The Kling operation carries no endpointPath")

        // The URL is built from our own base and the handle we minted, so the first hop is trusted. The
        // guard is for what comes AFTER it: a redirect off the API host is validated before it is
        // followed and carries no credential, so a poll can neither reach a metadata service nor hand
        // the key to whichever host the redirect named.
        val result = http.getBytes(
            url = "$baseUrl$endpointPath/$taskId",
            headers = combineHeaders(klingAuthHeaders(token()), headers),
            trustedOrigin = baseUrl,
        )
        val data = parseJsonObject(result.value.decodeToString())["data"] as? JsonObject
        val response = result.modalityResponse(modelId = modelId)

        return when (data?.get("task_status")?.jsonPrimitive?.content) {
            // `succeed`, not `succeeded`. Matching the English past tense polls a finished job forever.
            "succeed" -> completed(data, taskId, response)
            "failed" -> VideoStatusResult.Failed(
                error = "Video generation failed: " +
                    (data["task_status_msg"]?.jsonPrimitive?.content ?: "Unknown error"),
                response = response,
            )
            else -> VideoStatusResult.Pending(response = response)
        }
    }

    private fun completed(
        data: JsonObject,
        taskId: String,
        response: com.sabreware.aide.aisdk.ModalityResponse,
    ): VideoStatusResult {
        val videos = ((data["task_result"] as? JsonObject)?.get("videos") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it["url"]?.jsonPrimitive?.content != null }
        if (videos.isEmpty()) throw NoContentGeneratedError("Kling reported success with no video URLs")

        return VideoStatusResult.Completed(
            videos = videos.map {
                VideoData.Url(it.getValue("url").jsonPrimitive.content, mediaType = "video/mp4")
            },
            providerMetadata = mapOf(
                KLING_PROVIDER_ID to buildJsonObject {
                    put("taskId", taskId)
                    put(
                        "videos",
                        buildJsonArray {
                            videos.forEach { video ->
                                add(
                                    buildJsonObject {
                                        put("id", video["id"] ?: JsonPrimitive(""))
                                        put("url", video.getValue("url"))
                                        video["watermark_url"]?.let { put("watermarkUrl", it) }
                                        video["duration"]?.let { put("duration", it) }
                                    },
                                )
                            }
                        },
                    )
                },
            ),
            response = response,
        )
    }

    private fun textToVideoBody(
        options: VideoCallOptions,
        klingOptions: JsonObject?,
        warnings: MutableList<Warning>,
    ): JsonObject = buildJsonObject {
        put("model_name", klingApiModelName(modelId, KlingVideoMode.TextToVideo))
        options.prompt?.let { put("prompt", it) }
        klingOptions?.get("negativePrompt")?.let { put("negative_prompt", it) }
        klingOptions?.get("sound")?.let { put("sound", it) }
        klingOptions?.get("cfgScale")?.let { put("cfg_scale", it) }
        klingOptions?.get("mode")?.let { put("mode", it) }
        klingOptions?.get("cameraControl")?.let { put("camera_control", it) }
        options.aspectRatio?.let { put("aspect_ratio", it) }
        options.durationInSeconds?.let { put("duration", it.asKlingDuration()) }
        putMultiShot(klingOptions)
        klingOptions?.get("voiceList")?.let { put("voice_list", it) }
        putWatermark(klingOptions)
        if (startImage(options, warnings) != null) {
            warnings += Warning.Unsupported(
                "image",
                "KlingAI text-to-video does not support image input. Use an image-to-video model instead.",
            )
        }
        putPassthrough(klingOptions)
    }

    private fun imageToVideoBody(
        options: VideoCallOptions,
        klingOptions: JsonObject?,
        warnings: MutableList<Warning>,
    ): JsonObject = buildJsonObject {
        put("model_name", klingApiModelName(modelId, KlingVideoMode.ImageToVideo))
        options.prompt?.let { put("prompt", it) }
        startImage(options, warnings)?.let { put("image", it.toKlingImage()) }
        imageTail(options, klingOptions, warnings)?.let { put("image_tail", it) }
        klingOptions?.get("negativePrompt")?.let { put("negative_prompt", it) }
        klingOptions?.get("sound")?.let { put("sound", it) }
        klingOptions?.get("cfgScale")?.let { put("cfg_scale", it) }
        klingOptions?.get("mode")?.let { put("mode", it) }
        klingOptions?.get("cameraControl")?.let { put("camera_control", it) }
        klingOptions?.get("staticMask")?.let { put("static_mask", it) }
        klingOptions?.get("dynamicMasks")?.let { put("dynamic_masks", it) }
        putMultiShot(klingOptions)
        klingOptions?.get("elementList")?.let { put("element_list", it) }
        klingOptions?.get("voiceList")?.let { put("voice_list", it) }
        putWatermark(klingOptions)
        options.durationInSeconds?.let { put("duration", it.asKlingDuration()) }
        if (options.aspectRatio != null) {
            warnings += Warning.Unsupported(
                "aspectRatio",
                "KlingAI image-to-video does not support aspectRatio. " +
                    "The output dimensions are determined by the input image.",
            )
        }
        putPassthrough(klingOptions)
    }

    private fun multiImageBody(
        options: VideoCallOptions,
        klingOptions: JsonObject?,
        references: List<VideoFile>,
        warnings: MutableList<Warning>,
    ): JsonObject = buildJsonObject {
        put("model_name", klingApiModelName(modelId, KlingVideoMode.MultiImageToVideo))
        put(
            "image_list",
            buildJsonArray {
                references.forEach { add(buildJsonObject { put("image", it.toKlingImage()) }) }
            },
        )
        options.prompt?.let { put("prompt", it) }
        klingOptions?.get("negativePrompt")?.let { put("negative_prompt", it) }
        klingOptions?.get("cfgScale")?.let { put("cfg_scale", it) }
        klingOptions?.get("mode")?.let { put("mode", it) }
        options.aspectRatio?.let { put("aspect_ratio", it) }
        options.durationInSeconds?.let { put("duration", it.asKlingDuration()) }
        putWatermark(klingOptions)
        if (startImage(options, warnings) != null) {
            warnings += Warning.Unsupported(
                "image",
                "KlingAI reference-to-video does not support a separate start frame. " +
                    "Provide all guidance images via references instead.",
            )
        }
        if (imageTail(options, klingOptions, warnings) != null) {
            warnings += Warning.Unsupported(
                "frameImages",
                "KlingAI reference-to-video does not support a last frame (image_tail). " +
                    "Provide all guidance images via references instead.",
            )
        }
        putPassthrough(klingOptions)
    }

    private fun motionControlBody(
        options: VideoCallOptions,
        klingOptions: JsonObject?,
        warnings: MutableList<Warning>,
    ): JsonObject {
        val videoUrl = klingOptions?.get("videoUrl")
        val orientation = klingOptions?.get("characterOrientation")
        val mode = klingOptions?.get("mode")
        if (videoUrl == null || orientation == null || mode == null) {
            // Not a warning: motion control without the driving video is a request with no subject, and
            // sending it produces a 400 that names none of the three missing fields.
            throw InvalidArgumentError(
                message = "KlingAI Motion Control requires providerOptions.klingai with videoUrl, " +
                    "characterOrientation and mode.",
                argument = "providerOptions.klingai",
            )
        }
        return buildJsonObject {
            put("model_name", klingApiModelName(modelId, KlingVideoMode.MotionControl))
            put("video_url", videoUrl)
            put("character_orientation", orientation)
            put("mode", mode)
            options.prompt?.let { put("prompt", it) }
            startImage(options, warnings)?.let { put("image_url", it.toKlingImage()) }
            klingOptions["keepOriginalSound"]?.let { put("keep_original_sound", it) }
            putWatermark(klingOptions)
            klingOptions["elementList"]?.let { put("element_list", it) }
            if (options.aspectRatio != null) {
                warnings += Warning.Unsupported(
                    "aspectRatio",
                    "KlingAI Motion Control does not support aspectRatio. " +
                        "The output dimensions are determined by the reference image and video.",
                )
            }
            if (options.durationInSeconds != null) {
                warnings += Warning.Unsupported(
                    "durationInSeconds",
                    "KlingAI Motion Control does not support custom duration. " +
                        "The output duration matches the reference video.",
                )
            }
            putPassthrough(klingOptions)
        }
    }
}

private fun JsonObjectBuilder.putMultiShot(klingOptions: JsonObject?) {
    klingOptions?.get("multiShot")?.let { put("multi_shot", it) }
    klingOptions?.get("shotType")?.let { put("shot_type", it) }
    klingOptions?.get("multiPrompt")?.let { put("multi_prompt", it) }
}

private fun JsonObjectBuilder.putWatermark(klingOptions: JsonObject?) {
    klingOptions?.get("watermarkEnabled")?.let { enabled ->
        put("watermark_info", buildJsonObject { put("enabled", enabled) })
    }
}

private fun JsonObjectBuilder.putPassthrough(klingOptions: JsonObject?) {
    klingOptions?.forEach { (key, value) -> if (key !in KLING_HANDLED_OPTIONS) put(key, value) }
}

/**
 * The caller's receiver, forwarded as the vendor's `callback_url`. An explicit URL wins over one a
 * caller spelled raw in the vendor options: the argument is the receiver the runtime actually
 * registered, and the raw key is whatever an older config still carries.
 */
private fun JsonObject.withCallbackUrl(webhookUrl: String?): JsonObject =
    if (webhookUrl == null) this else JsonObject(this + ("callback_url" to JsonPrimitive(webhookUrl)))

/** Kling wants whole seconds as a string; `5.0` is rejected where `5` is accepted. */
private fun Double.asKlingDuration(): String =
    if (this == toLong().toDouble()) toLong().toString() else toString()

private fun VideoFile.toKlingImage(): String = when (this) {
    is VideoFile.Url -> url
    // A bare base64 payload, not a data URI: Kling rejects the prefix.
    is VideoFile.Data -> klingImageString(data)
}

/** Kling animates images only; a clip handed in as a reference is dropped rather than silently mangled. */
private fun VideoFile.isVideo(): Boolean = mediaType?.startsWith("video/") == true

private fun referenceImages(options: VideoCallOptions, warnings: MutableList<Warning>): List<VideoFile>? {
    if (!options.frameImages.isNullOrEmpty()) return null
    val references = options.references?.takeIf { it.isNotEmpty() } ?: return null
    val images = references.filter { reference ->
        if (reference.isVideo()) {
            warnings += Warning.Unsupported(
                "references",
                "KlingAI does not support video reference inputs; the video reference was ignored.",
            )
            false
        } else {
            true
        }
    }
    return images.takeIf { it.isNotEmpty() }
}

private fun startImage(options: VideoCallOptions, warnings: MutableList<Warning>): VideoFile? {
    val image = options.frameImages
        ?.firstOrNull { it.frameType == VideoFrameType.FirstFrame }?.image
        ?: options.image
    if (image != null && image.isVideo()) {
        warnings += Warning.Unsupported(
            "frameImages",
            "KlingAI does not accept video as a frame image; it was ignored.",
        )
        return null
    }
    return image
}

private fun imageTail(
    options: VideoCallOptions,
    klingOptions: JsonObject?,
    warnings: MutableList<Warning>,
): String? {
    val lastFrame = options.frameImages?.firstOrNull { it.frameType == VideoFrameType.LastFrame }?.image
    if (lastFrame != null) {
        if (lastFrame.isVideo()) {
            warnings += Warning.Unsupported(
                "frameImages",
                "KlingAI does not accept video as a frame image; it was ignored.",
            )
            return null
        }
        return lastFrame.toKlingImage()
    }
    return klingOptions?.get("imageTail")?.jsonPrimitive?.content
}

private fun universalWarnings(options: VideoCallOptions, warnings: MutableList<Warning>) {
    if (options.resolution != null) {
        warnings += Warning.Unsupported(
            "resolution",
            "KlingAI video models do not support the resolution option.",
        )
    }
    if (options.seed != null) {
        warnings += Warning.Unsupported(
            "seed",
            "KlingAI video models do not support seed for deterministic generation.",
        )
    }
    if (options.fps != null) {
        warnings += Warning.Unsupported("fps", "KlingAI video models do not support custom FPS.")
    }
    if (options.n > 1) {
        warnings += Warning.Unsupported(
            "n",
            "KlingAI video models do not support generating multiple videos per call. " +
                "Only 1 video will be generated.",
        )
    }
}
