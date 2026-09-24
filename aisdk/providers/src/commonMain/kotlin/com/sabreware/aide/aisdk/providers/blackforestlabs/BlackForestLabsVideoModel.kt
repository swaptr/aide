package com.sabreware.aide.aisdk.providers.blackforestlabs

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoFile
import com.sabreware.aide.aisdk.VideoFrameType
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.VideoStartResult
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.DownloadUrl
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** FLUX 3 video's whole-second window. */
private const val MIN_DURATION_SECONDS = 5
private const val MAX_DURATION_SECONDS = 20

/** Keyframe counts at or above this need an explicit duration when none carries a timestamp. */
private const val UNTIMED_KEYFRAMES_NEEDING_DURATION = 3

/** The ratios FLUX 3 accepts; `auto` (the API default) is only reachable via the provider option. */
private val BFL_VIDEO_ASPECT_RATIOS =
    setOf("21:9", "2:1", "16:9", "4:3", "1:1", "3:4", "9:16", "auto")

private val BFL_VIDEO_RESOLUTIONS = setOf("hd", "fhd")

/** Statuses that end the job without a video. `Task not found` is terminal too: it will never finish. */
private val TERMINAL_FAILURE_STATUSES =
    setOf("Error", "Failed", "Request Moderated", "Content Moderated", "Task not found")

/**
 * Black Forest Labs (FLUX 3) video generation.
 *
 * The same three transport traps as the image model — `x-key` auth, a poll URL on a DIFFERENT host
 * than submit, and routing by `?id=` query — plus one of its own: the request's MODE is derived from
 * what the call carries. Keyframes make it `i2v`, a video reference makes it `v2v`, neither is `t2v`,
 * and a body whose mode disagrees with its media is a 422 naming a field the caller never set.
 *
 * The operation handle carries the polling URL and request id verbatim, because the poll URL cannot be
 * rebuilt from our base (wrong host, see above) — losing it loses the job. The submit's billing figures
 * ride in the handle too: they only exist on the submit response, and the reference surfaces them in
 * the COMPLETED metadata, which a resumed poll could not do if they lived nowhere but a local variable.
 */
internal class BlackForestLabsVideoModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val apiKey: String,
) : VideoModel {

    override val provider: String = BFL_PROVIDER_ID

    override suspend fun maxVideosPerCall(): Int = 1

    private val authHeaders get() = mapOf("x-key" to apiKey)

    override suspend fun doStart(options: VideoCallOptions, webhookUrl: String?): VideoStartResult {
        val warnings = mutableListOf<Warning>()
        val bflOptions = options.providerOptions?.get(BFL_PROVIDER_ID)
        val body = if (bflOptions?.get("draftCache") != null) {
            draftEnhanceBody(options, bflOptions, warnings)
        } else {
            generationBody(options, bflOptions, warnings)
        }
        if (webhookUrl != null) {
            // The submit schema is closed; an undocumented webhook field would be a 422, and BFL routes
            // webhooks per-account rather than per-request on this surface.
            warnings += Warning.Unsupported(
                "webhookUrl",
                "FLUX 3 video takes no per-request webhook; the job must be polled.",
            )
        }

        val result = http.postJson(
            url = "$baseUrl/$modelId",
            body = body,
            headers = combineHeaders(authHeaders, options.headers),
        )
        val submit = result.value.jsonObject
        val pollingUrl = submit["polling_url"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("Black Forest Labs returned no polling_url")
        val requestId = submit["id"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("Black Forest Labs returned no request id")

        return VideoStartResult(
            operation = buildJsonObject {
                put("requestId", requestId)
                put("pollingUrl", pollingUrl)
                submit["cost"].notNull()?.let { put("cost", it) }
                submit["input_mp"].notNull()?.let { put("inputMegapixels", it) }
                submit["output_mp"].notNull()?.let { put("outputMegapixels", it) }
            },
            warnings = warnings,
            response = result.modalityResponse(modelId = modelId, id = requestId),
        )
    }

    override suspend fun doStatus(operation: JsonElement, headers: Map<String, String>?): VideoStatusResult {
        val handle = operation.jsonObject
        val requestId = handle["requestId"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("The Black Forest Labs operation carries no requestId")
        val pollingUrl = handle["pollingUrl"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("The Black Forest Labs operation carries no pollingUrl")

        // BFL routes a poll by query parameter; without `?id=` the endpoint answers about no job at
        // all. Appended only when absent — the vendor sometimes includes it, and a second `id` is a
        // rejected request.
        val pollUrl = when {
            "id=" in pollingUrl.substringAfter('?', "") -> pollingUrl
            '?' in pollingUrl -> "$pollingUrl&id=$requestId"
            else -> "$pollingUrl?id=$requestId"
        }
        DownloadUrl.validate(pollUrl)
        // The poll URL names a cluster host BFL picked, not our base — the key goes along only when the
        // host is provably theirs. See isTrustedBflUrl for why strict same-origin is wrong here.
        val requestHeaders = combineHeaders(authHeaders, headers)
        val result = http.getJson(
            url = pollUrl,
            headers = if (isTrustedBflUrl(pollUrl, baseUrl)) requestHeaders else emptyMap(),
        )
        val task = result.value.jsonObject
        val response = result.modalityResponse(modelId = modelId, id = requestId)

        // `state` is the same field under a second name on some responses.
        val status = (task["status"] ?: task["state"])?.jsonPrimitive?.content
        return when {
            status == "Ready" -> completed(task, handle, requestId, response)
            status in TERMINAL_FAILURE_STATUSES -> VideoStatusResult.Failed(
                error = "Black Forest Labs video generation failed with status \"$status\"" +
                    (task["details"]?.failureText()?.let { ": $it" } ?: "") +
                    ". Request id: $requestId",
                response = response,
            )
            else -> VideoStatusResult.Pending(response = response)
        }
    }

    private fun completed(
        task: JsonObject,
        handle: JsonObject,
        requestId: String,
        response: com.sabreware.aide.aisdk.ModalityResponse,
    ): VideoStatusResult {
        val ready = (task["result"] as? JsonObject)
            ?.takeIf { it["sample"]?.jsonPrimitive?.content != null }
            ?: throw NoContentGeneratedError(
                "Black Forest Labs reported the video as Ready with no result.sample URL. " +
                    "Request id: $requestId",
            )
        val sample = ready.getValue("sample").jsonPrimitive.content
        // The settled cost, which the API can only know once generation finishes, wins over the
        // submit-time estimate carried in the handle.
        val cost = task["cost"].notNull() ?: handle["cost"]

        return VideoStatusResult.Completed(
            videos = listOf(VideoData.Url(url = sample, mediaType = "video/mp4")),
            providerMetadata = mapOf(
                BFL_PROVIDER_ID to buildJsonObject {
                    put(
                        "videos",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", requestId)
                                    put("videoUrl", sample)
                                    ready["seed"].notNull()?.let { put("seed", it) }
                                    ready["start_time"].notNull()?.let { put("start_time", it) }
                                    ready["end_time"].notNull()?.let { put("end_time", it) }
                                    ready["duration"].notNull()?.let { put("duration", it) }
                                    ready["draft_cache"].notNull()?.let { put("draftCache", it) }
                                    cost?.let { put("cost", it) }
                                    handle["inputMegapixels"]?.let { put("inputMegapixels", it) }
                                    handle["outputMegapixels"]?.let { put("outputMegapixels", it) }
                                },
                            )
                        },
                    )
                },
            ),
            response = response,
        )
    }

    /**
     * Draft-enhance replays an encrypted bundle from a prior `draft` run at full quality. The bundle
     * pins the original mode, prompt, seed and conditioning media, and the API accepts nothing beside
     * it but `safety_tolerance` — so everything else the caller set is reported as dropped rather than
     * silently shadowed by the bundle's values.
     */
    private fun draftEnhanceBody(
        options: VideoCallOptions,
        bflOptions: JsonObject,
        warnings: MutableList<Warning>,
    ): JsonObject {
        val pinned = listOf(
            "prompt" to !options.prompt.isNullOrBlank(),
            "aspectRatio" to (options.aspectRatio != null || bflOptions["aspectRatio"] != null),
            "resolution" to (options.resolution != null || bflOptions["resolution"] != null),
            "duration" to (options.durationInSeconds != null),
            "fps" to (options.fps != null),
            "seed" to (options.seed != null),
            "generateAudio" to (options.generateAudio != null),
            "image" to (options.image != null),
            "frameImages" to !options.frameImages.isNullOrEmpty(),
            "references" to !options.references.isNullOrEmpty(),
            "keyframes" to (bflOptions["keyframes"] != null),
            "version" to (bflOptions["version"] != null),
        )
        for ((feature, isSet) in pinned) {
            if (isSet) {
                warnings += Warning.Unsupported(
                    feature,
                    "FLUX 3 draft enhance replays the draft bundle as it was generated, so " +
                        "\"$feature\" was ignored. Set it on the original draft request instead.",
                )
            }
        }
        if (bflOptions["draft"] != null) {
            warnings += Warning.Unsupported(
                "draft",
                "FLUX 3 draft enhance always renders at full quality. The draft option was ignored.",
            )
        }
        warnIfMultiple(options, warnings)
        return buildJsonObject {
            put("mode", "draft_enhance")
            put("draft_cache", bflOptions.getValue("draftCache"))
            bflOptions["safetyTolerance"]?.let { put("safety_tolerance", it) }
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun generationBody(
        options: VideoCallOptions,
        bflOptions: JsonObject?,
        warnings: MutableList<Warning>,
    ): JsonObject {
        if (options.fps != null) {
            warnings += Warning.Unsupported("fps", "FLUX 3 video does not support a custom frame rate.")
        }
        if (options.seed != null) {
            warnings += Warning.Unsupported("seed", "FLUX 3 video does not accept a seed.")
        }
        warnIfMultiple(options, warnings)

        val resolution = resolveResolution(options, bflOptions, warnings)
        val aspectRatio = resolveAspectRatio(options, bflOptions, warnings)
        val keyframes = resolveKeyframes(options, bflOptions, warnings)
        val startVideo = if (keyframes == null) resolveStartVideo(options, warnings) else {
            if (options.references.orEmpty().any { it.isVideo() || it.mediaType == null }) {
                warnings += Warning.Unsupported(
                    "references",
                    "FLUX 3 video cannot combine keyframes with a video to continue from. " +
                        "The video reference was ignored.",
                )
            }
            null
        }

        val duration = resolveDuration(options, warnings)
        val untimed = keyframes?.count { it is JsonPrimitive } ?: 0
        if (duration == null && untimed >= UNTIMED_KEYFRAMES_NEEDING_DURATION) {
            throw InvalidArgumentError(
                message = "FLUX 3 video requires an explicit duration when " +
                    "$UNTIMED_KEYFRAMES_NEEDING_DURATION or more keyframes are sent without a timestamp.",
                argument = "duration",
            )
        }

        val mode = when {
            keyframes != null -> "i2v"
            startVideo != null -> "v2v"
            else -> "t2v"
        }
        return buildJsonObject {
            put("mode", mode)
            put("prompt", options.prompt ?: "")
            aspectRatio?.let { put("aspect_ratio", it) }
            duration?.let { put("duration", it) }
            resolution?.let { put("resolution", it) }
            bflOptions?.get("version")?.let { put("version", it) }
            options.generateAudio?.let { put("generate_audio", it) }
            bflOptions?.get("safetyTolerance")?.let { put("safety_tolerance", it) }
            bflOptions?.get("draft")?.let { put("draft", it) }
            if (keyframes != null) put("keyframes", JsonArray(keyframes))
            if (startVideo != null) put("start_video", startVideo)
        }
    }

    /**
     * The API takes a named tier (`hd`/`fhd`) while the top-level resolution is `WIDTHxHEIGHT`, so a
     * caller may reasonably pass either. An explicit provider option is already a tier and wins.
     */
    private fun resolveResolution(
        options: VideoCallOptions,
        bflOptions: JsonObject?,
        warnings: MutableList<Warning>,
    ): JsonElement? {
        val fromVendor = bflOptions?.get("resolution")
        val topLevel = options.resolution ?: return fromVendor
        val named = topLevel.lowercase().takeIf { it in BFL_VIDEO_RESOLUTIONS }
        val dimensions = Regex("^(\\d+)x(\\d+)$").matchEntire(topLevel)
        if (fromVendor != null) {
            if (named == null && dimensions == null) {
                warnings += Warning.Unsupported(
                    "resolution",
                    "Unrecognized resolution \"$topLevel\". FLUX 3 video supports \"hd\" and \"fhd\", " +
                        "so providerOptions.blackForestLabs.resolution " +
                        "(${fromVendor.jsonPrimitive.content.let { "\"$it\"" }}) was used instead.",
                )
            }
            return fromVendor
        }
        if (named != null) return JsonPrimitive(named)
        if (dimensions == null) {
            warnings += Warning.Unsupported(
                "resolution",
                "Unrecognized resolution \"$topLevel\". FLUX 3 video supports \"hd\" and \"fhd\", " +
                    "or a {width}x{height} value to map onto one.",
            )
            return null
        }
        val shorter = min(
            dimensions.groupValues[1].toInt(),
            dimensions.groupValues[2].toInt(),
        )
        val tier = if (shorter <= 720) "hd" else "fhd"
        if (shorter != 720 && shorter != 1080) {
            warnings += Warning.Compatibility(
                "resolution",
                "FLUX 3 video renders at \"hd\" or \"fhd\"; the requested resolution " +
                    "\"$topLevel\" was mapped to \"$tier\".",
            )
        }
        return JsonPrimitive(tier)
    }

    /** The provider option can also express `auto`, so it wins over the top-level ratio. */
    private fun resolveAspectRatio(
        options: VideoCallOptions,
        bflOptions: JsonObject?,
        warnings: MutableList<Warning>,
    ): JsonElement? {
        bflOptions?.get("aspectRatio")?.let { return it }
        val requested = options.aspectRatio ?: return null
        if (requested in BFL_VIDEO_ASPECT_RATIOS) return JsonPrimitive(requested)
        warnings += Warning.Unsupported(
            "aspectRatio",
            "FLUX 3 video does not support the aspect ratio \"$requested\". " +
                "Using the provider default (auto).",
        )
        return null
    }

    /**
     * The vendor keyframe option covers the shapes the top-level fields cannot express — three or more
     * images, or images pinned to a second — so it wins over `image`/`frameImages` outright.
     */
    private fun resolveKeyframes(
        options: VideoCallOptions,
        bflOptions: JsonObject?,
        warnings: MutableList<Warning>,
    ): List<JsonElement>? {
        (bflOptions?.get("keyframes") as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { vendor ->
            validateKeyframes(vendor)
            if (options.image != null || !options.frameImages.isNullOrEmpty()) {
                warnings += Warning.Unsupported(
                    if (options.frameImages != null) "frameImages" else "image",
                    "FLUX 3 video takes a single keyframe list. " +
                        "providerOptions.blackForestLabs.keyframes was used and the top-level frame " +
                        "images were ignored.",
                )
            }
            return vendor.toList()
        }

        val firstFrameImage = options.frameImages
            ?.firstOrNull { it.frameType == VideoFrameType.FirstFrame }?.image
        var first = firstFrameImage ?: options.image
        var last = options.frameImages?.firstOrNull { it.frameType == VideoFrameType.LastFrame }?.image

        if (first != null && first.nonImageKind() != null) {
            warnings += Warning.Unsupported(
                if (firstFrameImage != null) "frameImages" else "image",
                if (first.isVideo()) {
                    "FLUX 3 video does not accept a video as a keyframe. Pass it as a reference to " +
                        "continue from it instead."
                } else {
                    "FLUX 3 video only accepts an image as a keyframe; the \"${first.mediaType}\" " +
                        "file was ignored."
                },
            )
            first = null
        }
        if (last != null) {
            if (first == null) {
                // Keyframes are positional: the first entry opens the clip, so a closing frame cannot
                // travel alone.
                warnings += Warning.Unsupported(
                    "frameImages",
                    "FLUX 3 video requires a first_frame when a last_frame is provided. " +
                        "The last_frame was ignored.",
                )
                last = null
            } else if (last.nonImageKind() != null) {
                warnings += Warning.Unsupported(
                    "frameImages",
                    if (last.isVideo()) {
                        "FLUX 3 video does not accept a video as a keyframe. The last_frame video " +
                            "was ignored."
                    } else {
                        "FLUX 3 video only accepts an image as a keyframe; the " +
                            "\"${last.mediaType}\" last_frame was ignored."
                    },
                )
                last = null
            }
        }

        if (first == null) return null
        return buildList {
            add(JsonPrimitive(first.toBflVideoFile()))
            last?.let { add(JsonPrimitive(it.toBflVideoFile())) }
        }
    }

    /** FLUX 3 continues from a single video; image references have no slot at all. */
    private fun resolveStartVideo(
        options: VideoCallOptions,
        warnings: MutableList<Warning>,
    ): String? {
        val videos = mutableListOf<VideoFile>()
        for (file in options.references.orEmpty()) {
            when {
                file.isVideo() -> videos += file
                file.mediaType == null -> {
                    warnings += Warning.Compatibility(
                        "references",
                        "FLUX 3 video only accepts a video reference, so the reference with no " +
                            "mediaType was treated as the video to continue from. Pass a mediaType " +
                            "of \"video/mp4\" to be explicit.",
                    )
                    videos += file
                }
                file.mediaType?.startsWith("image/") == true -> warnings += Warning.Unsupported(
                    "references",
                    "FLUX 3 video has no reference-image input. Pass images as `image`, " +
                        "`frameImages`, or providerOptions.blackForestLabs.keyframes instead. " +
                        "The reference was ignored.",
                )
                else -> warnings += Warning.Unsupported(
                    "references",
                    "FLUX 3 video only accepts a video reference; the \"${file.mediaType}\" " +
                        "reference was ignored.",
                )
            }
        }
        if (videos.isEmpty()) return null
        if (videos.size > 1) {
            warnings += Warning.Unsupported(
                "references",
                "FLUX 3 video continues from a single video. Only the first video reference was used.",
            )
        }
        return videos.first().toBflVideoFile()
    }

    /** Whole seconds between 5 and 20, or absent to let the API pick one that fits the content. */
    private fun resolveDuration(
        options: VideoCallOptions,
        warnings: MutableList<Warning>,
    ): Int? {
        val requested = options.durationInSeconds ?: return null
        var duration = if (requested == requested.toInt().toDouble()) {
            requested.toInt()
        } else {
            val rounded = requested.roundToInt()
            warnings += Warning.Unsupported(
                "duration",
                "FLUX 3 video requires a whole number of seconds. " +
                    "The requested duration of $requested was rounded to $rounded.",
            )
            rounded
        }
        if (duration > MAX_DURATION_SECONDS) {
            warnings += Warning.Unsupported(
                "duration",
                "FLUX 3 video supports at most $MAX_DURATION_SECONDS seconds. " +
                    "The requested duration of $requested was clamped to $MAX_DURATION_SECONDS.",
            )
            duration = MAX_DURATION_SECONDS
        } else if (duration < MIN_DURATION_SECONDS) {
            warnings += Warning.Unsupported(
                "duration",
                "FLUX 3 video requires at least $MIN_DURATION_SECONDS seconds. " +
                    "The requested duration of $requested was clamped to $MIN_DURATION_SECONDS.",
            )
            duration = MIN_DURATION_SECONDS
        }
        return duration
    }

    private fun warnIfMultiple(options: VideoCallOptions, warnings: MutableList<Warning>) {
        if (options.n > 1) {
            warnings += Warning.Unsupported(
                "n",
                "FLUX 3 video generates a single video per call. Only 1 video will be generated.",
            )
        }
    }
}

/**
 * Keyframes are either bare files or `[second, file]` pairs, at most ten, and the timed form must be
 * chronological — the API rejects out-of-order timestamps with an error naming neither entry.
 */
private fun validateKeyframes(keyframes: JsonArray) {
    if (keyframes.size > MAX_KEYFRAMES) {
        throw InvalidArgumentError(
            message = "FLUX 3 video accepts at most $MAX_KEYFRAMES keyframes.",
            argument = "keyframes",
        )
    }
    val times = keyframes.mapNotNull { entry ->
        (entry as? JsonArray)?.firstOrNull()?.jsonPrimitive?.doubleOrNull
    }
    if (times.zipWithNext().any { (before, after) -> after <= before }) {
        throw InvalidArgumentError(
            message = "Timed keyframes must be in chronological order.",
            argument = "keyframes",
        )
    }
}

private const val MAX_KEYFRAMES = 10

private fun VideoFile.isVideo(): Boolean = mediaType?.startsWith("video/") == true

/** The top-level media kind when it is NOT an image — the shapes a keyframe slot cannot take. */
private fun VideoFile.nonImageKind(): String? =
    mediaType?.substringBefore('/')?.takeIf { it != "image" }

/** A file as BFL wants it: a URL, or BARE base64 with no `data:` prefix — the prefix is a 422. */
@OptIn(ExperimentalEncodingApi::class)
private fun VideoFile.toBflVideoFile(): String = when (this) {
    is VideoFile.Url -> url
    is VideoFile.Data -> when (val payload = data) {
        is BinaryData.Base64 -> payload.value
        is BinaryData.Bytes -> Base64.encode(payload.value)
    }
}

/** JSON null is a value the vendor sent, not a value it has; this is the difference. */
private fun JsonElement?.notNull(): JsonElement? = this?.takeIf { it !is JsonNull }

/**
 * A failed poll's `details` is a plain sentence on moderation refusals and a structure on everything
 * else. The structure is rendered rather than dropped — an ugly JSON fragment still names the field
 * that was rejected, where an empty message names nothing.
 */
private fun JsonElement.failureText(): String? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> if (isString) content.takeIf { it.isNotBlank() } else toString()
    else -> toString().takeIf { it != "{}" && it != "[]" }
}
