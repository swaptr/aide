package com.sabreware.aide.aisdk.providers.prodia

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoFile
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.VideoResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.HttpResult
import com.sabreware.aide.aisdk.util.MultipartResponse
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Prodia video, on the same one-shot job endpoint its image model uses.
 *
 * **Synchronous, which is why this implements `doGenerate` and not the start/status pair.** Every other
 * video vendor here hands back a task id to poll; Prodia holds the connection until the clip is
 * rendered and answers with the finished bytes. Modelling it as an async job would invent a handle
 * there is nothing to poll with.
 *
 * Two request shapes for one endpoint, chosen by whether there is a starting frame: text-to-video
 * posts the job as JSON, image-to-video posts it as multipart with the picture as an `input` part.
 * The response is MULTIPART either way — job metadata as a JSON part named `job`, the clip as a binary
 * part named `output` — the same shape [ProdiaImageModel] decodes, and the reason [MultipartResponse]
 * exists.
 */
internal class ProdiaVideoModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val apiKey: String,
) : VideoModel {

    override val provider: String = PRODIA_PROVIDER_ID

    override suspend fun maxVideosPerCall(): Int = 1

    override suspend fun doGenerate(options: VideoCallOptions): VideoResult {
        val warnings = options.unsupportedWarnings()
        val vendor = options.providerOptions?.get(PRODIA_PROVIDER_ID)

        val envelope = buildJsonObject {
            put("type", modelId)
            put(
                "config",
                buildJsonObject {
                    options.prompt?.let { put("prompt", it) }
                    options.seed?.let { put("seed", it) }
                    vendor?.get("resolution")?.let { put("resolution", it) }
                },
            )
        }
        val jobJson = envelope.toProdiaJobJson()

        val url = "$baseUrl/job?price=true"
        val headers = combineHeaders(
            mapOf("Authorization" to "Bearer $apiKey"),
            options.headers,
            // LAST, so a caller cannot override it — see the same note on the image model. Names
            // the container AND the codec; without it the endpoint answers JSON with a delivery
            // URL on some job types and 406s on others.
            mapOf("Accept" to "multipart/form-data; video/mp4"),
        )
        val image = options.image
        val result: HttpResult<ByteArray> = if (image != null) {
            http.postMultipartForBytes(url, prodiaJobParts(jobJson, image.toProdiaInput()), headers)
        } else {
            http.postBytesForBytes(url, envelope, headers)
        }

        val parts = result.value.prodiaParts(result.headers["content-type"])
        val job = parts.firstOrNull { it.name == "job" }
            ?.let { parseJsonObject(it.body.decodeToString()) }
            ?: throw InvalidResponseDataError("Prodia multipart response missing job part")
        // `output` by name, then anything that declares itself a video — the reference accepts both,
        // because the part is unnamed on some job types.
        val clip = parts.firstOrNull { it.name == "output" }
            ?: parts.firstOrNull { it.contentType?.startsWith("video/") == true }
            ?: throw InvalidResponseDataError("Prodia multipart response missing output video")

        return VideoResult(
            videos = listOf(
                VideoData.Bytes(
                    data = clip.body,
                    mediaType = clip.contentType?.takeIf { it.startsWith("video/") } ?: "video/mp4",
                ),
            ),
            warnings = warnings,
            providerMetadata = mapOf(
                PRODIA_PROVIDER_ID to buildJsonObject {
                    put("videos", buildJsonArray { add(job.toJobMetadata()) })
                },
            ),
            // The envelope, whichever body shape carried it: the transport quotes the JSON body back
            // but holds no string form of a multipart one.
            request = RequestInfo(body = jobJson),
            response = result.modalityResponse(
                modelId = modelId,
                id = job["id"]?.jsonPrimitive?.content,
            ),
        )
    }

    /**
     * The starting frame as bytes we hold.
     *
     * A link is downloaded here rather than handed to the vendor, because the endpoint takes an upload
     * and nothing else — and it goes through the same guard every download in this module does, which
     * is what stops a prompt-supplied URL from naming a loopback or a metadata service. The reference
     * routes its download through the same guard for the same reason.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun VideoFile.toProdiaInput(): ProdiaInput = when (this) {
        is VideoFile.Data -> ProdiaInput(
            bytes = when (val payload = data) {
                is BinaryData.Bytes -> payload.value
                is BinaryData.Base64 -> Base64.decode(payload.value)
            },
            mediaType = mediaType,
        )
        is VideoFile.Url -> {
            val downloaded = http.getBytes(url)
            ProdiaInput(
                bytes = downloaded.value,
                mediaType = downloadedMediaType(downloaded.headers["content-type"], mediaType),
            )
        }
    }
}

/**
 * Every knob the job config has no room for, each named rather than silently dropped.
 *
 * `frameImages` is among them: the job takes ONE `input`, through `image`, and the reference ignores
 * role-tagged frames outright. Ignoring them here too would run a first-and-last-frame request as
 * text-to-video and hand back a clip with no trace of what was dropped.
 */
private fun VideoCallOptions.unsupportedWarnings(): List<Warning> = buildList {
    // Prodia's job config takes a resolution NAME (`720p`), not a pixel pair, so a `WIDTHxHEIGHT`
    // has nowhere to go. Saying so beats sending a value the vendor silently ignores.
    if (resolution != null) {
        add(
            Warning.Unsupported(
                "resolution",
                "Prodia takes a resolution name such as 720p through providerOptions.prodia.resolution.",
            ),
        )
    }
    for ((feature, value) in
        listOf(
            "aspectRatio" to aspectRatio,
            "durationInSeconds" to durationInSeconds,
            "fps" to fps,
            "generateAudio" to generateAudio,
        )
    ) {
        if (value != null) add(Warning.Unsupported(feature))
    }
    if (!frameImages.isNullOrEmpty()) {
        add(Warning.Unsupported("frameImages", "Prodia takes a single start frame; pass it as `image`."))
    }
    if (!references.isNullOrEmpty()) add(Warning.Unsupported("references"))
}
