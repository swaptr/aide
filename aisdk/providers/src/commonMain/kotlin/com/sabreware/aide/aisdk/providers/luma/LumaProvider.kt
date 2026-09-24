package com.sabreware.aide.aisdk.providers.luma

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.ImageResult
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.JobStatus
import com.sabreware.aide.aisdk.util.PollPolicy
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.pollUntilDone
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The provider id, and the namespace Luma payloads file under. */
public const val LUMA_PROVIDER_ID: String = "luma"

/**
 * Luma image generation.
 *
 * Queue-based, with its own status words: `queued`, `dreaming`, `completed`, `failed`. `dreaming` is the
 * one that catches people out — it is Luma's "processing", and a client matching only on a standard
 * vocabulary treats it as terminal and reports a failure for a job that is running fine.
 *
 * Luma is also the vendor that made [ImageFile] a union. It accepts a reference image only as a
 * publicly reachable URL and rejects inline data outright, so an editing contract that could hold only
 * bytes could not express a Luma edit at all.
 */
public class LumaProvider(
    client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val pollPolicy: PollPolicy = PollPolicy(),
    private val elapsedMillis: () -> Long,
) : Provider {

    override val providerId: String = LUMA_PROVIDER_ID

    private val http = ProviderHttp(client).withErrorStructure(LumaErrors)

    override fun imageModel(modelId: String): ImageModel = LumaImageModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        apiKey = apiKey,
        pollPolicy = pollPolicy,
        elapsedMillis = elapsedMillis,
    )

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.lumalabs.ai/dream-machine/v1"
    }
}

/** Luma reports a rejected field as FastAPI's `detail` list; the first entry names what to change. */
internal val LumaErrors: ProviderErrorStructure = ProviderErrorStructure(
    extractMessage = { body ->
        ((body as? JsonObject)?.get("detail") as? JsonArray)
            ?.firstOrNull()?.jsonObject?.get("msg")?.jsonPrimitive?.content
    },
)

/** How Luma is told to read the images it was handed. */
private const val LUMA_REFERENCE_TYPE = "referenceType"

/** Per-image weights and identity names, positionally matched to `files`. */
private const val LUMA_IMAGE_CONFIGS = "images"

private val LUMA_NON_REQUEST_OPTIONS =
    setOf(LUMA_REFERENCE_TYPE, LUMA_IMAGE_CONFIGS, "pollIntervalMillis", "maxPollAttempts")

private const val DEFAULT_IMAGE_WEIGHT = 0.85
private const val DEFAULT_STYLE_WEIGHT = 0.8
private const val DEFAULT_MODIFY_WEIGHT = 1.0
private const val MAX_REFERENCE_IMAGES = 4

internal class LumaImageModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val apiKey: String,
    private val pollPolicy: PollPolicy,
    private val elapsedMillis: () -> Long,
) : ImageModel {

    override val provider: String = LUMA_PROVIDER_ID

    override suspend fun maxImagesPerCall(): Int = 1

    private val authHeaders get() = mapOf("Authorization" to "Bearer $apiKey")

    override suspend fun doGenerate(options: ImageCallOptions): ImageResult {
        val warnings = mutableListOf<Warning>()
        if (options.seed != null) {
            warnings += Warning.Unsupported("seed", "This model does not support the `seed` option.")
        }
        if (options.size != null) {
            warnings += Warning.Unsupported(
                "size",
                "This model does not support the `size` option. Use `aspectRatio` instead.",
            )
        }

        val lumaOptions = options.providerOptions?.get(LUMA_PROVIDER_ID)
        val headers = combineHeaders(authHeaders, options.headers)
        val editing = lumaEditingFields(
            files = options.files,
            mask = options.mask,
            referenceType = lumaOptions?.get(LUMA_REFERENCE_TYPE)?.jsonPrimitive?.content ?: "image",
            imageConfigs = (lumaOptions?.get(LUMA_IMAGE_CONFIGS) as? JsonArray).orEmpty(),
        )

        val created = http.postJson(
            url = "$baseUrl/generations/image",
            body = buildJsonObject {
                options.prompt?.let { put("prompt", it) }
                options.aspectRatio?.let { put("aspect_ratio", it) }
                put("model", modelId)
                editing.forEach { (key, value) -> put(key, value) }
                lumaOptions?.forEach { (key, value) ->
                    if (key !in LUMA_NON_REQUEST_OPTIONS) put(key, value)
                }
            },
            headers = headers,
        )
        val id = created.value.jsonObject["id"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("Luma returned no generation id")

        val finished = pollUntilDone(policy = pollPolicy, elapsedMillis = elapsedMillis) {
            val generation = http.getJson("$baseUrl/generations/$id", headers).value.jsonObject
            when (generation["state"]?.jsonPrimitive?.content) {
                "completed" -> JobStatus.Succeeded(generation)
                // `dreaming` is Luma's "processing"; treating it as terminal reports a failure for a job
                // that is running perfectly well.
                "queued", "dreaming" -> JobStatus.InProgress()
                else -> JobStatus.Failed(
                    generation["failure_reason"]?.jsonPrimitive?.content
                        ?: "Luma reported state ${generation["state"]}",
                )
            }
        }

        // `as? JsonObject`: a completed generation with no assets block is a vendor answer, and the
        // throwing accessor would report it as an internal error instead.
        val url = (finished["assets"] as? JsonObject)?.get("image")?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("Luma returned no image asset")

        val image = http.getBytes(url, trustedOrigin = baseUrl)
        return ImageResult(
            images = listOf(BinaryData.Bytes(image.value)),
            warnings = warnings,
            response = created.modalityResponse(modelId = modelId),
        )
    }
}

/**
 * Turns input images into whichever field Luma reads them from.
 *
 * There is no one "input image" key: `image` guides the composition, `style` transfers a look,
 * `character` groups shots of one subject by identity, and `modify_image` transforms a single source.
 * Sending a reference under the wrong key produces a plausible picture that ignores the input entirely —
 * the failure that is hardest to notice, because nothing errors.
 *
 * Every refusal here throws rather than warns. A caller who asked to edit *this* image and silently got
 * a fresh generation of the prompt has been given the wrong picture, and a warning on a result nobody
 * reads is not a correction.
 */
private fun lumaEditingFields(
    files: List<ImageFile>?,
    mask: ImageFile?,
    referenceType: String,
    imageConfigs: List<kotlinx.serialization.json.JsonElement>,
): Map<String, kotlinx.serialization.json.JsonElement> {
    if (mask != null) {
        throw UnsupportedFunctionalityError(
            functionality = "mask",
            message = "Luma AI does not support mask-based image editing. Use the prompt to describe " +
                "the changes you want to make, along with input images containing the source image URL.",
        )
    }
    if (files.isNullOrEmpty()) return emptyMap()

    val urls = files.map { file ->
        (file as? ImageFile.Url)?.url ?: throw UnsupportedFunctionalityError(
            functionality = "files",
            message = "Luma AI only supports URL-based images. Please provide publicly accessible " +
                "image URLs. Base64 and byte data are not supported.",
        )
    }
    fun weight(index: Int, default: Double): Double =
        (imageConfigs.getOrNull(index) as? JsonObject)
            ?.get("weight")?.jsonPrimitive?.content?.toDoubleOrNull() ?: default

    return when (referenceType) {
        "image" -> {
            require(urls.size <= MAX_REFERENCE_IMAGES) {
                "Luma AI image supports up to $MAX_REFERENCE_IMAGES reference images. " +
                    "You provided ${urls.size} images."
            }
            mapOf("image" to weightedList(urls) { weight(it, DEFAULT_IMAGE_WEIGHT) })
        }
        "style" -> mapOf("style" to weightedList(urls) { weight(it, DEFAULT_STYLE_WEIGHT) })
        "character" -> {
            val identities = urls.withIndex().groupBy { (index, _) ->
                (imageConfigs.getOrNull(index) as? JsonObject)
                    ?.get("id")?.jsonPrimitive?.content ?: "identity0"
            }
            identities.forEach { (identity, images) ->
                require(images.size <= MAX_REFERENCE_IMAGES) {
                    "Luma AI character supports up to $MAX_REFERENCE_IMAGES images per identity. " +
                        "Identity '$identity' has ${images.size} images."
                }
            }
            mapOf(
                "character" to buildJsonObject {
                    identities.forEach { (identity, images) ->
                        put(
                            identity,
                            buildJsonObject {
                                put(
                                    "images",
                                    buildJsonArray { images.forEach { add(JsonPrimitive(it.value)) } },
                                )
                            },
                        )
                    }
                },
            )
        }
        "modify_image" -> {
            require(urls.size == 1) {
                "Luma AI modify_image only supports a single input image. " +
                    "You provided ${urls.size} images."
            }
            mapOf(
                "modify_image" to buildJsonObject {
                    put("url", urls.first())
                    put("weight", weight(0, DEFAULT_MODIFY_WEIGHT))
                },
            )
        }
        else -> throw UnsupportedFunctionalityError(
            functionality = "referenceType",
            message = "Luma AI has no reference type '$referenceType'. Use image, style, character or " +
                "modify_image.",
        )
    }
}

private fun weightedList(urls: List<String>, weight: (Int) -> Double) = buildJsonArray {
    urls.forEachIndexed { index, url ->
        add(
            buildJsonObject {
                put("url", url)
                put("weight", weight(index))
            },
        )
    }
}
