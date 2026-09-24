package com.sabreware.aide.aisdk.providers.replicate

import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.VideoStartResult
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.DownloadUrl
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.providers.media.toDataUri
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Replicate video generation.
 *
 * Unlike an image call, this one cannot be held open: a clip takes minutes, so the prediction is started
 * and its `urls.get` is handed back as the operation. Everything the caller needs to resume after a
 * restart is in that one URL, which is why the handle carries nothing else.
 */
internal class ReplicateVideoModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val apiToken: String,
) : VideoModel {

    override val provider: String = REPLICATE_PROVIDER_ID

    override suspend fun maxVideosPerCall(): Int = 1

    override val supportsWebhooks: Boolean = true

    override suspend fun doStart(options: VideoCallOptions, webhookUrl: String?): VideoStartResult {
        val warnings = mutableListOf<Warning>()
        val replicateOptions = options.providerOptions?.get(REPLICATE_PROVIDER_ID)
        val (url, version) = replicatePredictionUrl(baseUrl, modelId)

        if (options.n > 1) {
            warnings += Warning.Unsupported("n", "Replicate video models generate one clip per call.")
        }
        if (options.frameImages != null) {
            warnings += Warning.Unsupported(
                "frameImages",
                "Replicate video models take a single start frame; use `image` instead.",
            )
        }
        if (options.references != null) {
            warnings += Warning.Unsupported(
                "references",
                "Replicate video models do not accept style or subject references.",
            )
        }

        val body = buildJsonObject {
            putJsonObject("input") {
                options.prompt?.let { put("prompt", it) }
                options.image?.let { put("image", it.toDataUri()) }
                options.aspectRatio?.let { put("aspect_ratio", it) }
                // Replicate spells a video's pixel dimensions `size`, not `resolution`.
                options.resolution?.let { put("size", it) }
                options.durationInSeconds?.let { put("duration", it) }
                options.fps?.let { put("fps", it) }
                options.seed?.let { put("seed", it) }
                replicateOptions?.forEach { (key, value) ->
                    if (key !in REPLICATE_VIDEO_TRANSPORT_OPTIONS) put(key, value)
                }
            }
            version?.let { put("version", it) }
            if (webhookUrl != null) {
                put("webhook", webhookUrl)
                // Without the filter Replicate calls the webhook on every intermediate log line, so a
                // listener written for "the clip is ready" fires dozens of times per job.
                putJsonArray("webhook_events_filter") { add(kotlinx.serialization.json.JsonPrimitive("completed")) }
            }
        }

        val result = http.postJson(
            url = url,
            body = body,
            headers = combineHeaders(replicateAuthHeaders(apiToken), options.headers),
        )
        val getUrl = (result.value.jsonObject["urls"] as? JsonObject)
            ?.get("get")?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("Replicate returned no urls.get to poll")

        return VideoStartResult(
            operation = buildJsonObject { put("getUrl", getUrl) },
            warnings = warnings,
            response = result.modalityResponse(modelId = modelId),
        )
    }

    override suspend fun doStatus(operation: JsonElement, headers: Map<String, String>?): VideoStatusResult {
        val getUrl = operation.jsonObject["getUrl"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("The Replicate operation carries no getUrl")

        // The URL came out of a vendor response body, so it is validated before the request and the token
        // rides along only while it stays on the API host.
        DownloadUrl.validate(getUrl)
        val result = http.getJson(
            url = getUrl,
            headers = DownloadUrl.headersFor(
                url = getUrl,
                trustedOrigin = baseUrl,
                headers = combineHeaders(replicateAuthHeaders(apiToken), headers),
            ),
        )
        val prediction = result.value.jsonObject
        val response = result.modalityResponse(modelId = modelId)

        return when (val status = prediction["status"]?.jsonPrimitive?.content) {
            "succeeded" -> {
                // `?.jsonPrimitive?.content` alone reads a JSON null as the four characters "null" and
                // hands the caller that as the clip's URL. A succeeded prediction with no output is a
                // real shape — the reference records it — and it has to be an empty result, not a link.
                val url = prediction["output"]?.let { it as? JsonPrimitive }
                    ?.takeIf { it.isString }?.content
                    ?: throw NoContentGeneratedError("Replicate reported success with no video URL")
                VideoStatusResult.Completed(
                    videos = listOf(VideoData.Url(url = url, mediaType = "video/mp4")),
                    providerMetadata = mapOf(
                        REPLICATE_PROVIDER_ID to buildJsonObject {
                            put("videos", buildJsonArray { add(buildJsonObject { put("url", url) }) })
                            prediction["id"]?.let { put("predictionId", it) }
                            prediction["metrics"]?.let { put("metrics", it) }
                        },
                    ),
                    response = response,
                )
            }
            // A refused job and a cancelled one are answers, not transport problems: retrying either
            // verbatim fails the same way, so they are reported rather than thrown.
            "failed" -> VideoStatusResult.Failed(
                error = "Video generation failed: " +
                    (prediction["error"]?.jsonPrimitive?.content ?: "Unknown error"),
                response = response,
            )
            "canceled" -> VideoStatusResult.Failed(
                error = "Video generation was canceled",
                response = response,
            )
            // Terminal like the two above, but a distinct cause worth naming: Replicate documents
            // `aborted` as the prediction exceeding its deadline BEFORE it started running — which is
            // why it costs nothing, where a cancellation is billed for the time that ran. The generic
            // fallback below already caught it correctly; this only says why.
            "aborted" -> VideoStatusResult.Failed(
                error = "Replicate aborted the video: it exceeded its deadline before starting",
                response = response,
            )
            "starting", "processing" -> VideoStatusResult.Pending(response = response)
            // Anything unrecognised is terminal: treating it as pending polls to the caller's timeout and
            // then reports the wrong failure.
            else -> VideoStatusResult.Failed(
                error = "Replicate reported status $status",
                response = response,
            )
        }
    }
}

/** Options that steer the poll or the wait, and would be rejected as model inputs. */
private val REPLICATE_VIDEO_TRANSPORT_OPTIONS =
    setOf("pollIntervalMs", "pollTimeoutMs", REPLICATE_MAX_WAIT)

