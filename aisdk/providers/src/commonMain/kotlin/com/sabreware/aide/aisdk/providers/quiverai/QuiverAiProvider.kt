package com.sabreware.aide.aisdk.providers.quiverai

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.ImageResult
import com.sabreware.aide.aisdk.ImageUsage
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The provider id, and the namespace QuiverAI payloads file under. */
public const val QUIVERAI_PROVIDER_ID: String = "quiverai"

private const val MILLIS_PER_SECOND = 1000L

/**
 * QuiverAI — SVG generation, wearing the image-model contract.
 *
 * The output is not pixels: `data[].svg` is an SVG DOCUMENT, returned here as its UTF-8 bytes with
 * `image/svg+xml` recorded per image in the provider metadata. A caller that writes the bytes to a
 * `.png` gets markup; the metadata is how it knows better.
 *
 * Two operations share the model: `generate` (text→SVG, `POST /svgs/generations`, with optional raster
 * `references`) and `vectorize` (raster→SVG, `POST /svgs/vectorizations`, exactly one input image),
 * chosen by `providerOptions.quiverai.operation`. Pixel-era knobs — size, aspect ratio, seed, mask —
 * have no meaning against an SVG canvas and warn rather than vanish.
 */
public class QuiverAiProvider(
    client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : Provider {

    override val providerId: String = QUIVERAI_PROVIDER_ID

    private val http = ProviderHttp(client).withErrorStructure(QuiverAiErrors)

    override fun imageModel(modelId: String): ImageModel = QuiverAiImageModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        apiKey = apiKey,
    )

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.quiver.ai/v1"
    }
}

/** QuiverAI's flat error shape: `{status, code, message, request_id}`; retryable on 429 and 5xx. */
internal val QuiverAiErrors: ProviderErrorStructure = ProviderErrorStructure(
    extractMessage = { body -> (body as? JsonObject)?.get("message")?.jsonPrimitive?.content },
    isRetryable = { status, _ -> if (status == 429 || status >= 500) true else null },
)

/** The generate operation's reference-image ceiling is per model, and the vendor documents both. */
internal fun quiverAiReferenceLimit(modelId: String): Int = if (modelId == "arrow-1.1-max") 16 else 4

internal class QuiverAiImageModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val apiKey: String,
) : ImageModel {

    override val provider: String = QUIVERAI_PROVIDER_ID

    override suspend fun maxImagesPerCall(): Int = MAX_IMAGES

    override suspend fun doGenerate(options: ImageCallOptions): ImageResult {
        val vendor = options.providerOptions?.get(QUIVERAI_PROVIDER_ID)
        val operation = vendor?.optString("operation") ?: "generate"
        val warnings = collectWarnings(options)

        val body = when (operation) {
            "vectorize" -> vectorizeBody(options, vendor)
            else -> generateBody(options, vendor)
        }
        val path = if (operation == "vectorize") "/svgs/vectorizations" else "/svgs/generations"
        val result = http.postJson(
            url = baseUrl + path,
            body = body,
            headers = combineHeaders(mapOf("Authorization" to "Bearer $apiKey"), options.headers),
        )
        val payload = result.value.jsonObject
        val documents = payload["data"]?.jsonArray.orEmpty().mapNotNull {
            it.jsonObject["svg"]?.jsonPrimitive?.content
        }
        if (documents.isEmpty()) throw NoContentGeneratedError("QuiverAI returned no SVG documents.")

        val usage = payload["usage"] as? JsonObject
        return ImageResult(
            // The SVG document's own bytes — NOT base64. An SVG is already text.
            images = documents.map { BinaryData.Bytes(it.encodeToByteArray()) },
            warnings = warnings,
            usage = usage?.let {
                ImageUsage(
                    inputTokens = it.optInt("input_tokens"),
                    outputTokens = it.optInt("output_tokens"),
                    totalTokens = it.optInt("total_tokens"),
                    raw = it,
                )
            },
            providerMetadata = mapOf(
                QUIVERAI_PROVIDER_ID to buildJsonObject {
                    put(
                        "images",
                        buildJsonArray {
                            payload["data"]?.jsonArray.orEmpty().forEachIndexed { index, entry ->
                                add(
                                    buildJsonObject {
                                        put("index", index)
                                        entry.jsonObject["mime_type"]?.let { put("mimeType", it) }
                                    },
                                )
                            }
                        },
                    )
                },
            ),
            request = result.requestInfo(),
            response = result.modalityResponse(
                modelId = modelId,
                id = payload["id"]?.jsonPrimitive?.content,
            ).let { response ->
                // The vendor's own `created` beats the transport clock: it is when QuiverAI made the
                // image, not when the bytes reached us. Unix SECONDS on the wire, millis in the
                // contract — the conversion is the whole reason to read it rather than pass it through.
                payload["created"]?.jsonPrimitive?.longOrNull
                    ?.let { response.copy(timestamp = it * MILLIS_PER_SECOND) }
                    ?: response
            },
        )
    }

    private fun generateBody(options: ImageCallOptions, vendor: JsonObject?): JsonObject {
        val prompt = options.prompt?.takeIf { it.isNotBlank() }
            ?: throw InvalidArgumentError(
                "QuiverAI image generation requires a non-empty prompt for generateImage.",
                "prompt",
            )
        val references = options.files.orEmpty()
        val limit = quiverAiReferenceLimit(modelId)
        if (references.size > limit) {
            throw InvalidArgumentError(
                "QuiverAI generate supports up to $limit reference images for model \"$modelId\".",
                "files",
            )
        }
        return buildJsonObject {
            put("model", modelId)
            put("n", options.n)
            put("prompt", prompt)
            putShared(vendor)
            vendor?.optString("instructions")?.let { put("instructions", it) }
            if (references.isNotEmpty()) {
                put("references", buildJsonArray { references.forEach { add(it.toReference()) } })
            }
        }
    }

    private fun vectorizeBody(options: ImageCallOptions, vendor: JsonObject?): JsonObject {
        val files = options.files.orEmpty()
        if (files.isEmpty()) {
            throw InvalidArgumentError(
                "QuiverAI vectorize requires an input image. Pass an image in the generateImage " +
                    "prompt and set providerOptions.quiverai.operation to \"vectorize\".",
                "files",
            )
        }
        if (files.size > 1) {
            throw InvalidArgumentError("QuiverAI vectorize accepts a single input image.", "files")
        }
        return buildJsonObject {
            put("model", modelId)
            put("n", options.n)
            put("image", files.first().toReference())
            putShared(vendor)
            vendor?.get("autoCrop")?.let { put("auto_crop", it) }
            vendor?.get("targetSize")?.let { put("target_size", it) }
        }
    }

    /** The sampling knobs both operations take, camelCase in, snake_case out. */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putShared(vendor: JsonObject?) {
        vendor?.get("temperature")?.let { put("temperature", it) }
        vendor?.get("topP")?.let { put("top_p", it) }
        vendor?.get("presencePenalty")?.let { put("presence_penalty", it) }
        vendor?.get("maxOutputTokens")?.let { put("max_output_tokens", it) }
        put("stream", false)
    }

    /** A reference image: the vendor takes `{url}` or `{base64}`, never raw bytes. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun ImageFile.toReference(): JsonObject = buildJsonObject {
        when (val file = this@toReference) {
            is ImageFile.Url -> put("url", file.url)
            is ImageFile.Data -> put(
                "base64",
                when (val payload = file.data) {
                    is BinaryData.Base64 -> payload.value
                    is BinaryData.Bytes -> Base64.encode(payload.value)
                },
            )
        }
    }

    private fun collectWarnings(options: ImageCallOptions): List<Warning> = buildList {
        if (options.size != null) {
            add(
                Warning.Unsupported(
                    "size",
                    "QuiverAI SVG generation does not support the `size` option. The setting was ignored.",
                ),
            )
        }
        if (options.aspectRatio != null) {
            add(
                Warning.Unsupported(
                    "aspectRatio",
                    "QuiverAI SVG generation does not support the `aspectRatio` option. " +
                        "The setting was ignored.",
                ),
            )
        }
        if (options.seed != null) {
            add(
                Warning.Unsupported(
                    "seed",
                    "QuiverAI SVG generation does not support the `seed` option. The setting was ignored.",
                ),
            )
        }
        if (options.mask != null) {
            add(
                Warning.Unsupported(
                    "mask",
                    "QuiverAI SVG generation does not support masks. The mask was ignored.",
                ),
            )
        }
    }

    private companion object {
        /** The vendor's own per-call ceiling for `n`. */
        const val MAX_IMAGES = 16
    }
}
