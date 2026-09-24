package com.sabreware.aide.aisdk.providers.bytedance

import com.sabreware.aide.aisdk.NoContentGeneratedError
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
import com.sabreware.aide.aisdk.providers.media.toDataUri
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

/** Options this model spends itself; anything else a caller passes goes through to the body verbatim. */
private val BYTEDANCE_HANDLED_OPTIONS = setOf(
    "watermark", "generateAudio", "cameraFixed", "returnLastFrame", "serviceTier", "draft",
    "lastFrameImage", "referenceImages", "referenceVideos", "referenceAudio",
    "pollIntervalMs", "pollTimeoutMs",
)

/**
 * ModelArk takes a named tier while the top-level resolution is `WIDTHxHEIGHT`, so the documented
 * frame sizes map onto their tiers. An unlisted size is forwarded as-is — the API may know a tier this
 * table does not, and clamping would silently shrink what the caller asked for.
 */
private val BYTEDANCE_RESOLUTION_TIERS: Map<String, String> = buildMap {
    listOf(
        "864x496", "496x864", "752x560", "560x752", "640x640", "992x432", "432x992",
        "864x480", "480x864", "736x544", "544x736", "960x416", "416x960", "832x480",
        "480x832", "624x624",
    ).forEach { put(it, "480p") }
    listOf(
        "1280x720", "720x1280", "1112x834", "834x1112", "960x960", "1470x630", "630x1470",
        "1248x704", "704x1248", "1120x832", "832x1120", "1504x640", "640x1504",
    ).forEach { put(it, "720p") }
    listOf(
        "1920x1080", "1080x1920", "1664x1248", "1248x1664", "1440x1440", "2206x946",
        "946x2206", "1920x1088", "1088x1920", "2176x928", "928x2176",
    ).forEach { put(it, "1080p") }
}

/**
 * ByteDance Seedance video generation, over ModelArk's task API.
 *
 * The request is a CONTENT ARRAY, not named fields: the prompt, the frame images and every reference
 * ride as typed entries whose `role` says what each one is — and the role rules are subtle enough to
 * get wrong silently. A start image carries `role: first_frame` ONLY when a last frame accompanies it
 * (alone, the role is omitted and the API infers image-to-video); a reference video whose URL carries
 * no media type is routed as an IMAGE, because the wire cannot express "you decide".
 */
internal class BytedanceVideoModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : VideoModel {

    override val provider: String = BYTEDANCE_PROVIDER_ID

    override suspend fun maxVideosPerCall(): Int = 1

    override suspend fun doStart(options: VideoCallOptions, webhookUrl: String?): VideoStartResult {
        val warnings = mutableListOf<Warning>()
        val body = requestBody(options, warnings).withCallbackUrl(webhookUrl)

        val result = http.postJson(
            url = "$baseUrl/contents/generations/tasks",
            body = body,
            headers = combineHeaders(headers, options.headers),
        )
        val taskId = result.value.jsonObject["id"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("ByteDance returned no task id")

        return VideoStartResult(
            operation = buildJsonObject { put("taskId", taskId) },
            warnings = warnings,
            response = result.modalityResponse(modelId = modelId, id = taskId),
        )
    }

    override suspend fun doStatus(operation: JsonElement, headers: Map<String, String>?): VideoStatusResult {
        val taskId = operation.jsonObject["taskId"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("The ByteDance operation carries no taskId")

        // The URL is built from our own base and the handle we minted, so the first hop is trusted. The
        // guard is for what comes AFTER it: a redirect off the API host is validated before it is
        // followed and carries no credential, so a poll can neither reach a metadata service nor hand
        // the key to whichever host the redirect named.
        val result = http.getBytes(
            url = "$baseUrl/contents/generations/tasks/$taskId",
            headers = combineHeaders(this.headers, headers),
            trustedOrigin = baseUrl,
        )
        val task = parseJsonObject(result.value.decodeToString())
        val response = result.modalityResponse(modelId = modelId, id = taskId)

        return when (task["status"]?.jsonPrimitive?.content) {
            "succeeded" -> {
                val content = task["content"] as? JsonObject
                val videoUrl = content?.get("video_url")?.jsonPrimitive?.content
                    ?: throw NoContentGeneratedError(
                        "ByteDance reported success with no video URL. Task ID: $taskId",
                    )
                VideoStatusResult.Completed(
                    videos = listOf(VideoData.Url(url = videoUrl, mediaType = "video/mp4")),
                    providerMetadata = mapOf(
                        BYTEDANCE_PROVIDER_ID to buildJsonObject {
                            put("taskId", taskId)
                            task["usage"]?.let { put("usage", it) }
                            content?.get("last_frame_url")?.let { put("lastFrameUrl", it) }
                        },
                    ),
                    response = response,
                )
            }
            // ModelArk documents `cancelled`; `canceled` is handled defensively. `expired` is a task the
            // service gave up on, and polling it further waits on nothing. The HTTP response is still a
            // 200, so the reason lives in the body's `error` object — or, absent one, the raw body,
            // because a failure reported with no diagnostic at all cannot be acted on.
            "failed", "expired", "cancelled", "canceled" -> {
                val error = task["error"] as? JsonObject
                val detail = error?.get("message")?.jsonPrimitive?.content
                    ?: error?.get("code")?.jsonPrimitive?.content
                    ?: task.toString()
                VideoStatusResult.Failed(
                    error = "Video generation ${task.getValue("status").jsonPrimitive.content}. " +
                        "Task ID: $taskId. $detail",
                    response = response,
                )
            }
            else -> VideoStatusResult.Pending(response = response)
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun requestBody(options: VideoCallOptions, warnings: MutableList<Warning>): JsonObject {
        val vendor = options.providerOptions?.get(BYTEDANCE_PROVIDER_ID)

        // Kling precedent: polling belongs to the caller now, but these two once steered an inner
        // poll loop, so a caller still setting them is told rather than left waiting for an effect.
        for (setting in listOf("pollIntervalMs", "pollTimeoutMs")) {
            if (vendor?.get(setting) != null) {
                warnings += Warning.Deprecated(
                    setting = setting,
                    message = "Polling is the caller's: drive doStatus on your own schedule.",
                )
            }
        }
        if (options.fps != null) {
            warnings += Warning.Unsupported(
                "fps",
                "ByteDance video models do not support custom FPS. Frame rate is fixed at 24 fps.",
            )
        }
        if (options.n > 1) {
            warnings += Warning.Unsupported(
                "n",
                "ByteDance video models do not support generating multiple videos per call. " +
                    "Only 1 video will be generated.",
            )
        }

        val startImage = options.frameImages
            ?.firstOrNull { it.frameType == VideoFrameType.FirstFrame }?.image
            ?: options.image
        val lastFrameUrl = options.frameImages
            ?.firstOrNull { it.frameType == VideoFrameType.LastFrame }?.image?.toDataUri()
            ?: vendor?.get("lastFrameImage")?.jsonPrimitive?.content

        val content = buildJsonArray {
            options.prompt?.let {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", it)
                    },
                )
            }
            startImage?.let { image ->
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        // ModelArk takes URLs or data URIs — never bare base64 (the reverse of
                        // Kling and BFL), which is why inline bytes go out wrapped.
                        putJsonObject("image_url") { put("url", image.toDataUri()) }
                        // The role is stated only when a last frame makes it ambiguous; alone, the API
                        // infers image-to-video, and an unnecessary role narrows what some models accept.
                        if (lastFrameUrl != null) put("role", "first_frame")
                    },
                )
            }
            lastFrameUrl?.let { url ->
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", url) }
                        put("role", "last_frame")
                    },
                )
            }
            referenceEntries(options, vendor, warnings).forEach { add(it) }
            (vendor?.get("referenceAudio") as? JsonArray)?.forEach { audioUrl ->
                add(
                    buildJsonObject {
                        put("type", "audio_url")
                        putJsonObject("audio_url") { put("url", audioUrl.jsonPrimitive.content) }
                        put("role", "reference_audio")
                    },
                )
            }
        }

        return buildJsonObject {
            put("model", modelId)
            put("content", content)
            options.aspectRatio?.let { put("ratio", it) }
            options.durationInSeconds?.let { put("duration", it.asJsonSeconds()) }
            options.seed?.let { put("seed", it) }
            options.resolution?.let { put("resolution", BYTEDANCE_RESOLUTION_TIERS[it] ?: it) }
            (options.generateAudio ?: vendor.boolean("generateAudio"))?.let {
                put("generate_audio", it)
            }
            vendor?.get("watermark")?.let { put("watermark", it) }
            vendor?.get("cameraFixed")?.let { put("camera_fixed", it) }
            vendor?.get("returnLastFrame")?.let { put("return_last_frame", it) }
            vendor?.get("serviceTier")?.let { put("service_tier", it) }
            vendor?.get("draft")?.let { put("draft", it) }
            vendor?.forEach { (key, value) -> if (key !in BYTEDANCE_HANDLED_OPTIONS) put(key, value) }
        }
    }

    /**
     * References ride in the same content array, routed by media type — and frame images shut them
     * out entirely, matching the reference: a request cannot be image-to-video and reference-to-video
     * at once.
     */
    private fun referenceEntries(
        options: VideoCallOptions,
        vendor: JsonObject?,
        warnings: MutableList<Warning>,
    ): List<JsonObject> {
        if (!options.frameImages.isNullOrEmpty()) return emptyList()

        val references = options.references.orEmpty()
        if (references.isNotEmpty()) {
            return references.map { reference ->
                if (reference is VideoFile.Url && reference.mediaType == null) {
                    warnings += Warning.Unsupported(
                        "references",
                        "ByteDance requires an explicit mediaType to route URL references as video " +
                            "or image. Pass a mediaType of \"video/mp4\" for video references. " +
                            "The reference was treated as an image.",
                    )
                }
                val url = reference.toDataUri()
                if (reference.mediaType?.startsWith("video/") == true) {
                    buildJsonObject {
                        put("type", "video_url")
                        putJsonObject("video_url") { put("url", url) }
                        put("role", "reference_video")
                    }
                } else {
                    buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", url) }
                        put("role", "reference_image")
                    }
                }
            }
        }

        return buildList {
            (vendor?.get("referenceImages") as? JsonArray)?.forEach { url ->
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", url.jsonPrimitive.content) }
                        put("role", "reference_image")
                    },
                )
            }
            (vendor?.get("referenceVideos") as? JsonArray)?.forEach { url ->
                add(
                    buildJsonObject {
                        put("type", "video_url")
                        putJsonObject("video_url") { put("url", url.jsonPrimitive.content) }
                        put("role", "reference_video")
                    },
                )
            }
        }
    }
}

private fun JsonObject?.boolean(key: String): Boolean? =
    (this?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()

/** A whole 5.0 goes out as `5`, matching how the reference's JS number serializes; a fraction stays. */
private fun Double.asJsonSeconds(): kotlinx.serialization.json.JsonPrimitive =
    if (this == toInt().toDouble()) {
        kotlinx.serialization.json.JsonPrimitive(toInt())
    } else {
        kotlinx.serialization.json.JsonPrimitive(this)
    }

/**
 * The caller's receiver, forwarded as the vendor's `callback_url`. An explicit URL wins over one a
 * caller spelled raw in the vendor options: the argument is the receiver the runtime actually
 * registered, and the raw key is whatever an older config still carries.
 */
private fun JsonObject.withCallbackUrl(webhookUrl: String?): JsonObject =
    if (webhookUrl == null) this else JsonObject(this + ("callback_url" to JsonPrimitive(webhookUrl)))
