package com.sabreware.aide.aisdk.providers.fal

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.ImageResult
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.PollPolicy
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.defaultErrorMessage
import com.sabreware.aide.aisdk.providers.media.toDataUri
import io.ktor.client.HttpClient
import kotlin.time.TimeSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The provider id, and the namespace fal payloads file under. */
public const val FAL_PROVIDER_ID: String = "fal"

/**
 * fal.ai.
 *
 * **Images and video are on different transports, and swapping them is a live bug.** An image call is a
 * plain synchronous `POST fal.run/{model}` that answers with the finished picture; only video goes
 * through `queue.fal.run`, where a submit returns a URL to poll. Running images through the queue works
 * against a queue host and 404s against the documented one, so the two are separate models here rather
 * than one shape with a flag.
 *
 * Auth is `Authorization: Key …` — neither a bearer nor a bare key, and getting it wrong is a 401 that
 * looks like an invalid credential rather than a malformed header.
 */
public class FalProvider(
    client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val queueUrl: String = DEFAULT_QUEUE_URL,
    /** How hard transcription polls the queue. Video is exempt: its caller drives the polling. */
    private val pollPolicy: PollPolicy = PollPolicy(),
    /**
     * The poll budget's clock; pinned in tests. Defaulted, unlike the all-async vendors' constructors,
     * because most of this provider (images, speech, video) never polls — demanding a clock from every
     * caller taxes the majority for the one model that needs it.
     */
    private val elapsedMillis: () -> Long = monotonicElapsed(),
) : Provider {

    override val providerId: String = FAL_PROVIDER_ID

    private val http = ProviderHttp(client).withErrorStructure(FalErrors)

    override fun imageModel(modelId: String): ImageModel = FalImageModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        apiKey = apiKey,
    )

    override fun videoModel(modelId: String): VideoModel = FalVideoModel(
        modelId = modelId,
        http = http,
        queueUrl = queueUrl.trimEnd('/'),
        apiKey = apiKey,
    )

    override fun speechModel(modelId: String): SpeechModel = FalSpeechModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        apiKey = apiKey,
    )

    override fun transcriptionModel(modelId: String): TranscriptionModel = FalTranscriptionModel(
        modelId = modelId,
        http = http,
        queueUrl = queueUrl.trimEnd('/'),
        apiKey = apiKey,
        pollPolicy = pollPolicy,
        elapsedMillis = elapsedMillis,
    )

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://fal.run"
        public const val DEFAULT_QUEUE_URL: String = "https://queue.fal.run"
    }
}

/** `Authorization: Key …`. Neither `Bearer` nor a bare key is accepted. */
internal fun falAuthHeaders(apiKey: String): Map<String, String> =
    mapOf("Authorization" to "Key $apiKey")

/** A monotonic clock started at construction — differences are all the poll budget reads. */
private fun monotonicElapsed(): () -> Long {
    val started = TimeSource.Monotonic.markNow()
    return { started.elapsedNow().inWholeMilliseconds }
}

/**
 * fal's two error shapes.
 *
 * A rejected parameter comes back as FastAPI's validation envelope — a list of `{loc, msg}` pairs — and
 * the useful part is which field was rejected. Flattening that to "422" is why an unaccepted `image_size`
 * reads as a server fault rather than as the one field to change.
 *
 * Everything else — a bad key, a missing model — is fal's documented `{error: {message, code}}`, so the
 * shared default finishes the job. Without that fallback a 401 arrives as the raw JSON body, which is
 * the excerpt an unmatched structure produces.
 */
internal val FalErrors: ProviderErrorStructure = ProviderErrorStructure(
    extractMessage = { body ->
        val detail = (body as? JsonObject)?.get("detail") as? JsonArray
        detail?.mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            val message = item["msg"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val location = (item["loc"] as? JsonArray)
                ?.joinToString(".") { it.jsonPrimitive.content }
            if (location == null) message else "$location: $message"
        }?.takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: defaultErrorMessage(body)
    },
)

/**
 * Copies a provider-option entry onto the request body under fal's own field name.
 *
 * fal names its inputs in snake_case while the option keys are camelCase, and the mapping is not
 * mechanical — `enableSafetyChecker` is `enable_safety_checker`, but a model-specific key a caller passes
 * for a LoRA has no mapping at all and must go through untouched. Anything unmapped is passed through
 * verbatim, which is what makes a model this port has never heard of usable.
 */
private val FAL_IMAGE_FIELD_NAMES = mapOf(
    "imageUrl" to "image_url",
    "maskUrl" to "mask_url",
    "guidanceScale" to "guidance_scale",
    "numInferenceSteps" to "num_inference_steps",
    "enableSafetyChecker" to "enable_safety_checker",
    "outputFormat" to "output_format",
    "syncMode" to "sync_mode",
    "safetyTolerance" to "safety_tolerance",
)

/** Selects `image_urls` over a single `image_url`; a routing flag, so it is never sent. */
private const val FAL_USE_MULTIPLE_IMAGES = "useMultipleImages"

/**
 * The snake_case spellings fal still accepts and the camelCase ones that replace them.
 *
 * They reach the wire unchanged — snake_case is what fal wants — so nothing breaks by passing them, and
 * that is exactly the problem: a caller on the deprecated spelling gets no signal at all until the
 * vendor drops it. The reference warns, listing the replacement for each key; this does the same, one
 * `Warning.Deprecated` per key rather than the reference's single concatenated sentence, so a caller can
 * match on the setting instead of grepping a message.
 */
private val FAL_DEPRECATED_IMAGE_FIELD_NAMES =
    FAL_IMAGE_FIELD_NAMES.entries.associate { (camel, snake) -> snake to camel }

internal class FalImageModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val apiKey: String,
) : ImageModel {

    override val provider: String = FAL_PROVIDER_ID

    override suspend fun maxImagesPerCall(): Int = 1

    override suspend fun doGenerate(options: ImageCallOptions): ImageResult {
        val warnings = mutableListOf<Warning>()
        val falOptions = options.providerOptions?.get(FAL_PROVIDER_ID)
        val useMultipleImages =
            falOptions?.get(FAL_USE_MULTIPLE_IMAGES)?.jsonPrimitive?.content == "true"

        val body = buildJsonObject {
            options.prompt?.let { put("prompt", it) }
            options.seed?.let { put("seed", it) }
            put("num_images", options.n)
            // fal takes an enum name or a `{width, height}` object here. A `"1024x1024"` string is a 422
            // on every call that sets a size, which is what this field used to send.
            falImageSize(options.size, options.aspectRatio)?.let { put("image_size", it) }

            options.files?.takeIf { it.isNotEmpty() }?.let { files ->
                if (useMultipleImages) {
                    // A bare base64 string is what a caller reaches for, and fal accepts it silently
                    // as a PROMPT — the edit then runs as a text-to-image of a base64 blob.
                    put("image_urls", buildJsonArray { files.forEach { add(JsonPrimitive(it.toDataUri())) } })
                } else {
                    put("image_url", files.first().toDataUri())
                    if (files.size > 1) {
                        warnings += Warning.Other(
                            "Multiple input images provided but useMultipleImages is not enabled. " +
                                "Only the first image will be used. Set providerOptions.fal.useMultipleImages " +
                                "to true for models that support multiple images (e.g., fal-ai/flux-2/edit).",
                        )
                    }
                }
            }
            options.mask?.let { put("mask_url", it.toDataUri()) }

            falOptions?.forEach { (key, value) ->
                if (key == FAL_USE_MULTIPLE_IMAGES) return@forEach
                FAL_DEPRECATED_IMAGE_FIELD_NAMES[key]?.let { replacement ->
                    warnings += Warning.Deprecated(
                        setting = key,
                        message = "fal provider options are camelCase; use '$replacement' instead.",
                    )
                }
                put(FAL_IMAGE_FIELD_NAMES[key] ?: key, value)
            }
        }

        val result = http.postJson(
            url = "$baseUrl/$modelId",
            body = body,
            headers = combineHeaders(falAuthHeaders(apiKey), options.headers),
        )
        val payload = result.value.jsonObject
        // Most fal image models answer with `images`; a handful (easel-avatar among them) answer with a
        // single `image`, and reading only the array drops every picture those models produce.
        val images = payload["images"]?.jsonArray
            ?: payload["image"]?.let { buildJsonArray { add(it) } }
            ?: throw NoContentGeneratedError("fal returned no images")
        val urls = images.mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.content }
        if (urls.isEmpty()) throw NoContentGeneratedError("fal returned no images")

        return ImageResult(
            // Fetched rather than handed back as links: fal's URLs expire, so a stored link is a broken
            // image later instead of an error now. `trustedOrigin` keeps the key off a foreign host that
            // fal's own response happened to name.
            images = urls.map {
                BinaryData.Bytes(http.getBytes(it, trustedOrigin = baseUrl).value)
            },
            warnings = warnings,
            providerMetadata = falImageMetadata(payload, images),
            response = result.modalityResponse(modelId = modelId),
        )
    }
}

/**
 * fal's per-image extras, which are the whole reason a caller reaches for this vendor's metadata.
 *
 * `has_nsfw_concepts` (and `nsfw_content_detected`, which the older models spell it as) is a
 * safety-relevant per-image flag: dropping it leaves a caller unable to tell a filtered image from a
 * clean one. `timings` and `seed` are what make a generation reproducible.
 */
private fun falImageMetadata(payload: JsonObject, images: JsonArray): ProviderMetadata {
    val nsfw = (payload["has_nsfw_concepts"] ?: payload["nsfw_content_detected"]) as? JsonArray
    val perImage = buildJsonArray {
        images.forEachIndexed { index, image ->
            add(
                buildJsonObject {
                    image.jsonObject.forEach { (key, value) ->
                        when (key) {
                            "url" -> Unit
                            "content_type" -> put("contentType", value)
                            "file_name" -> put("fileName", value)
                            "file_data" -> put("fileData", value)
                            "file_size" -> put("fileSize", value)
                            else -> put(key, value)
                        }
                    }
                    nsfw?.getOrNull(index)?.let { put("nsfw", it) }
                },
            )
        }
    }
    return mapOf(
        FAL_PROVIDER_ID to buildJsonObject {
            put("images", perImage)
            payload.forEach { (key, value) ->
                // `prompt` is the prompt we sent back verbatim, not a revised one, so it carries nothing.
                if (key !in FAL_METADATA_EXCLUDED) put(key, value)
            }
        },
    )
}

private val FAL_METADATA_EXCLUDED =
    setOf("images", "image", "prompt", "has_nsfw_concepts", "nsfw_content_detected")

/**
 * fal's `image_size`: an enum name for the ratios it has presets for, a `{width, height}` object
 * otherwise.
 *
 * The preset names read backwards on purpose — `9:16` is `portrait_16_9`, because fal names the preset
 * after the ratio's landscape form and then says which way up it is. "Correcting" that to
 * `portrait_9_16` is a 422.
 */
private fun falImageSize(size: String?, aspectRatio: String?): JsonElement? {
    if (size != null) {
        val (width, height) = size.split("x").takeIf { it.size == 2 } ?: return null
        val w = width.toIntOrNull() ?: return null
        val h = height.toIntOrNull() ?: return null
        return buildJsonObject {
            put("width", w)
            put("height", h)
        }
    }
    return when (aspectRatio) {
        null -> null
        "1:1" -> JsonPrimitive("square_hd")
        "16:9" -> JsonPrimitive("landscape_16_9")
        "9:16" -> JsonPrimitive("portrait_16_9")
        "4:3" -> JsonPrimitive("landscape_4_3")
        "3:4" -> JsonPrimitive("portrait_4_3")
        "16:10" -> falSize(1280, 800)
        "10:16" -> falSize(800, 1280)
        "21:9" -> falSize(2560, 1080)
        "9:21" -> falSize(1080, 2560)
        else -> null
    }
}

private fun falSize(width: Int, height: Int): JsonElement = buildJsonObject {
    put("width", width)
    put("height", height)
}
