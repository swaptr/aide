package com.sabreware.aide.aisdk.providers.vertex

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
import com.sabreware.aide.aisdk.providers.google.GoogleErrorStructure
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Veo on Vertex — the same family as the Gemini API's Veo, on a DIFFERENT operation protocol.
 *
 * Submission is the familiar `models/{id}:predictLongRunning`, but polling is a POST to
 * `models/{id}:fetchPredictOperation` with the operation name in the BODY — the Gemini API polls with a
 * GET at `{base}/{operationName}`, and pointing either at the other is a 404. The finished clips also
 * arrive differently: directly as `response.videos[]` (inline base64 or `gcsUri`), not wrapped in
 * `generateVideoResponse.generatedSamples`, and with no `key=` credential dance because auth here is the
 * bearer header on the poll itself.
 *
 * Input images take the Vertex payload shape (`bytesBase64Encoded`/`gcsUri` + `mimeType`); an ordinary
 * `http(s)` URL has no representation and warns rather than being silently dropped. Caller-supplied
 * [VideoCallOptions.references] become `referenceImages` with `referenceType: "asset"`; frame images
 * suppress references entirely (the endpoint accepts one or the other — reference behaviour); a vendor
 * `referenceImages` option passes through verbatim when the caller supplied none.
 *
 * The operation handle is `{"operationName": …}` — persistable, so polling can resume after the process
 * that submitted the job has gone.
 */
internal class VertexVideoModel(
    override val modelId: String,
    http: ProviderHttp,
    private val baseUrl: String,
    private val headers: suspend () -> Map<String, String>,
) : VideoModel {

    override val provider: String = VERTEX_PROVIDER_ID

    private val http = http.withErrorStructure(GoogleErrorStructure)

    /** Vertex serves several clips per submission through `sampleCount`. */
    override suspend fun maxVideosPerCall(): Int = MAX_VIDEOS_PER_CALL

    override suspend fun doStart(options: VideoCallOptions, webhookUrl: String?): VideoStartResult {
        val warnings = mutableListOf<Warning>()
        val body = buildJsonObject {
            putJsonArray("instances") { add(buildInstance(options, warnings)) }
            put("parameters", buildParameters(options, warnings))
        }

        val result = http.postJson(
            url = "$baseUrl/models/$modelId:predictLongRunning",
            body = body,
            headers = combineHeaders(headers(), options.headers),
        )
        val operationName = result.value.jsonObject.optString("name")
            ?: throw NoContentGeneratedError("Vertex returned no operation name for the video job.")

        return VideoStartResult(
            operation = buildJsonObject { put("operationName", operationName) },
            warnings = warnings,
            response = result.modalityResponse(modelId = modelId),
            request = result.requestInfo(),
        )
    }

    override suspend fun doStatus(
        operation: JsonElement,
        headers: Map<String, String>?,
    ): VideoStatusResult {
        val operationName = (operation as? JsonObject)?.optString("operationName")
            ?: throw NoContentGeneratedError("The Vertex operation carries no operationName.")

        val result = http.postJson(
            url = "$baseUrl/models/$modelId:fetchPredictOperation",
            body = buildJsonObject { put("operationName", operationName) },
            headers = combineHeaders(this.headers(), headers),
        )
        val op = result.value.jsonObject
        val response = result.modalityResponse(modelId = modelId)

        val error = op["error"] as? JsonObject
        return when {
            op.optBoolean("done") != true -> VideoStatusResult.Pending(response = response)
            error != null -> VideoStatusResult.Failed(
                error = "Video generation failed: " + (error.optString("message") ?: "Unknown error"),
                response = response,
            )
            else -> completed(op, response)
        }
    }

    private fun completed(
        op: JsonObject,
        response: com.sabreware.aide.aisdk.ModalityResponse,
    ): VideoStatusResult.Completed {
        val entries = (op["response"] as? JsonObject)?.optArray("videos").orEmpty().map { it.jsonObject }
        val videos = entries.mapNotNull { video ->
            val mediaType = video.optString("mimeType") ?: "video/mp4"
            val inline = video.optString("bytesBase64Encoded")
            val gcsUri = video.optString("gcsUri")
            when {
                inline != null -> VideoData.Base64(inline, mediaType)
                gcsUri != null -> VideoData.Url(gcsUri, mediaType)
                else -> null
            }
        }
        if (videos.isEmpty()) {
            throw NoContentGeneratedError("Vertex reported a finished video operation with no videos.")
        }

        return VideoStatusResult.Completed(
            videos = videos,
            providerMetadata = mapOf(
                VERTEX_PROVIDER_ID to buildJsonObject {
                    putJsonArray("videos") {
                        entries.forEach { video ->
                            addJsonObject {
                                video.optString("gcsUri")?.let { put("gcsUri", it) }
                                video.optString("mimeType")?.let { put("mimeType", it) }
                            }
                        }
                    }
                },
            ),
            response = response,
        )
    }

    private fun buildInstance(options: VideoCallOptions, warnings: MutableList<Warning>): JsonObject =
        buildJsonObject {
            options.prompt?.let { put("prompt", it) }

            val startImage = options.frameImages
                ?.firstOrNull { it.frameType == VideoFrameType.FirstFrame }?.image
                ?: options.image
            startImage?.toVertexImage(warnings)?.let { put("image", it) }

            options.frameImages
                ?.firstOrNull { it.frameType == VideoFrameType.LastFrame }?.image
                ?.toVertexImage(warnings)?.let { put("lastFrame", it) }

            // Frame images suppress references entirely: the endpoint accepts one or the other, and
            // sending both makes it ignore the references without saying so.
            val hasFrames = !options.frameImages.isNullOrEmpty()
            val callerReferences = options.references.orEmpty()
            if (!hasFrames && callerReferences.isNotEmpty()) {
                putJsonArray("referenceImages") {
                    callerReferences.forEach { reference ->
                        reference.toVertexImage(warnings)?.let { image ->
                            addJsonObject {
                                put("image", image)
                                put("referenceType", "asset")
                            }
                        }
                    }
                }
            } else if (!hasFrames) {
                options.providerOptions.vertexVendorOptions()?.optArray("referenceImages")
                    ?.let { put("referenceImages", it) }
            }
        }

    private fun buildParameters(options: VideoCallOptions, warnings: MutableList<Warning>): JsonObject {
        if (options.fps != null) {
            warnings += Warning.Unsupported(
                feature = "fps",
                details = "Vertex video models do not accept a frame rate. The `fps` option was ignored.",
            )
        }
        val vendor = options.providerOptions.vertexVendorOptions()
        return buildJsonObject {
            put("sampleCount", options.n)
            options.aspectRatio?.let { put("aspectRatio", it) }
            options.resolution?.let { put("resolution", RESOLUTION_NAMES[it] ?: it) }
            options.durationInSeconds?.let {
                // Whole seconds go as integers — the endpoint's own examples — and fractions survive.
                if (it % 1.0 == 0.0) put("durationSeconds", it.toInt()) else put("durationSeconds", it)
            }
            options.seed?.let { put("seed", it) }
            (options.generateAudio ?: vendor?.optBoolean("generateAudio"))
                ?.let { put("generateAudio", it) }
            vendor?.optString("personGeneration")?.let { put("personGeneration", it) }
            vendor?.optString("negativePrompt")?.let { put("negativePrompt", it) }
            vendor?.optString("gcsOutputDirectory")?.let { put("gcsOutputDirectory", it) }
            // Unclaimed vendor options pass through verbatim; the polling knobs are consumed silently
            // (polling is the caller's here — the house precedent Kling set).
            vendor?.forEach { (key, value) ->
                if (key !in CONSUMED) put(key, value)
            }
        }
    }

    private companion object {
        const val MAX_VIDEOS_PER_CALL = 4

        val RESOLUTION_NAMES = mapOf(
            "1280x720" to "720p",
            "1920x1080" to "1080p",
            "3840x2160" to "4k",
        )

        val CONSUMED = setOf(
            "pollIntervalMs",
            "pollTimeoutMs",
            "personGeneration",
            "negativePrompt",
            "generateAudio",
            "gcsOutputDirectory",
            "referenceImages",
        )
    }
}

/**
 * The Vertex image payload: inline bytes as `bytesBase64Encoded`, `gs://` URIs as `gcsUri`.
 *
 * An ordinary URL has no representation — Vertex fetches nothing on the caller's behalf — so it warns
 * and is dropped rather than silently re-labelled. A `gs://` URI keeps the file's declared media type
 * when present (strictly more information than the reference's hardcoded `image/png`, falling back
 * identically).
 */
@OptIn(ExperimentalEncodingApi::class)
private fun VideoFile.toVertexImage(warnings: MutableList<Warning>): JsonObject? = when (this) {
    is VideoFile.Url ->
        if (url.startsWith("gs://")) {
            buildJsonObject {
                put("gcsUri", url)
                put("mimeType", mediaType ?: "image/png")
            }
        } else {
            warnings += Warning.Unsupported(
                feature = "URL-based image input",
                details = "Vertex AI video models require base64-encoded images or GCS URIs. " +
                    "URL will be ignored.",
            )
            null
        }

    is VideoFile.Data -> buildJsonObject {
        put(
            "bytesBase64Encoded",
            when (val payload = data) {
                is BinaryData.Base64 -> payload.value
                is BinaryData.Bytes -> Base64.encode(payload.value)
            },
        )
        put("mimeType", mediaType)
    }
}
