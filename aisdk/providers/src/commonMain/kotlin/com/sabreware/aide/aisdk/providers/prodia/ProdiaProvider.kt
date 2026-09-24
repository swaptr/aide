package com.sabreware.aide.aisdk.providers.prodia

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.ImageResult
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.MultipartResponse
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The provider id, and the namespace Prodia payloads file under. */
public const val PRODIA_PROVIDER_ID: String = "prodia"

/**
 * Prodia's v2 job API.
 *
 * The wire this port refused to write from memory, now written from the vendored reference. One
 * request does everything: `POST {base}/job?price=true` with `{type: <model>, config: {...}}` and an
 * `Accept` naming multipart, and the answer is a MULTIPART body — job metadata as a JSON part named
 * `job`, the finished image as a binary part named `output` — decoded by
 * [MultipartResponse], the parser whose absence was the documented reason this provider did not exist.
 *
 * The model id is Prodia's job *type* — `inference.flux.schnell.txt2img.v2` — not a bare model name.
 */
public class ProdiaProvider(
    client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : Provider {

    override val providerId: String = PRODIA_PROVIDER_ID

    private val http = ProviderHttp(client).withErrorStructure(ProdiaErrors)

    override fun imageModel(modelId: String): ImageModel = ProdiaImageModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        apiKey = apiKey,
    )

    /**
     * The image job driven from a conversation — see [ProdiaLanguageModel].
     *
     * Not a chat model, and served here because Prodia's `.img2img.` job types are addressed exactly
     * like every other job on this endpoint.
     */
    override fun languageModel(modelId: String): LanguageModel = ProdiaLanguageModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        apiKey = apiKey,
    )

    override fun videoModel(modelId: String): VideoModel = ProdiaVideoModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        apiKey = apiKey,
    )

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://inference.prodia.com/v2"
    }
}

/** Prodia's error is a union: a string `detail` first, then a structured one, then `error`/`message`. */
internal val ProdiaErrors: ProviderErrorStructure = ProviderErrorStructure(
    extractMessage = { body ->
        val obj = body as? JsonObject
        when (val detail = obj?.get("detail")) {
            null -> obj?.get("error")?.jsonPrimitive?.content
                ?: obj?.get("message")?.jsonPrimitive?.content
            is JsonPrimitive -> if (detail.isString) detail.content else detail.toString()
            else -> detail.toString()
        }
    },
)

internal class ProdiaImageModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val apiKey: String,
) : ImageModel {

    override val provider: String = PRODIA_PROVIDER_ID

    override suspend fun maxImagesPerCall(): Int = 1

    override suspend fun doGenerate(options: ImageCallOptions): ImageResult {
        val warnings = mutableListOf<Warning>()
        val vendor = options.providerOptions?.get(PRODIA_PROVIDER_ID)

        var width: Int? = null
        var height: Int? = null
        options.size?.let { size ->
            val parts = size.split("x")
            width = parts.getOrNull(0)?.toIntOrNull()
            height = parts.getOrNull(1)?.toIntOrNull()
            if (width == null || height == null) {
                warnings += Warning.Unsupported(
                    "size",
                    "Invalid size format: $size. Expected format: WIDTHxHEIGHT (e.g., 1024x1024)",
                )
                width = null
                height = null
            }
        }

        val body = buildJsonObject {
            put("type", modelId)
            putJobConfig(options, vendor, width, height)
        }
        val result = http.postBytesForBytes(
            url = "$baseUrl/job?price=true",
            body = body,
            headers = combineHeaders(
                mapOf("Authorization" to "Bearer $apiKey"),
                options.headers,
                // LAST, so a caller cannot override it — combineHeaders is last-wins and the reference
                // spreads this after the caller's for the same reason. Without this Accept the endpoint
                // answers plain JSON with a delivery URL on some job types and 406s on others; with a
                // caller's `Accept: application/json` it answers a body the multipart parser then
                // fails on, which reads as a broken response rather than a rejected header.
                mapOf("Accept" to "multipart/form-data; image/png"),
            ),
        )

        val boundary = MultipartResponse.boundaryOf(result.headers["content-type"])
            ?: throw InvalidResponseDataError(
                "Prodia response missing multipart boundary in content-type: " +
                    "${result.headers["content-type"]}",
            )
        val parts = MultipartResponse.parse(result.value, boundary)

        val job = parts.firstOrNull { it.name == "job" }
            ?.let { parseJsonObject(it.body.decodeToString()) }
            ?: throw InvalidResponseDataError("Prodia multipart response missing job part")
        val image = parts.firstOrNull { it.name == "output" }
            ?: parts.firstOrNull { it.contentType?.startsWith("image/") == true }
            ?: throw InvalidResponseDataError("Prodia multipart response missing output image")

        return ImageResult(
            images = listOf(BinaryData.Bytes(image.body)),
            warnings = warnings,
            providerMetadata = mapOf(
                PRODIA_PROVIDER_ID to buildJsonObject {
                    put("images", buildJsonArray { add(job.toJobMetadata()) })
                },
            ),
            request = result.requestInfo(),
            response = result.modalityResponse(
                modelId = modelId,
                id = job["id"]?.jsonPrimitive?.content,
            ),
        )
    }

    /** The job's `config` — caller options over size-derived pixels, in the reference's order. */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putJobConfig(
        options: ImageCallOptions,
        vendor: JsonObject?,
        width: Int?,
        height: Int?,
    ) {
        put(
            "config",
            buildJsonObject {
                options.prompt?.let { put("prompt", it) }
                (vendor?.get("width") ?: width?.let(::JsonPrimitive))?.let { put("width", it) }
                (vendor?.get("height") ?: height?.let(::JsonPrimitive))?.let { put("height", it) }
                options.seed?.let { put("seed", it) }
                vendor?.get("steps")?.let { put("steps", it) }
                vendor?.get("stylePreset")?.let { put("style_preset", it) }
                vendor?.get("loras")?.let { loras ->
                    if ((loras as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() != false) {
                        put("loras", loras)
                    }
                }
                vendor?.get("progressive")?.let { put("progressive", it) }
            },
        )
    }
}

/** The metrics worth keeping from a finished job, camelCased the way the reference publishes them. */
internal fun JsonObject.toJobMetadata(): JsonObject = buildJsonObject {
    this@toJobMetadata["id"]?.let { put("jobId", it) }
    (this@toJobMetadata["config"] as? JsonObject)?.get("seed")?.let { put("seed", it) }
    val metrics = this@toJobMetadata["metrics"] as? JsonObject
    metrics?.get("elapsed")?.let { put("elapsed", it) }
    metrics?.get("ips")?.let { put("iterationsPerSecond", it) }
    this@toJobMetadata["created_at"]?.let { put("createdAt", it) }
    this@toJobMetadata["updated_at"]?.let { put("updatedAt", it) }
    ((this@toJobMetadata["price"] as? JsonObject)?.get("dollars"))?.let { put("dollars", it) }
}
