package com.sabreware.aide.aisdk.providers.xai

import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoFile
import com.sabreware.aide.aisdk.VideoFrameType
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.VideoStartResult
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.media.toDataUri
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The three shapes of an xAI video job, selected by `providerOptions.xai.mode`. */
private const val MODE_EDIT = "edit-video"
private const val MODE_EXTEND = "extend-video"
private const val MODE_REFERENCE = "reference-to-video"

/** xAI names its resolutions; the specification carries pixel dimensions. */
private val RESOLUTIONS = mapOf(
    "1920x1080" to "1080p",
    "1280x720" to "720p",
    "854x480" to "480p",
    "640x480" to "480p",
)

/**
 * xAI video generation, on the start/status contract.
 *
 * **Three endpoints, one model.** A plain generation posts to `/videos/generations`, an edit to
 * `/videos/edits` and a continuation to `/videos/extensions`, chosen by `providerOptions.xai.mode`.
 * They are not interchangeable: posting an edit body to the generations endpoint is accepted and
 * answered with a video generated from the prompt alone, so the source clip is silently ignored rather
 * than rejected.
 *
 * The job handle is `{"requestId": …}` — persistable, so a caller can close the process that submitted
 * the job and resume polling later, which is the property [VideoModel]'s split contract exists for.
 *
 * Each mode refuses a different subset of the standard knobs, and xAI refuses them by IGNORING them.
 * An edit sent with a duration renders at the source clip's length with nothing to say why, so every
 * one of those combinations warns here instead of reaching the wire.
 */
internal class XaiVideoModel(
    override val modelId: String,
    http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : VideoModel {

    override val provider: String = XAI_PROVIDER_ID

    private val http = http.withErrorStructure(XaiErrors)

    override suspend fun doStart(options: VideoCallOptions, webhookUrl: String?): VideoStartResult {
        val warnings = mutableListOf<Warning>()
        val vendor = options.providerOptions?.forProvider(XAI_PROVIDER_ID)
        val mode = vendor?.optString("mode")
        val videoUrl = vendor?.optString("videoUrl")

        // The reference infers the legacy modes from the options alone: a `videoUrl` with no mode is an
        // edit, `referenceImageUrls` with no mode is reference-to-video. Kept, because a caller written
        // against the older shape otherwise silently gets a plain generation.
        val isEdit = mode == MODE_EDIT || (mode == null && videoUrl != null)
        val isExtension = mode == MODE_EXTEND

        if (webhookUrl != null) {
            warnings += Warning.Unsupported(
                feature = "webhook",
                details = "xAI video generation has no webhook delivery; poll doStatus instead.",
            )
        }
        if ((isEdit || isExtension) && videoUrl == null) {
            throw NoContentGeneratedError(
                "providerOptions.xai.videoUrl is required for mode '${mode ?: MODE_EDIT}'.",
            )
        }

        val body = buildBody(options, vendor, isEdit, isExtension, videoUrl, warnings)
        val endpoint = when {
            isEdit -> "$baseUrl/videos/edits"
            isExtension -> "$baseUrl/videos/extensions"
            else -> "$baseUrl/videos/generations"
        }

        val result = http.postJson(endpoint, body, combineHeaders(headers, options.headers))
        val requestId = (result.value as? JsonObject)?.optString("request_id")
            ?: throw NoContentGeneratedError("xAI returned no request_id for the video job.")

        return VideoStartResult(
            operation = buildJsonObject { put("requestId", requestId) },
            warnings = warnings,
            response = result.modalityResponse(modelId = modelId),
            request = result.requestInfo(),
        )
    }

    override suspend fun doStatus(
        operation: JsonElement,
        headers: Map<String, String>?,
    ): VideoStatusResult {
        val requestId = (operation as? JsonObject)?.optString("requestId")
            ?: throw NoContentGeneratedError("The xAI operation carries no requestId.")

        // Percent-encoded as a PATH segment: a request id is vendor data, and one that happened to
        // contain a slash or a dot segment would otherwise rewrite the URL it is being placed into.
        val result = http.getJson(
            url = "$baseUrl/videos/${requestId.encodeURLPathPart()}",
            headers = combineHeaders(this.headers, headers),
        )
        val body = result.value.jsonObject
        val response = result.modalityResponse(modelId = modelId)
        val status = body.optString("status")
        val video = body["video"] as? JsonObject

        fun failed(error: String) = VideoStatusResult.Failed(error, response = response)

        return when {
            status == "expired" -> failed("Video generation request expired.")

            status == "failed" -> {
                val error = body["error"] as? JsonObject
                val detail = error?.optString("message") ?: error?.optString("code")
                failed(detail?.let { "Video generation failed: $it" } ?: "Video generation failed.")
            }

            // `status` absent WITH a video is xAI's older completed shape; treating a missing status as
            // "still running" would poll a finished job until the caller's budget ran out.
            status == "done" || (status == null && video?.get("url") != null) -> when {
                video?.optBoolean("respect_moderation") == false ->
                    failed("Video generation was blocked due to a content policy violation.")
                video?.optString("url") == null ->
                    failed("Video generation completed but no video URL was returned.")
                else -> VideoStatusResult.Completed(
                    videos = listOf(VideoData.Url(video.optString("url")!!, "video/mp4")),
                    providerMetadata = mapOf(XAI_PROVIDER_ID to completedMetadata(requestId, body, video)),
                    response = response,
                )
            }

            else -> VideoStatusResult.Pending(response = response)
        }
    }

    /**
     * What xAI reports about a finished job beyond the clip itself: cost, duration, progress.
     *
     * The numbers are carried as the vendor sent them rather than read into a Kotlin type and written
     * back. Reading a cost of `42` through a Double turns it into `42.0`, which is a different value to
     * anything comparing or displaying it — and this map exists precisely to hand a caller what xAI
     * said.
     */
    private fun completedMetadata(
        requestId: String,
        body: JsonObject,
        video: JsonObject,
    ): JsonObject = buildJsonObject {
        put("requestId", requestId)
        video.optString("url")?.let { put("videoUrl", it) }
        video["duration"]?.let { put("duration", it) }
        (body["usage"] as? JsonObject)?.get("cost_in_usd_ticks")?.let { put("costInUsdTicks", it) }
        body["progress"]?.let { put("progress", it) }
    }

    @Suppress("CyclomaticComplexMethod", "LongParameterList")
    private fun buildBody(
        options: VideoCallOptions,
        vendor: JsonObject?,
        isEdit: Boolean,
        isExtension: Boolean,
        videoUrl: String?,
        warnings: MutableList<Warning>,
    ): JsonObject {
        // Each mode's refusals, raised before anything is built so the warning names the option the
        // caller actually set rather than the field that went missing.
        if (isEdit && options.durationInSeconds != null) {
            warnings += unsupported("duration", "xAI video editing does not support custom duration.")
        }
        if (isEdit && options.aspectRatio != null) {
            warnings += unsupported("aspectRatio", "xAI video editing does not support custom aspect ratio.")
        }
        if (isExtension && options.aspectRatio != null) {
            warnings += unsupported("aspectRatio", "xAI video extension does not support custom aspect ratio.")
        }
        val resolutionAsked = vendor?.optString("resolution") ?: options.resolution
        if ((isEdit || isExtension) && resolutionAsked != null) {
            val what = if (isEdit) "editing" else "extension"
            warnings += unsupported("resolution", "xAI video $what does not support custom resolution.")
        }
        if (options.fps != null) {
            warnings += unsupported("fps", "xAI video models do not accept a frame rate.")
        }
        if (options.generateAudio != null) {
            warnings += unsupported("generateAudio", "xAI decides audio per model; the flag was ignored.")
        }
        if (options.seed != null) {
            warnings += unsupported("seed", "xAI video models do not accept a seed.")
        }
        if (options.n != 1) {
            warnings += unsupported("n", "xAI returns one clip per job; issue more jobs for more clips.")
        }

        return buildJsonObject {
            put("model", modelId)
            options.prompt?.let { put("prompt", it) }
            if (!isEdit) options.durationInSeconds?.let { put("duration", it) }
            if (!isEdit && !isExtension) {
                options.aspectRatio?.let { put("aspect_ratio", it) }
                resolution(vendor, options, warnings)?.let { put("resolution", it) }
            }
            if (isEdit || isExtension) {
                videoUrl?.let { putJsonObject("video") { put("url", it) } }
            }
            startImage(options, warnings)?.let { putJsonObject("image") { put("url", it) } }
            references(options, vendor, isEdit, isExtension, warnings)?.let { put("reference_images", it) }
            vendor?.optArray("referenceVoiceIds")?.takeIf { it.isNotEmpty() }?.let { voices ->
                put(
                    "reference_audios",
                    buildJsonArray {
                        voices.forEach { add(buildJsonObject { put("voice_id", it.jsonPrimitive.content) }) }
                    },
                )
            }
            vendor?.optString("user")?.let { put("user", it) }
        }
    }

    /** The vendor's own name wins; a pixel size is translated, and an unknown one warns rather than 400s. */
    private fun resolution(
        vendor: JsonObject?,
        options: VideoCallOptions,
        warnings: MutableList<Warning>,
    ): String? {
        vendor?.optString("resolution")?.let { return it }
        val asked = options.resolution ?: return null
        return RESOLUTIONS[asked] ?: run {
            warnings += unsupported(
                "resolution",
                "Unrecognized resolution \"$asked\". Use providerOptions.xai.resolution with " +
                    "\"480p\", \"720p\", or \"1080p\" instead.",
            )
            null
        }
    }

    /**
     * The frame the clip starts from: an explicit first-frame image, else [VideoCallOptions.image].
     *
     * A VIDEO handed to either is refused rather than sent — xAI reads this field as a still, so a clip
     * placed there is decoded as one frame and the caller's intent (continue this video) is lost. The
     * warning names the mode that actually does it.
     */
    private fun startImage(options: VideoCallOptions, warnings: MutableList<Warning>): String? {
        val firstFrame = options.frameImages?.firstOrNull { it.frameType == VideoFrameType.FirstFrame }?.image
        options.frameImages?.firstOrNull { it.frameType == VideoFrameType.LastFrame }?.let { last ->
            warnings += unsupported(
                "frameImages",
                if (last.image.isVideo()) {
                    "xAI does not accept a video as a frame image. Use providerOptions.xai.mode " +
                        "\"extend-video\" to continue from a video instead."
                } else {
                    "xAI video models do not support last_frame. Use providerOptions.xai.mode " +
                        "\"extend-video\" to continue from a video's last frame."
                },
            )
        }
        val start = firstFrame ?: options.image ?: return null
        if (start.isVideo()) {
            warnings += unsupported(
                feature = if (firstFrame != null) "frameImages" else "image",
                details = "xAI does not accept a video as a start image. Use providerOptions.xai.mode " +
                    "\"extend-video\" to continue from a video instead.",
            )
            return null
        }
        return start.toDataUri()
    }

    /**
     * Reference-to-video inputs. First-class [VideoCallOptions.references] win over the legacy
     * `referenceImageUrls` option, and a non-image reference is dropped with a warning: xAI accepts
     * images here only, and a video passed through would be fetched and rejected on their side.
     */
    private fun references(
        options: VideoCallOptions,
        vendor: JsonObject?,
        isEdit: Boolean,
        isExtension: Boolean,
        warnings: MutableList<Warning>,
    ): JsonElement? {
        val legacy = vendor?.optArray("referenceImageUrls")?.map { it.jsonPrimitive.content }
        val wantsReferences = vendor?.optString("mode") == MODE_REFERENCE ||
            !options.references.isNullOrEmpty() ||
            !legacy.isNullOrEmpty()
        if (!wantsReferences || isEdit || isExtension) return null

        val urls = options.references.orEmpty().mapNotNull { reference ->
            if (reference.isVideo()) {
                warnings += unsupported(
                    "references",
                    "xAI reference-to-video accepts image references only. The video reference was " +
                        "ignored; use providerOptions.xai.mode \"extend-video\" to continue from a video.",
                )
                null
            } else {
                reference.toDataUri()
            }
        }.ifEmpty { legacy.orEmpty() }

        if (urls.isEmpty()) {
            warnings += unsupported(
                "references",
                "xAI reference-to-video requires at least one image reference. The video will be " +
                    "generated without reference images.",
            )
            return null
        }
        return buildJsonArray { urls.forEach { add(buildJsonObject { put("url", it) }) } }
    }
}

/** A reference with no media type is an image: only a URL can omit one, and that is the legacy shape. */
private fun VideoFile.isVideo(): Boolean = mediaType?.startsWith("video/") == true

private fun unsupported(feature: String, details: String) =
    Warning.Unsupported(feature = feature, details = details)
