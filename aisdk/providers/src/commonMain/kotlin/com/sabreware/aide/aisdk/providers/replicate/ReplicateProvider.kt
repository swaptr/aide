package com.sabreware.aide.aisdk.providers.replicate

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.ImageResult
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.JobFailedError
import com.sabreware.aide.aisdk.util.JobTimeoutError
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.providers.media.toDataUri
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The provider id, and the namespace Replicate payloads file under. */
public const val REPLICATE_PROVIDER_ID: String = "replicate"

/**
 * Replicate.
 *
 * **Images are synchronous.** Replicate's `Prefer: wait` header holds the request open until the
 * prediction finishes and answers with the output inline, so an image call is one round trip and the
 * poll loop this used to run was pure latency. Video keeps the operation model, because a clip routinely
 * outlives any request timeout.
 *
 * The model id carries the transport in it: `owner/name` posts to `/models/{id}/predictions`, while
 * `owner/name:sha` pins a version and must post to `/predictions` with `version` in the body. Building
 * the first URL for a versioned id is a 404 that reads as a deleted model.
 */
public class ReplicateProvider(
    client: HttpClient,
    private val apiToken: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : Provider {

    override val providerId: String = REPLICATE_PROVIDER_ID

    private val http = ProviderHttp(client).withErrorStructure(ReplicateErrors)

    override fun imageModel(modelId: String): ImageModel = ReplicateImageModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        apiToken = apiToken,
    )

    override fun videoModel(modelId: String): VideoModel = ReplicateVideoModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        apiToken = apiToken,
    )

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.replicate.com/v1"
    }
}

internal fun replicateAuthHeaders(apiToken: String): Map<String, String> =
    mapOf("Authorization" to "Bearer $apiToken")

/**
 * Replicate puts its message under `detail`, and only sometimes under `error`.
 *
 * The shared default reads `error` first, so a body carrying both would surface the less specific one.
 */
internal val ReplicateErrors: ProviderErrorStructure = ProviderErrorStructure(
    extractMessage = { body ->
        val obj = body as? JsonObject
        obj?.get("detail")?.stringOrNull() ?: obj?.get("error")?.stringOrNull()
    },
)

private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * The prediction URL and the body's `version` field, which are decided together.
 *
 * A versioned id is `owner/name:sha`. Splitting on the LAST colon is deliberate — nothing else in a
 * Replicate id contains one, and a naive `split(":")[0]` silently truncates an id that does.
 */
internal fun replicatePredictionUrl(baseUrl: String, modelId: String): Pair<String, String?> {
    val separator = modelId.lastIndexOf(':')
    return if (separator < 0) {
        "$baseUrl/models/$modelId/predictions" to null
    } else {
        "$baseUrl/predictions" to modelId.substring(separator + 1)
    }
}

/** Flux-2 takes up to eight inputs under numbered keys; everything else takes one under `image`. */
private const val MAX_FLUX_2_INPUT_IMAGES = 8

private fun isFlux2(modelId: String): Boolean = modelId.startsWith("black-forest-labs/flux-2-")

internal class ReplicateImageModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val apiToken: String,
) : ImageModel {

    override val provider: String = REPLICATE_PROVIDER_ID

    override suspend fun maxImagesPerCall(): Int =
        if (isFlux2(modelId)) MAX_FLUX_2_INPUT_IMAGES else 1

    override suspend fun doGenerate(options: ImageCallOptions): ImageResult {
        val warnings = mutableListOf<Warning>()
        val replicateOptions = options.providerOptions?.get(REPLICATE_PROVIDER_ID)
        val (url, version) = replicatePredictionUrl(baseUrl, modelId)

        val body = buildJsonObject {
            putJsonObject("input") {
                options.prompt?.let { put("prompt", it) }
                options.aspectRatio?.let { put("aspect_ratio", it) }
                options.size?.let { put("size", it) }
                options.seed?.let { put("seed", it) }
                put("num_outputs", options.n)
                options.files?.takeIf { it.isNotEmpty() }?.let { files ->
                    if (isFlux2(modelId)) {
                        files.take(MAX_FLUX_2_INPUT_IMAGES).forEachIndexed { index, file ->
                            val key = if (index == 0) "input_image" else "input_image_${index + 1}"
                            put(key, file.toDataUri())
                        }
                        if (files.size > MAX_FLUX_2_INPUT_IMAGES) {
                            warnings += Warning.Other(
                                "Flux-2 models support up to $MAX_FLUX_2_INPUT_IMAGES input images. " +
                                    "Additional images are ignored.",
                            )
                        }
                    } else {
                        put("image", files.first().toDataUri())
                        if (files.size > 1) {
                            warnings += Warning.Other(
                                "This Replicate model only supports a single input image. " +
                                    "Additional images are ignored.",
                            )
                        }
                    }
                }
                options.mask?.let { mask ->
                    if (isFlux2(modelId)) {
                        warnings += Warning.Other(
                            "Flux-2 models do not support mask input. The mask will be ignored.",
                        )
                    } else {
                        put("mask", mask.toDataUri())
                    }
                }
                replicateOptions?.forEach { (key, value) ->
                    // A wait budget is a transport instruction, not a model input; sending it as one is
                    // an "unexpected parameter" rejection from the model itself.
                    if (key != REPLICATE_MAX_WAIT) put(key, value)
                }
            }
            version?.let { put("version", it) }
        }

        val waitSeconds = replicateOptions?.get(REPLICATE_MAX_WAIT)?.jsonPrimitive?.content
        val result = http.postJson(
            url = url,
            body = body,
            // `prefer` goes last so a caller's own header cannot turn the synchronous call back into an
            // asynchronous one this model has no poll loop to finish.
            headers = combineHeaders(
                replicateAuthHeaders(apiToken),
                options.headers,
                mapOf("prefer" to if (waitSeconds != null) "wait=$waitSeconds" else "wait"),
            ),
        )

        val prediction = result.value.jsonObject
        val output = prediction["output"]
        val urls = when {
            // The union that catches clients out: single-output models return a bare string, and reading
            // only the array shape drops every image those models produce, silently. A JSON null is
            // neither — read as a primitive it becomes the four characters "null", which then gets
            // requested as a URL and fails as a download rather than as the empty result it is.
            output is JsonPrimitive && output !is kotlinx.serialization.json.JsonNull ->
                listOf(output.content)
            output is JsonArray -> output.mapNotNull {
                (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content
            }
            else -> emptyList()
        }

        // `Prefer: wait` answers 200 for a prediction that never produced an image, so the terminal
        // states have to be read out of the body: a refused prompt arrives as a successful HTTP call.
        //
        // Only when there is no output, though. Replicate's own recorded wire answers a finished
        // generation with `"status": "processing"` beside a populated `output` — the status describes
        // the prediction record, not the payload — so letting the status overrule an output that is
        // already in hand turned every ordinary `Prefer: wait` call into a spurious timeout.
        if (urls.isEmpty()) when (prediction["status"]?.jsonPrimitive?.content) {
            "failed" -> throw JobFailedError(
                "Replicate generation failed: " +
                    (prediction["error"]?.stringOrNull() ?: "no reason given"),
            )
            "canceled" -> throw JobFailedError("Replicate generation was canceled")
            // Terminal, and NOT the same thing as canceled: Replicate documents `aborted` as the
            // prediction exceeding its deadline "before it could start running", which is why it is
            // billed at nothing where a cancellation is billed for the time that ran. Reported here
            // rather than left to the fallback, which said "no output" — true, but silent about the
            // one fact a caller can act on, since retrying an aborted prediction is often all it takes.
            "aborted" -> throw JobFailedError(
                "Replicate aborted the generation: it exceeded its deadline before starting",
            )
            // The wait window lapsed before the prediction finished. Naming it is the difference between
            // a caller raising `maxWaitTimeInSeconds` and a caller hunting a model that works — this
            // model has no poll loop to fall back to, by design.
            "starting", "processing" -> throw JobTimeoutError(
                (waitSeconds?.toLongOrNull() ?: DEFAULT_WAIT_SECONDS) * MILLIS_PER_SECOND,
            )
            // Deliberately permissive: an unrecognised status falls through to the no-output error
            // below rather than being enumerated away. Replicate has added a status before — `aborted`
            // is absent from the schema this port was written against — and the next one should not
            // stop a caller who does have output in hand.
            else -> Unit
        }
        if (urls.isEmpty()) throw NoContentGeneratedError("Replicate returned no output")

        return ImageResult(
            images = urls.map { BinaryData.Bytes(http.getBytes(it, trustedOrigin = baseUrl).value) },
            warnings = warnings,
            // The prediction id is what a support ticket is opened against, and `metrics` carries the
            // billed prediction time. Neither survives anywhere else once the images are bytes.
            providerMetadata = mapOf(
                REPLICATE_PROVIDER_ID to buildJsonObject {
                    prediction["id"]?.let { put("predictionId", it) }
                    prediction["metrics"]?.let { put("metrics", it) }
                },
            ),
            response = result.modalityResponse(modelId = modelId, id = prediction["id"]?.stringOrNull()),
        )
    }
}

internal const val REPLICATE_MAX_WAIT: String = "maxWaitTimeInSeconds"

/** What `Prefer: wait` means without a number, per Replicate's own documentation. */
private const val DEFAULT_WAIT_SECONDS = 60L

private const val MILLIS_PER_SECOND = 1_000L
