package com.sabreware.aide.aisdk.providers.fal

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.VideoStartResult
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.DownloadUrl
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.RetryPolicy
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.providers.media.toDataUri
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * fal.ai video generation, over the queue.
 *
 * The operation handle carries BOTH URLs: the one to poll and the one that was submitted to. The second
 * is not redundant — it is the origin the API key belongs to, and `doStatus` is handed a URL that came
 * out of a vendor response body. Without the submit URL in the handle, a resumed poll after a process
 * restart has nothing to compare against and would either send the key to whatever host the response
 * named or refuse to send it at all.
 */
internal class FalVideoModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val queueUrl: String,
    private val apiKey: String,
) : VideoModel {

    override val provider: String = FAL_PROVIDER_ID

    override suspend fun maxVideosPerCall(): Int = 1

    override val supportsWebhooks: Boolean = true

    override suspend fun doStart(options: VideoCallOptions, webhookUrl: String?): VideoStartResult {
        val warnings = mutableListOf<Warning>()
        val falOptions = options.providerOptions?.get(FAL_PROVIDER_ID)

        if (options.n > 1) {
            warnings += Warning.Unsupported("n", "fal video models generate one clip per call.")
        }
        if (options.fps != null) {
            warnings += Warning.Unsupported("fps", "fal video models do not accept a frame rate.")
        }
        if (options.resolution != null) {
            warnings += Warning.Unsupported(
                "resolution",
                "fal takes a resolution per model; pass it as providerOptions.fal.resolution.",
            )
        }
        if (options.frameImages != null) {
            warnings += Warning.Unsupported(
                "frameImages",
                "fal video models take a single start frame; use `image` instead.",
            )
        }
        if (options.references != null) {
            warnings += Warning.Unsupported(
                "references",
                "fal video models do not accept style or subject references.",
            )
        }

        val body = buildJsonObject {
            options.prompt?.let { put("prompt", it) }
            options.image?.let { put("image_url", it.toDataUri()) }
            options.aspectRatio?.let { put("aspect_ratio", it) }
            options.durationInSeconds?.let { put("duration", "${it.trimTrailingZero()}s") }
            options.seed?.let { put("seed", it) }
            falOptions?.forEach { (key, value) -> put(FAL_VIDEO_FIELD_NAMES[key] ?: key, value) }
        }

        // The queue path is built from `fal-ai/{model}` whatever prefix the caller wrote: `fal-ai/veo3`,
        // `fal/veo3` and `veo3` all name the same model, and only one of the three spellings routes.
        val submitUrl = buildString {
            append("$queueUrl/fal-ai/${modelId.removePrefix("fal-ai/").removePrefix("fal/")}")
            if (webhookUrl != null) append("?fal_webhook=").append(webhookUrl.encodeQueryValue())
        }
        val result = http.postJson(
            url = submitUrl,
            body = body,
            headers = combineHeaders(falAuthHeaders(apiKey), options.headers),
        )
        val responseUrl = result.value.jsonObject["response_url"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("fal returned no response_url to collect from")

        return VideoStartResult(
            operation = buildJsonObject {
                put("responseUrl", responseUrl)
                put("submitUrl", submitUrl)
            },
            warnings = warnings,
            response = result.modalityResponse(modelId = modelId),
        )
    }

    override suspend fun doStatus(operation: JsonElement, headers: Map<String, String>?): VideoStatusResult {
        val handle = operation.jsonObject
        val responseUrl = handle["responseUrl"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("The fal operation carries no responseUrl")
        val submitUrl = handle["submitUrl"]?.jsonPrimitive?.content

        DownloadUrl.validate(responseUrl)
        val result = try {
            // Polling does NOT retry, and that is load-bearing rather than a tuning choice. fal answers
            // "still rendering" with a 500, which the shared transport reads as retryable: with the
            // default policy every poll made before the clip is ready burned three requests and six
            // seconds of backoff and then threw `RetryError` — a wrapper that is not an `APICallError`,
            // so the classification below never ran and an unfinished job surfaced as a failed one. The
            // caller is already polling; a blip is covered by its next poll, not by ours.
            http.withRetryPolicy(RetryPolicy.None).getJson(
                url = responseUrl,
                headers = DownloadUrl.headersFor(
                    url = responseUrl,
                    trustedOrigin = submitUrl,
                    headers = combineHeaders(falAuthHeaders(apiKey), headers),
                ),
            )
        } catch (e: APICallError) {
            // fal reports "not finished yet" as an ERROR response, not as a status field. Letting that
            // escape turns every poll before the clip is ready into a failed generation.
            val detail = (e.data as? JsonObject)?.get("detail")?.jsonPrimitive?.content
            return if (detail == FAL_IN_PROGRESS) {
                VideoStatusResult.Pending()
            } else {
                VideoStatusResult.Failed(error = e.message ?: "fal reported a failed generation")
            }
        }

        val payload = result.value.jsonObject
        val video = payload["video"]?.jsonObject
        val url = video?.get("url")?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("fal reported a finished job with no video URL")

        return VideoStatusResult.Completed(
            videos = listOf(
                VideoData.Url(
                    url = url,
                    mediaType = video["content_type"]?.jsonPrimitive?.content ?: "video/mp4",
                ),
            ),
            providerMetadata = mapOf(
                FAL_PROVIDER_ID to buildJsonObject {
                    put(
                        "videos",
                        kotlinx.serialization.json.buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("url", url)
                                    video["width"]?.let { put("width", it) }
                                    video["height"]?.let { put("height", it) }
                                    video["duration"]?.let { put("duration", it) }
                                    video["fps"]?.let { put("fps", it) }
                                    video["content_type"]?.let { put("contentType", it) }
                                },
                            )
                        },
                    )
                    payload["seed"]?.let { put("seed", it) }
                    payload["timings"]?.let { put("timings", it) }
                    payload["has_nsfw_concepts"]?.let { put("has_nsfw_concepts", it) }
                    payload["prompt"]?.let { put("prompt", it) }
                },
            ),
            response = result.modalityResponse(modelId = modelId),
        )
    }
}

/** The body fal answers a poll with while the clip is still rendering. */
private const val FAL_IN_PROGRESS = "Request is still in progress"

private val FAL_VIDEO_FIELD_NAMES = mapOf(
    "motionStrength" to "motion_strength",
    "negativePrompt" to "negative_prompt",
    "promptOptimizer" to "prompt_optimizer",
)

/** `5.0` is not a duration fal accepts; `5s` is. */
private fun Double.trimTrailingZero(): String =
    if (this == toLong().toDouble()) toLong().toString() else toString()

/**
 * Percent-encodes a webhook URL for the `fal_webhook` query parameter.
 *
 * The colon and slashes in `https://…` would otherwise be read as query structure, and fal would call a
 * truncated address — a listener that is never notified, with nothing logged anywhere to say why.
 */
private fun String.encodeQueryValue(): String = buildString {
    this@encodeQueryValue.encodeToByteArray().forEach { byte ->
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        if (char.isLetterOrDigit() && code < 0x80 || char in "-_.~") {
            append(char)
        } else {
            append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
        }
    }
}

private const val HEX = "0123456789ABCDEF"
