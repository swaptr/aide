package com.sabreware.aide.aisdk.providers.minimax

import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoFile
import com.sabreware.aide.aisdk.VideoFrameType
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.VideoStartResult
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.parseJsonObject
import com.sabreware.aide.aisdk.providers.media.toDataUri
import io.ktor.http.encodeURLParameter
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val DEFAULT_RESOLUTION = "2K"
private const val DEFAULT_ASPECT_RATIO = "16:9"
private const val DEFAULT_DURATION_SECONDS = 5
private const val MAX_DURATION_SECONDS = 15
private const val MAX_REFERENCE_IMAGES = 9
private const val MAX_REFERENCE_VIDEOS = 3
private const val MAX_REFERENCE_AUDIOS = 3

private val MINIMAX_VIDEO_RATIOS = setOf("adaptive", "21:9", "16:9", "4:3", "1:1", "3:4", "9:16")
private val MINIMAX_VIDEO_RESOLUTIONS = setOf("480P", "768P", "2K")

/**
 * The API takes a named tier, and the map is convention rather than a rule: the 480P and 768P rows are
 * named for the SHORTER side, the 2K rows for the longer one. A frame size not listed here warns.
 */
private val MINIMAX_RESOLUTION_TIERS = mapOf(
    "480x480" to "480P", "1120x480" to "480P", "854x480" to "480P", "640x480" to "480P",
    "480x854" to "480P", "480x640" to "480P",
    "768x768" to "768P", "1792x768" to "768P", "1366x768" to "768P", "1024x768" to "768P",
    "768x1366" to "768P", "768x1024" to "768P",
    "2048x2048" to "2K", "2560x1080" to "2K", "2560x1440" to "2K", "2048x1536" to "2K",
    "1440x2560" to "2K", "1536x2048" to "2K",
)

/** Which tiers each model actually serves, and what it falls back to. */
private data class ResolutionMenu(val supported: Set<String>, val default: String)

private val MODEL_RESOLUTIONS = mapOf(
    "MiniMax-H3" to ResolutionMenu(setOf("768P", "2K"), DEFAULT_RESOLUTION),
    "MiniMax-H3-Max" to ResolutionMenu(setOf("480P", "768P"), "768P"),
)

/** MiniMax video errors nest under `error.message`, unlike the flat chat shape. */
internal val MiniMaxVideoErrors: ProviderErrorStructure = ProviderErrorStructure(
    extractMessage = { body ->
        (((body as? JsonObject)?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
    },
)

/**
 * MiniMax Hailuo video generation.
 *
 * A separate REST surface from the Anthropic-shaped chat endpoint: its own host path
 * (`/v2/video_generation`), and BEARER auth where chat uses `x-api-key` — the same key spelled two
 * ways, and each endpoint rejects the other's spelling.
 *
 * This port served the `doStart`/`doStatus` pair before the reference did; ai@7.0.102 (`46cea63`) has
 * since grown the same pair and keeps its polling `doGenerate` on top of it, so the shape is no longer
 * a divergence. What remains ours: the reference's `pollIntervalMs`/`pollTimeoutMs` options are
 * consumed as deprecated, because polling is the caller's schedule here (the Kling precedent), and the
 * inputs that actually survived the caps and rejections are reported as
 * `providerMetadata.minimax.resolvedInputs` on the START result rather than carried in the operation
 * — the warnings say an input was dropped, but not how many made it, which a caller that meters usage
 * needs, and they ride on the start because that is when they are known; a resumed poll in another
 * process never knew them. The reference stores input INDICES in its operation for the reason it
 * gives (URLs and inline data do not belong in a persisted handle); this handle carries only the task
 * id, so nothing here needs the indirection.
 */
internal class MiniMaxVideoModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : VideoModel {

    override val provider: String = MINIMAX_PROVIDER_ID

    override suspend fun maxVideosPerCall(): Int = 1

    override suspend fun doStart(options: VideoCallOptions, webhookUrl: String?): VideoStartResult {
        val warnings = mutableListOf<Warning>()
        val built = requestBody(options, warnings)

        val result = http.postJson(
            url = "$baseUrl/v2/video_generation",
            // MiniMax expects the receiver to echo a challenge within seconds and then posts progress
            // notifications, so the caller owns the receiver; this only names it.
            body = built.body.withCallbackUrl(webhookUrl),
            headers = combineHeaders(headers, options.headers),
        )
        val taskId = result.value.jsonObject["task_id"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
            ?: throw NoContentGeneratedError("MiniMax returned no task_id")

        return VideoStartResult(
            operation = buildJsonObject { put("taskId", taskId) },
            warnings = warnings,
            providerMetadata = mapOf(
                MINIMAX_PROVIDER_ID to buildJsonObject {
                    put("taskId", taskId)
                    putJsonObject("resolvedInputs") {
                        put("imageCount", built.sentImageCount)
                        put(
                            "referenceVideoUrls",
                            buildJsonArray { built.sentReferenceVideoUrls.forEach { add(JsonPrimitive(it)) } },
                        )
                    }
                },
            ),
            response = result.modalityResponse(modelId = modelId, id = taskId),
        )
    }

    override suspend fun doStatus(operation: JsonElement, headers: Map<String, String>?): VideoStatusResult {
        val taskId = operation.jsonObject["taskId"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("The MiniMax operation carries no taskId")

        // The URL is built from our own base and the handle we minted, so the first hop is trusted. The
        // guard is for what comes AFTER it: a redirect off the API host is validated before it is
        // followed and carries no credential, so a poll can neither reach a metadata service nor hand
        // the key to whichever host the redirect named.
        // The id is a path segment; one carrying `/` or `?` would otherwise rewrite the request.
        val result = http.getBytes(
            url = "$baseUrl/v2/query/video_generation/${taskId.encodeURLParameter()}",
            headers = combineHeaders(this.headers, headers),
            trustedOrigin = baseUrl,
        )
        val task = parseJsonObject(result.value.decodeToString())["task"] as? JsonObject
            ?: throw NoContentGeneratedError("MiniMax answered a status poll with no task object")
        val response = result.modalityResponse(modelId = modelId, id = taskId)

        return when (val status = task["status"]?.jsonPrimitive?.content) {
            "succeeded" -> {
                val url = (task["content"] as? JsonObject)?.get("url")?.jsonPrimitive?.content
                    ?: throw NoContentGeneratedError(
                        "MiniMax video generation completed but no video URL was returned. " +
                            "Task ID: $taskId",
                    )
                VideoStatusResult.Completed(
                    videos = listOf(VideoData.Url(url = url, mediaType = "video/mp4")),
                    providerMetadata = mapOf(
                        MINIMAX_PROVIDER_ID to buildJsonObject {
                            put("taskId", taskId)
                            put("videoUrl", url)
                            task["duration"]?.let { put("duration", it) }
                            task["ratio"]?.let { put("ratio", it) }
                            task["resolution"]?.let { put("resolution", it) }
                            (task["usage"] as? JsonObject)?.let { usage ->
                                putJsonObject("usage") {
                                    usage["total_seconds"]?.let { put("totalSeconds", it) }
                                    usage["input_seconds"]?.let { put("inputSeconds", it) }
                                    usage["output_seconds"]?.let { put("outputSeconds", it) }
                                }
                            }
                        },
                    ),
                    response = response,
                )
            }
            // A refused, cancelled or lapsed job is an ANSWER; only `queued`/`running` mean try again.
            "failed", "cancelled", "expired" -> {
                val error = task["error"] as? JsonObject
                VideoStatusResult.Failed(
                    error = buildString {
                        append("MiniMax video generation $status")
                        error?.get("message")?.jsonPrimitive?.content?.let { append(": $it") }
                        error?.get("code")?.jsonPrimitive?.content?.let { append(" ($it)") }
                        append(". Task ID: $taskId")
                    },
                    response = response,
                )
            }
            else -> VideoStatusResult.Pending(response = response)
        }
    }

    private class BuiltRequest(
        val body: JsonObject,
        val sentImageCount: Int,
        val sentReferenceVideoUrls: List<String>,
    )

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun requestBody(options: VideoCallOptions, warnings: MutableList<Warning>): BuiltRequest {
        val vendor = options.providerOptions?.get(MINIMAX_PROVIDER_ID)
        for (setting in listOf("pollIntervalMs", "pollTimeoutMs")) {
            if (vendor?.get(setting) != null) {
                warnings += Warning.Deprecated(
                    setting = setting,
                    message = "Polling is the caller's: drive doStatus on your own schedule.",
                )
            }
        }
        if (options.fps != null) {
            warnings += Warning.Unsupported("fps", "$modelId does not support a custom frame rate.")
        }
        if (options.seed != null) {
            warnings += Warning.Unsupported("seed", "$modelId does not support a seed.")
        }
        if (options.n > 1) {
            warnings += Warning.Unsupported(
                "n",
                "$modelId generates a single video per call. Only 1 video will be generated.",
            )
        }
        if (options.generateAudio != null) {
            warnings += Warning.Unsupported(
                "generateAudio",
                "The $modelId API does not expose an audio parameter. " +
                    "The generateAudio option was ignored.",
            )
        }

        val menu = MODEL_RESOLUTIONS[modelId]
            ?: ResolutionMenu(MINIMAX_VIDEO_RESOLUTIONS, DEFAULT_RESOLUTION)
        val resolution = resolveResolution(options, vendor, menu, warnings)

        var sentImageCount = 0
        val sentReferenceVideoUrls = mutableListOf<String>()

        val firstFrameImage = options.frameImages
            ?.firstOrNull { it.frameType == VideoFrameType.FirstFrame }?.image
        var firstFrame = firstFrameImage ?: options.image
        var lastFrame = options.frameImages?.firstOrNull { it.frameType == VideoFrameType.LastFrame }?.image

        val firstKind = firstFrame?.nonImageKind()
        if (firstFrame != null && firstKind != null) {
            warnings += Warning.Unsupported(
                if (firstFrameImage != null) "frameImages" else "image",
                if (firstKind == "video") {
                    "$modelId does not accept a video as a frame image. The video was ignored."
                } else {
                    "$modelId only accepts an image as a frame image; " +
                        "the \"${firstFrame.mediaType}\" file was ignored."
                },
            )
            firstFrame = null
        }
        if (lastFrame != null) {
            if (firstFrame == null) {
                warnings += Warning.Unsupported(
                    "frameImages",
                    "$modelId requires a first_frame when a last_frame is provided. " +
                        "The last_frame was ignored.",
                )
                lastFrame = null
            } else {
                val lastKind = lastFrame.nonImageKind()
                if (lastKind != null) {
                    warnings += Warning.Unsupported(
                        "frameImages",
                        if (lastKind == "video") {
                            "$modelId does not accept a video as a frame image. " +
                                "The last_frame video was ignored."
                        } else {
                            "$modelId only accepts an image as a frame image; " +
                                "the \"${lastFrame.mediaType}\" last_frame was ignored."
                        },
                    )
                    lastFrame = null
                }
            }
        }

        val usesFrameImages = firstFrame != null || lastFrame != null
        val referenceFiles = options.references.orEmpty()
        val referenceAudioUrls = (vendor?.get("referenceAudioUrls") as? JsonArray)
            ?.map { it.jsonPrimitive.content }.orEmpty()
        // H3-Max is the one model in the family without the reference-to-video path.
        val supportsReferences = modelId != "MiniMax-H3-Max"
        if (!supportsReferences && referenceFiles.isNotEmpty()) {
            warnings += Warning.Unsupported(
                "references",
                "MiniMax-H3-Max does not support reference-to-video inputs. The references were ignored.",
            )
        }
        if (!supportsReferences && referenceAudioUrls.isNotEmpty()) {
            warnings += Warning.Unsupported(
                "referenceAudioUrls",
                "MiniMax-H3-Max does not support reference audio. The audio was ignored.",
            )
        }
        val usesReferences =
            supportsReferences && (referenceFiles.isNotEmpty() || referenceAudioUrls.isNotEmpty())

        val content = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", options.prompt ?: "")
                },
            )
            if (usesFrameImages) {
                // URLs and data URIs only. MiniMax's own `mm_file://…` handles survive solely through
                // `providerOptions.minimax.referenceAudioUrls`, which is forwarded raw — one passed as
                // a file here would be base64-mangled, matching the reference's documented limitation.
                firstFrame?.let {
                    add(imageEntry(it.toDataUri(), "first_frame"))
                    sentImageCount++
                }
                lastFrame?.let {
                    add(imageEntry(it.toDataUri(), "last_frame"))
                    sentImageCount++
                }
                if (usesReferences) {
                    warnings += Warning.Unsupported(
                        "references",
                        "MiniMax-H3 cannot combine frame images with reference inputs. " +
                            "The references were ignored.",
                    )
                }
            } else if (usesReferences) {
                val images = mutableListOf<VideoFile>()
                val videos = mutableListOf<VideoFile>()
                for (file in referenceFiles) {
                    when {
                        file.mediaType?.startsWith("video/") == true -> videos += file
                        file.mediaType?.startsWith("image/") == true -> images += file
                        file.mediaType == null -> {
                            warnings += Warning.Unsupported(
                                "references",
                                "MiniMax-H3 requires an explicit mediaType to route URL references " +
                                    "as video or image. Pass a mediaType of \"video/mp4\" for video " +
                                    "references. The reference was treated as an image.",
                            )
                            images += file
                        }
                        else -> warnings += Warning.Unsupported(
                            "references",
                            "MiniMax-H3 only accepts image and video references; the " +
                                "\"${file.mediaType}\" reference was ignored. Pass reference audio " +
                                "via providerOptions.minimax.referenceAudioUrls.",
                        )
                    }
                }
                images.take(MAX_REFERENCE_IMAGES).forEach {
                    add(imageEntry(it.toDataUri(), "reference_image"))
                    sentImageCount++
                }
                if (images.size > MAX_REFERENCE_IMAGES) {
                    warnings += Warning.Unsupported(
                        "references",
                        "MiniMax-H3 accepts at most $MAX_REFERENCE_IMAGES reference images. " +
                            "Extra images were ignored.",
                    )
                }
                videos.take(MAX_REFERENCE_VIDEOS).forEach { video ->
                    val url = video.toDataUri()
                    add(
                        buildJsonObject {
                            put("type", "video_url")
                            putJsonObject("video_url") { put("url", url) }
                            put("role", "reference_video")
                        },
                    )
                    // Inline files become data URIs, which are not worth echoing back.
                    if (video is VideoFile.Url) sentReferenceVideoUrls += url
                }
                if (videos.size > MAX_REFERENCE_VIDEOS) {
                    warnings += Warning.Unsupported(
                        "references",
                        "MiniMax-H3 accepts at most $MAX_REFERENCE_VIDEOS reference videos. " +
                            "Extra videos were ignored.",
                    )
                }
                if (referenceAudioUrls.isNotEmpty()) {
                    if (images.isEmpty() && videos.isEmpty()) {
                        warnings += Warning.Unsupported(
                            "referenceAudioUrls",
                            "MiniMax-H3 reference audio must be paired with at least one reference " +
                                "image or video. The audio was ignored.",
                        )
                    } else {
                        referenceAudioUrls.take(MAX_REFERENCE_AUDIOS).forEach { url ->
                            add(
                                buildJsonObject {
                                    put("type", "audio_url")
                                    putJsonObject("audio_url") { put("url", url) }
                                    put("role", "reference_audio")
                                },
                            )
                        }
                        if (referenceAudioUrls.size > MAX_REFERENCE_AUDIOS) {
                            warnings += Warning.Unsupported(
                                "referenceAudioUrls",
                                "MiniMax-H3 accepts at most $MAX_REFERENCE_AUDIOS reference audios. " +
                                    "Extra audios were ignored.",
                            )
                        }
                    }
                }
            }
        }

        val ratio = resolveRatio(options, vendor, usesFrameImages, content.size == 1, warnings)
        val duration = resolveDuration(options, warnings)

        val body = buildJsonObject {
            put("model", modelId)
            put("content", content)
            put("resolution", resolution)
            put("duration", duration)
            ratio?.let { put("ratio", it) }
            vendor?.get("aigcWatermark")?.let { put("aigc_watermark", it) }
        }
        return BuiltRequest(body, sentImageCount, sentReferenceVideoUrls)
    }

    private fun resolveResolution(
        options: VideoCallOptions,
        vendor: JsonObject?,
        menu: ResolutionMenu,
        warnings: MutableList<Warning>,
    ): String {
        val names = menu.supported.joinToString(" or ") { "\"$it\"" }
        var resolution = vendor?.get("resolution")?.jsonPrimitive?.content
        if (resolution != null && resolution !in menu.supported) {
            warnings += Warning.Unsupported(
                "resolution",
                "$modelId supports $names. The provider resolution \"$resolution\" was ignored.",
            )
            resolution = null
        }
        val topLevel = options.resolution
        if (topLevel != null) {
            val mapped = topLevel.uppercase().takeIf { it in MINIMAX_VIDEO_RESOLUTIONS }
                ?: MINIMAX_RESOLUTION_TIERS[topLevel]
            if (resolution != null) {
                if (mapped == null) {
                    warnings += Warning.Unsupported(
                        "resolution",
                        "Unrecognized resolution \"$topLevel\". $modelId supports $names, so " +
                            "providerOptions.minimax.resolution (\"$resolution\") was used instead.",
                    )
                } else if (mapped != resolution) {
                    warnings += Warning.Unsupported(
                        "resolution",
                        "The resolution \"$topLevel\" selects $mapped, but " +
                            "providerOptions.minimax.resolution (\"$resolution\") was used instead.",
                    )
                }
            } else if (mapped != null && mapped in menu.supported) {
                resolution = mapped
            } else {
                warnings += Warning.Unsupported(
                    "resolution",
                    if (mapped == null) {
                        "Unrecognized resolution \"$topLevel\". $modelId supports $names."
                    } else {
                        "$modelId does not support the resolution \"$mapped\". It supports $names."
                    },
                )
            }
        }
        return resolution ?: menu.default
    }

    /** In frame-image mode the ratio follows the supplied image, so an explicit ratio is ignored. */
    private fun resolveRatio(
        options: VideoCallOptions,
        vendor: JsonObject?,
        usesFrameImages: Boolean,
        isTextToVideo: Boolean,
        warnings: MutableList<Warning>,
    ): String? {
        var ratio = vendor?.get("ratio")?.jsonPrimitive?.content
        if (ratio == null && options.aspectRatio != null) {
            if (options.aspectRatio in MINIMAX_VIDEO_RATIOS) {
                ratio = options.aspectRatio
            } else {
                warnings += Warning.Unsupported(
                    "aspectRatio",
                    if (isTextToVideo) {
                        "$modelId does not support the aspect ratio \"${options.aspectRatio}\". " +
                            "Using the default ($DEFAULT_ASPECT_RATIO)."
                    } else {
                        "$modelId does not support the aspect ratio \"${options.aspectRatio}\". " +
                            "Using the provider default (adaptive)."
                    },
                )
                if (isTextToVideo) ratio = DEFAULT_ASPECT_RATIO
            }
        }
        if (ratio == "adaptive" && isTextToVideo) {
            warnings += Warning.Unsupported(
                "aspectRatio",
                "$modelId text-to-video does not support the adaptive aspect ratio. " +
                    "Using the default ($DEFAULT_ASPECT_RATIO).",
            )
            ratio = DEFAULT_ASPECT_RATIO
        }
        if (usesFrameImages && ratio != null) {
            warnings += Warning.Unsupported(
                "aspectRatio",
                "$modelId derives the aspect ratio from the frame image; the requested ratio was ignored.",
            )
            ratio = null
        }
        if (ratio == null && isTextToVideo) ratio = DEFAULT_ASPECT_RATIO
        return ratio
    }

    /** Integer seconds; H3 starts at 4 where H3-Max starts at 5. Rounded before clamping. */
    private fun resolveDuration(options: VideoCallOptions, warnings: MutableList<Warning>): Int {
        val minDuration = if (modelId == "MiniMax-H3") 4 else 5
        val requested = options.durationInSeconds ?: return DEFAULT_DURATION_SECONDS
        var duration = if (requested == requested.toInt().toDouble()) {
            requested.toInt()
        } else {
            val rounded = requested.roundToInt()
            warnings += Warning.Unsupported(
                "duration",
                "$modelId requires a whole number of seconds. " +
                    "The requested duration of $requested was rounded to $rounded.",
            )
            rounded
        }
        if (duration > MAX_DURATION_SECONDS) {
            warnings += Warning.Unsupported(
                "duration",
                "$modelId supports at most $MAX_DURATION_SECONDS seconds. " +
                    "The requested duration of $requested was clamped to $MAX_DURATION_SECONDS.",
            )
            duration = MAX_DURATION_SECONDS
        } else if (duration < minDuration) {
            warnings += Warning.Unsupported(
                "duration",
                "$modelId requires at least $minDuration seconds. " +
                    "The requested duration of $requested was clamped to $minDuration.",
            )
            duration = minDuration
        }
        return duration
    }
}

private fun imageEntry(url: String, role: String): JsonObject = buildJsonObject {
    put("type", "image_url")
    putJsonObject("image_url") { put("url", url) }
    put("role", role)
}

private fun VideoFile.nonImageKind(): String? =
    mediaType?.substringBefore('/')?.takeIf { it != "image" }

/**
 * The caller's receiver, forwarded as the vendor's `callback_url`. An explicit URL wins over one a
 * caller spelled raw in the vendor options: the argument is the receiver the runtime actually
 * registered, and the raw key is whatever an older config still carries.
 */
private fun JsonObject.withCallbackUrl(webhookUrl: String?): JsonObject =
    if (webhookUrl == null) this else JsonObject(this + ("callback_url" to JsonPrimitive(webhookUrl)))
