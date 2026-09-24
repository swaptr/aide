package com.sabreware.aide.aisdk.providers.google

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
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.DownloadUrl
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Resolutions Veo names rather than measures; anything else is forwarded as the caller spelled it. */
private val GOOGLE_VIDEO_RESOLUTIONS = mapOf(
    "1280x720" to "720p",
    "1920x1080" to "1080p",
    "3840x2160" to "4k",
)

/** Options this model spends itself; anything else a caller passes goes into `parameters` verbatim. */
private val GOOGLE_VIDEO_HANDLED_OPTIONS = setOf(
    "pollIntervalMs", "pollTimeoutMs", "personGeneration", "negativePrompt", "referenceImages",
)

/**
 * Veo, on the Gemini API's long-running-operation surface.
 *
 * A generation is TWO endpoints: `models/{id}:predictLongRunning` answers with an operation name, and
 * the operation itself is then polled at `{base}/{operationName}` until `done`. The operation handle is
 * `{operationName}` — persistable, so a caller can resume polling after the process that submitted the
 * job has gone.
 *
 * The finished clip comes back as a URI on Google's own file host, which requires the SAME api key as
 * the generation — appended as a `key=` query parameter, but only when the URI stays on the provider's
 * own origin. A response is free to name any host it likes, and appending credentials to a foreign URL
 * hands the key to whoever controls it.
 */
internal class GoogleVideoModel(
    override val modelId: String,
    http: ProviderHttp,
    private val baseUrl: String = GOOGLE_DEFAULT_BASE_URL,
    /** Resolved per call, not per model — see [GoogleLanguageModel.headers]. */
    private val headers: suspend () -> Map<String, String> = { emptyMap() },
) : VideoModel {

    override val provider: String = GOOGLE_PROVIDER_ID

    private val http = http.withErrorStructure(GoogleErrorStructure)

    /** Veo serves several clips per call through `sampleCount`. */
    override suspend fun maxVideosPerCall(): Int = MAX_VIDEOS_PER_CALL

    override suspend fun doStart(options: VideoCallOptions, webhookUrl: String?): VideoStartResult {
        val warnings = mutableListOf<Warning>()
        val body = buildRequestBody(options, warnings)

        val result = http.postJson(
            url = "$baseUrl/models/$modelId:predictLongRunning",
            body = body,
            headers = combineHeaders(headers(), options.headers),
        )
        val operationName = (result.value as? JsonObject)?.optString("name")
            ?: throw NoContentGeneratedError("Google returned no operation name for the video job.")

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
            ?: throw NoContentGeneratedError("The Google operation carries no operationName.")

        val resolved = this.headers()
        // The poll URL is our own base plus the operation name Google minted; it is not a
        // response-supplied URL, so the auth headers may ride.
        val result = http.getJson(
            url = "$baseUrl/$operationName",
            headers = combineHeaders(resolved, headers),
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
            else -> completed(op, apiKey = resolved["x-goog-api-key"], response = response)
        }
    }

    private fun completed(
        op: JsonObject,
        apiKey: String?,
        response: com.sabreware.aide.aisdk.ModalityResponse,
    ): VideoStatusResult.Completed {
        val samples = ((op["response"] as? JsonObject)
            ?.get("generateVideoResponse") as? JsonObject)
            ?.get("generatedSamples")
            ?.let { it as? kotlinx.serialization.json.JsonArray }
            .orEmpty()
        val uris = samples.mapNotNull { sample ->
            ((sample as? JsonObject)?.get("video") as? JsonObject)?.optString("uri")
        }
        if (uris.isEmpty()) {
            throw NoContentGeneratedError("Google reported a finished video operation with no videos.")
        }

        return VideoStatusResult.Completed(
            videos = uris.map { uri ->
                // The key is appended only when the URI stays on our own origin — a response is free to
                // name any host, and credentials belong only to the origin they were issued for.
                val url = if (apiKey != null && DownloadUrl.sameOrigin(uri, baseUrl)) {
                    uri + (if ('?' in uri) "&" else "?") + "key=$apiKey"
                } else {
                    uri
                }
                VideoData.Url(url = url, mediaType = "video/mp4")
            },
            providerMetadata = mapOf(
                GOOGLE_PROVIDER_ID to buildJsonObject {
                    put(
                        "videos",
                        buildJsonArray {
                            // The UN-keyed URIs: metadata is for correlation and support tickets, and a
                            // copy of the credential does not belong in either.
                            uris.forEach { add(buildJsonObject { put("uri", it) }) }
                        },
                    )
                },
            ),
            response = response,
        )
    }

    private fun buildRequestBody(options: VideoCallOptions, warnings: MutableList<Warning>): JsonObject {
        val vendor = options.providerOptions?.forProvider(GOOGLE_PROVIDER_ID)

        val instance = buildJsonObject {
            options.prompt?.let { put("prompt", it) }
            startImage(options)?.let { file ->
                googleVideoImage(file, warnings)?.let { put("image", it) }
            }
            lastFrameImage(options)?.let { file ->
                googleVideoImage(file, warnings)?.let { put("lastFrame", it) }
            }
            referenceImages(options, vendor, warnings)?.let { put("referenceImages", it) }
        }

        val parameters = buildJsonObject {
            put("sampleCount", options.n)
            options.aspectRatio?.let { put("aspectRatio", it) }
            options.resolution?.let { put("resolution", GOOGLE_VIDEO_RESOLUTIONS[it] ?: it) }
            options.durationInSeconds?.let { put("durationSeconds", it.asWholeSecondsIfWhole()) }
            options.seed?.let { put("seed", it) }
            vendor?.optString("personGeneration")?.let { put("personGeneration", it) }
            vendor?.optString("negativePrompt")?.let { put("negativePrompt", it) }
            vendor?.forEach { (key, value) ->
                if (key !in GOOGLE_VIDEO_HANDLED_OPTIONS) put(key, value)
            }
        }

        if (options.fps != null) {
            warnings += Warning.Unsupported(
                "fps",
                "Google video models do not support custom FPS. The fps option was ignored.",
            )
        }
        if (options.generateAudio != null) {
            warnings += Warning.Unsupported(
                "generateAudio",
                "Google video models decide audio by model, not by option; " +
                    "pick a Veo model with the wanted audio behaviour instead.",
            )
        }

        return buildJsonObject {
            put("instances", buildJsonArray { add(instance) })
            put("parameters", parameters)
        }
    }

    /** The reference images: the caller's [VideoCallOptions.references] win over the vendor option. */
    private fun referenceImages(
        options: VideoCallOptions,
        vendor: JsonObject?,
        warnings: MutableList<Warning>,
    ): JsonElement? {
        // With explicit frame images the reference sends no reference images at all.
        if (!options.frameImages.isNullOrEmpty()) return null

        val fromCall = options.references?.takeIf { it.isNotEmpty() }
        if (fromCall != null) {
            val converted = fromCall.mapNotNull { file ->
                googleVideoImage(file, warnings)?.let { image ->
                    buildJsonObject {
                        put("image", image)
                        put("referenceType", "asset")
                    }
                }
            }
            return converted.takeIf { it.isNotEmpty() }?.let { list ->
                buildJsonArray { list.forEach { add(it) } }
            }
        }

        val fromVendor = vendor?.get("referenceImages") as? kotlinx.serialization.json.JsonArray
            ?: return null
        return buildJsonArray {
            fromVendor.forEach { entry ->
                val obj = entry as? JsonObject ?: return@forEach
                val inlined = obj.optString("bytesBase64Encoded")
                val gcs = obj.optString("gcsUri")
                when {
                    inlined != null -> add(referenceAsset { put("bytesBase64Encoded", inlined) })
                    gcs != null -> add(referenceAsset { put("gcsUri", gcs) })
                    // Neither known field: the caller is speaking a shape Veo defines and we do not.
                    else -> add(obj)
                }
            }
        }
    }

    private inline fun referenceAsset(crossinline image: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put(
                "image",
                buildJsonObject {
                    image()
                    put("mimeType", "image/png")
                },
            )
            put("referenceType", "asset")
        }

    private companion object {
        const val MAX_VIDEOS_PER_CALL = 4
    }
}

/**
 * A file as Veo's `predictLongRunning` wants it — the Vertex-style image payload, NOT the Gemini
 * `inlineData` shape this package uses everywhere else.
 *
 * Only bytes and `gs://` URIs are expressible; an ordinary URL has no field to ride in, so it warns and
 * drops rather than being mangled into one of the other two.
 */
private fun googleVideoImage(file: VideoFile, warnings: MutableList<Warning>): JsonObject? = when (file) {
    is VideoFile.Url ->
        if (file.url.startsWith("gs://")) {
            buildJsonObject {
                put("gcsUri", file.url)
                put("mimeType", file.mediaType ?: "image/png")
            }
        } else {
            warnings += Warning.Unsupported(
                "URL-based image input",
                "Google Generative AI video models require base64-encoded images or GCS URIs. " +
                    "URL will be ignored.",
            )
            null
        }
    is VideoFile.Data -> buildJsonObject {
        put("bytesBase64Encoded", googleVideoBase64(file))
        put("mimeType", file.mediaType)
    }
}

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun googleVideoBase64(file: VideoFile.Data): String = when (val data = file.data) {
    is com.sabreware.aide.aisdk.BinaryData.Base64 -> data.value
    is com.sabreware.aide.aisdk.BinaryData.Bytes -> kotlin.io.encoding.Base64.encode(data.value)
}

private fun startImage(options: VideoCallOptions): VideoFile? =
    options.frameImages?.firstOrNull { it.frameType == VideoFrameType.FirstFrame }?.image
        ?: options.image

private fun lastFrameImage(options: VideoCallOptions): VideoFile? =
    options.frameImages?.firstOrNull { it.frameType == VideoFrameType.LastFrame }?.image

/** Veo takes whole seconds; `8.0` goes out as `8`, and a genuine fraction goes out as the caller said. */
private fun Double.asWholeSecondsIfWhole(): JsonPrimitive =
    if (this == toLong().toDouble()) JsonPrimitive(toLong()) else JsonPrimitive(this)
