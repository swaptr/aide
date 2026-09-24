package com.sabreware.aide.aisdk.providers.alibaba

import com.sabreware.aide.aisdk.BinaryData
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
import com.sabreware.aide.aisdk.providers.media.toDataUri
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Which request protocol a model id speaks.
 *
 * Three generations of one API, and the field names moved between them: `parameters.size` became a
 * `resolution` tier, `input.img_url` and `input.reference_urls` became `input.media`, `shot_type`
 * disappeared. Sending a wan2.6 body to a wan3 model does not fail — DashScope IGNORES the unknown
 * fields and generates from the prompt alone, which reads as a model that cannot follow an image.
 */
private enum class WanProtocol { Legacy, Wan27, Wan3 }

private fun protocolOf(modelId: String): WanProtocol = when {
    modelId.startsWith("wan3") -> WanProtocol.Wan3
    modelId.startsWith("wan2.7") -> WanProtocol.Wan27
    else -> WanProtocol.Legacy
}

/** Only meaningful for ids that name their mode; wan3 ships one id and routes by the media sent. */
private fun modeOf(modelId: String): String = when {
    "-i2v" in modelId -> "i2v"
    "-r2v" in modelId -> "r2v"
    else -> "t2v"
}

/** SDK `WIDTHxHEIGHT` frame sizes mapped onto DashScope's named tiers. */
private val ALIBABA_RESOLUTION_TIERS = mapOf(
    "1280x720" to "720P", "720x1280" to "720P", "960x960" to "720P",
    "1088x832" to "720P", "832x1088" to "720P",
    "1920x1080" to "1080P", "1080x1920" to "1080P", "1440x1440" to "1080P",
    "1632x1248" to "1080P", "1248x1632" to "1080P",
    "832x480" to "480P", "480x832" to "480P", "624x624" to "480P",
)

private val ALIBABA_SUPPORTED_RATIOS = setOf("16:9", "9:16", "1:1", "4:3", "3:4")

/** Provider options this model spends itself. */
private val ALIBABA_HANDLED_OPTIONS = setOf(
    "negativePrompt", "audioUrl", "promptExtend", "shotType", "watermark", "audio",
    "referenceUrls", "media", "ratio", "pollIntervalMs", "pollTimeoutMs",
)

/**
 * Alibaba (DashScope) Wan video generation.
 *
 * Uses the NATIVE DashScope API root the embeddings already use — not the `compatible-mode/v1` host
 * the chat models live on. The task is asynchronous only when the submit carries
 * `X-DashScope-Async: enable`; without that header the endpoint blocks the connection for the whole
 * render and then times out, which reads as a dead API rather than a missing header.
 */
internal class AlibabaVideoModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : VideoModel {

    override val provider: String = ALIBABA_PROVIDER_ID

    override suspend fun maxVideosPerCall(): Int = 1

    override suspend fun doStart(options: VideoCallOptions, webhookUrl: String?): VideoStartResult {
        val warnings = mutableListOf<Warning>()
        val body = requestBody(options, warnings)
        if (webhookUrl != null) {
            warnings += Warning.Unsupported(
                "webhookUrl",
                "DashScope's task API takes no per-request webhook; the job must be polled.",
            )
        }

        val result = http.postJson(
            url = "$baseUrl/services/aigc/video-generation/video-synthesis",
            body = body,
            headers = combineHeaders(
                headers + mapOf("X-DashScope-Async" to "enable"),
                options.headers,
            ),
        )
        val taskId = (result.value.jsonObject["output"] as? JsonObject)
            ?.get("task_id")?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("Alibaba returned no task_id")

        return VideoStartResult(
            operation = buildJsonObject { put("taskId", taskId) },
            warnings = warnings,
            response = result.modalityResponse(modelId = modelId, id = taskId),
        )
    }

    override suspend fun doStatus(operation: JsonElement, headers: Map<String, String>?): VideoStatusResult {
        val taskId = operation.jsonObject["taskId"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("The Alibaba operation carries no taskId")

        val result = http.getJson(
            url = "$baseUrl/tasks/$taskId",
            headers = combineHeaders(this.headers, headers),
        )
        val payload = result.value.jsonObject
        val output = payload["output"] as? JsonObject
        val response = result.modalityResponse(modelId = modelId, id = taskId)

        val taskStatus = output?.get("task_status")?.jsonPrimitive?.content
        return when (taskStatus) {
            "SUCCEEDED" -> {
                val videoUrl = output?.get("video_url")?.jsonPrimitive?.content
                    ?: throw NoContentGeneratedError(
                        "Alibaba reported success with no video URL. Task ID: $taskId",
                    )
                VideoStatusResult.Completed(
                    videos = listOf(VideoData.Url(url = videoUrl, mediaType = "video/mp4")),
                    providerMetadata = mapOf(
                        ALIBABA_PROVIDER_ID to buildJsonObject {
                            put("taskId", taskId)
                            put("videoUrl", videoUrl)
                            output?.get("actual_prompt")?.let { put("actualPrompt", it) }
                            (payload["usage"] as? JsonObject)?.let { usage ->
                                putJsonObject("usage") {
                                    usage["duration"]?.let { put("duration", it) }
                                    usage["output_video_duration"]
                                        ?.let { put("outputVideoDuration", it) }
                                    // wan3 splits the total: input video counts toward its ceiling.
                                    usage["input_video_duration"]
                                        ?.let { put("inputVideoDuration", it) }
                                    usage["SR"]?.let { put("resolution", it) }
                                    usage["size"]?.let { put("size", it) }
                                    usage["fps"]?.let { put("fps", it) }
                                    usage["ratio"]?.let { put("ratio", it) }
                                }
                            }
                        },
                    ),
                    response = response,
                )
            }
            "FAILED", "CANCELED" -> VideoStatusResult.Failed(
                error = buildString {
                    append("Video generation ${taskStatus.lowercase()}. Task ID: $taskId.")
                    output?.get("message")?.jsonPrimitive?.content?.let { append(" $it") }
                },
                response = response,
            )
            else -> VideoStatusResult.Pending(response = response)
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun requestBody(options: VideoCallOptions, warnings: MutableList<Warning>): JsonObject {
        val vendor = options.providerOptions?.get(ALIBABA_PROVIDER_ID)
        val protocol = protocolOf(modelId)
        val mode = modeOf(modelId)
        val tiered = protocol != WanProtocol.Legacy
        // wan2.7 T2V and R2V take an explicit ratio (I2V follows the input image); wan3 serves every
        // mode from one id, so it always takes one.
        val supportsRatio =
            protocol == WanProtocol.Wan3 || (protocol == WanProtocol.Wan27 && mode != "i2v")

        for (setting in listOf("pollIntervalMs", "pollTimeoutMs")) {
            if (vendor?.get(setting) != null) {
                warnings += Warning.Deprecated(
                    setting = setting,
                    message = "Polling is the caller's: drive doStatus on your own schedule.",
                )
            }
        }

        val startImage = options.frameImages
            ?.firstOrNull { it.frameType == VideoFrameType.FirstFrame }?.image
            ?: options.image
        val lastFrame = options.frameImages
            ?.firstOrNull { it.frameType == VideoFrameType.LastFrame }?.image

        val input = buildJsonObject {
            options.prompt?.let { put("prompt", it) }
            vendor?.get("negativePrompt")?.let { put("negative_prompt", it) }
            vendor?.get("audioUrl")?.let { put("audio_url", it) }
            when {
                protocol == WanProtocol.Wan3 -> {
                    // The media the request carries decides whether this is text-, image- or
                    // reference-to-video; there is no mode to read off the id.
                    mediaArray(options, vendor, warnings, first = startImage, last = lastFrame)
                        ?.let { put("media", it) }
                }
                mode == "i2v" -> startImage?.let { put("img_url", it.toAlibabaFile()) }
                mode == "r2v" && protocol == WanProtocol.Wan27 ->
                    mediaArray(options, vendor, warnings, first = startImage, last = null)
                        ?.let { put("media", it) }
                mode == "r2v" -> referenceUrls(options, vendor, warnings)?.let { urls ->
                    put("reference_urls", buildJsonArray { urls.forEach { add(jsonString(it)) } })
                }
                else -> Unit
            }
        }

        if (lastFrame != null && protocol != WanProtocol.Wan3) {
            warnings += Warning.Unsupported(
                "frameImages",
                "This model does not support last_frame. The last frame image was ignored.",
            )
        }
        if (!options.references.isNullOrEmpty() && mode != "r2v" && protocol != WanProtocol.Wan3) {
            warnings += Warning.Unsupported(
                "references",
                "Alibaba only supports references (reference-to-video) on reference-to-video models. " +
                    "The reference images were ignored.",
            )
        }

        val parameters = buildJsonObject {
            options.durationInSeconds?.let { put("duration", it.asJsonSeconds()) }
            options.seed?.let { put("seed", it) }
            putResolution(options, protocol, mode, tiered, warnings)
            if (supportsRatio) {
                val ratio = vendor?.get("ratio")?.jsonPrimitive?.content
                    ?: options.aspectRatio
                    ?: options.resolution?.let(::ratioOfResolution)
                ratio?.let { put("ratio", it) }
            }
            vendor?.get("promptExtend")?.let { put("prompt_extend", it) }
            vendor?.get("shotType")?.let { shotType ->
                if (tiered) {
                    // wan2.7 removed shot_type; shot structure is described in the prompt.
                    warnings += Warning.Unsupported(
                        "shotType",
                        "${if (protocol == WanProtocol.Wan3) "wan3" else "wan2.7"} models do not " +
                            "support the shotType option. Describe the shot structure in the prompt " +
                            "instead.",
                    )
                } else {
                    put("shot_type", shotType)
                }
            }
            vendor?.get("watermark")?.let { put("watermark", it) }
            val audio = options.generateAudio ?: vendor.booleanOption("audio")
            if (audio != null) {
                if (protocol == WanProtocol.Wan27) {
                    warnings += Warning.Unsupported(
                        "generateAudio",
                        "wan2.7 models always generate audio. The audio option was ignored.",
                    )
                } else {
                    put("audio", audio)
                }
            }
            vendor?.forEach { (key, value) ->
                if (key !in ALIBABA_HANDLED_OPTIONS) put(key, value)
            }
        }

        if (options.aspectRatio != null && !supportsRatio) {
            warnings += Warning.Unsupported(
                "aspectRatio",
                "Alibaba video models use explicit size/resolution dimensions. " +
                    "Use the resolution option or providerOptions.alibaba for size control.",
            )
        }
        if (options.fps != null) {
            warnings += Warning.Unsupported("fps", "Alibaba video models do not support custom FPS.")
        }
        if (options.n > 1) {
            warnings += Warning.Unsupported(
                "n",
                "Alibaba video models only support generating 1 video per call.",
            )
        }

        return buildJsonObject {
            put("model", modelId)
            put("input", input)
            put("parameters", parameters)
        }
    }

    /**
     * The resolution moved between generations: wan2.6 T2V/R2V take `size` as `WIDTH*HEIGHT`
     * (an SDK `x` becomes the API's `*`), while I2V and everything since wan2.7 take a named tier.
     */
    private fun JsonObjectBuilder.putResolution(
        options: VideoCallOptions,
        protocol: WanProtocol,
        mode: String,
        tiered: Boolean,
        warnings: MutableList<Warning>,
    ) {
        val resolution = options.resolution ?: return
        if (mode == "i2v" || tiered) {
            val tier = ALIBABA_RESOLUTION_TIERS[resolution] ?: resolution
            val supported = if (protocol == WanProtocol.Wan3) {
                listOf("480P", "720P", "1080P")
            } else {
                listOf("720P", "1080P")
            }
            if (tiered && tier !in supported) {
                warnings += Warning.Unsupported(
                    "resolution",
                    "${if (protocol == WanProtocol.Wan3) "wan3" else "wan2.7"} models only support " +
                        "the ${supported.joinToString(", ")} resolution tiers. " +
                        "The resolution \"$resolution\" was ignored.",
                )
            } else {
                put("resolution", tier)
            }
        } else {
            put("size", resolution.replace('x', '*'))
        }
    }

    /** `input.media` for wan2.7 and wan3 — references plus the frame slots, vendor override first. */
    private fun mediaArray(
        options: VideoCallOptions,
        vendor: JsonObject?,
        warnings: MutableList<Warning>,
        first: VideoFile?,
        last: VideoFile?,
    ): JsonArray? {
        (vendor?.get("media") as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { explicit ->
            return buildJsonArray {
                explicit.forEach { item ->
                    val obj = item.jsonObject
                    add(
                        buildJsonObject {
                            put("type", obj.getValue("type"))
                            put("url", obj.getValue("url"))
                            obj["referenceVoice"]?.let { put("reference_voice", it) }
                        },
                    )
                }
            }
        }

        val media = buildJsonArray {
            for (reference in options.references.orEmpty()) {
                when {
                    reference is VideoFile.Url -> add(
                        buildJsonObject {
                            put(
                                "type",
                                if (reference.looksLikeVideo()) "reference_video" else "reference_image",
                            )
                            put("url", reference.url)
                        },
                    )
                    reference.mediaType?.startsWith("image/") == true -> add(
                        buildJsonObject {
                            put("type", "reference_image")
                            put("url", reference.toDataUri())
                        },
                    )
                    else -> warnings += Warning.Unsupported(
                        "references",
                        "Alibaba reference-to-video requires URL references for videos. " +
                            "Non-URL video reference was skipped.",
                    )
                }
            }
            // DashScope's newer slots take a URL or a data URI for an inline frame; the legacy
            // `img_url` field one generation back takes bare base64 instead — see toAlibabaFile.
            first?.let {
                add(
                    buildJsonObject {
                        put("type", "first_frame")
                        put("url", it.toDataUri())
                    },
                )
            }
            last?.let {
                add(
                    buildJsonObject {
                        put("type", "last_frame")
                        put("url", it.toDataUri())
                    },
                )
            }
        }
        return media.takeIf { it.isNotEmpty() }
    }

    /** wan2.6's `reference_urls`: URLs only, frame images shut references out. */
    private fun referenceUrls(
        options: VideoCallOptions,
        vendor: JsonObject?,
        warnings: MutableList<Warning>,
    ): List<String>? {
        if (!options.frameImages.isNullOrEmpty()) return null
        val references = options.references.orEmpty()
        if (references.isNotEmpty()) {
            val urls = references.mapNotNull { reference ->
                (reference as? VideoFile.Url)?.url.also {
                    if (it == null) {
                        warnings += Warning.Unsupported(
                            "references",
                            "Alibaba reference-to-video requires URL references. " +
                                "Non-URL reference was skipped.",
                        )
                    }
                }
            }
            return urls.takeIf { it.isNotEmpty() }
        }
        return (vendor?.get("referenceUrls") as? JsonArray)?.map { it.jsonPrimitive.content }
    }
}

private fun jsonString(value: String): JsonElement =
    kotlinx.serialization.json.JsonPrimitive(value)

private fun JsonObject?.booleanOption(key: String): Boolean? =
    (this?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()

/** The wire routes a bare URL by its extension; only `.mp4`/`.mov` are treated as video. */
private fun VideoFile.Url.looksLikeVideo(): Boolean =
    mediaType?.startsWith("video/") ?: Regex("\\.(mp4|mov)([?#]|$)", RegexOption.IGNORE_CASE)
        .containsMatchIn(url)

/** A whole 5.0 goes out as `5`, matching how the reference's JS number serializes. */
private fun Double.asJsonSeconds(): kotlinx.serialization.json.JsonPrimitive =
    if (this == toInt().toDouble()) {
        kotlinx.serialization.json.JsonPrimitive(toInt())
    } else {
        kotlinx.serialization.json.JsonPrimitive(this)
    }

/**
 * The legacy `img_url` slot takes a URL or BARE base64 — no data-URI prefix — where the newer
 * `input.media` slots take data URIs. Same vendor, two envelopes, one generation apart.
 */
@OptIn(ExperimentalEncodingApi::class)
private fun VideoFile.toAlibabaFile(): String = when (this) {
    is VideoFile.Url -> url
    is VideoFile.Data -> when (val payload = data) {
        is BinaryData.Base64 -> payload.value
        is BinaryData.Bytes -> Base64.encode(payload.value)
    }
}

/**
 * A ratio derived from `WIDTHxHEIGHT`, reduced by GCD, offered only when it lands on a ratio the API
 * accepts — snapping to the nearest named one would silently crop what the caller asked for.
 */
private fun ratioOfResolution(resolution: String): String? {
    val parts = resolution.split("x").takeIf { it.size == 2 } ?: return null
    val width = parts[0].toIntOrNull()?.takeIf { it > 0 } ?: return null
    val height = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
    var a = width
    var b = height
    while (b != 0) {
        val t = b
        b = a % b
        a = t
    }
    val ratio = "${width / a}:${height / a}"
    return ratio.takeIf { it in ALIBABA_SUPPORTED_RATIOS }
}
