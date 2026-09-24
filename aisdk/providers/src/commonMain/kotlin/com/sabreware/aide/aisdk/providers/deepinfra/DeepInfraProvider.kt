package com.sabreware.aide.aisdk.providers.deepinfra

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.ImageResult
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.util.MultipartPart
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The provider id, and the namespace DeepInfra payloads file under. */
public const val DEEPINFRA_PROVIDER_ID: String = "deepinfra"

/**
 * DeepInfra image generation, natively.
 *
 * The compat `/images/generations` binding this port once advertised 404'd, because DeepInfra's
 * generation endpoint is `POST {base}/inference/{modelId}` — the model in the PATH, `num_images` in the
 * body, and the images answered as `data:` URIs whose prefix must be stripped before the payload is
 * base64.
 *
 * Editing is a different dialect on a different path: with input [ImageCallOptions.files] the call goes
 * to the OpenAI-compatible `{base}/openai/images/edits` as a multipart form, answering the familiar
 * `data[].b64_json`. Two shapes, two error envelopes (`{detail:{error}}` vs `{error:{message}}`), one
 * model class — exactly the reference's split.
 */
public class DeepInfraProvider(
    client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : Provider {

    override val providerId: String = DEEPINFRA_PROVIDER_ID

    private val http = ProviderHttp(client)

    override fun imageModel(modelId: String): ImageModel = DeepInfraImageModel(
        modelId = modelId,
        http = http.withErrorStructure(DeepInfraErrors),
        baseUrl = baseUrl.trimEnd('/'),
        apiKey = apiKey,
    )

    public companion object {
        /** The version root; `/inference` and `/openai` both hang off it. */
        public const val DEFAULT_BASE_URL: String = "https://api.deepinfra.com/v1"
    }
}

/**
 * DeepInfra's two error shapes, tried most-specific first: the inference endpoint wraps its message as
 * `{detail:{error}}`; the OpenAI-compatible edit endpoint answers `{error:{message}}`, which the shared
 * default already reads.
 */
internal val DeepInfraErrors: ProviderErrorStructure = ProviderErrorStructure(
    extractMessage = { body ->
        val obj = body as? JsonObject
        (obj?.get("detail") as? JsonObject)?.get("error")?.jsonPrimitive?.content
            ?: com.sabreware.aide.aisdk.util.defaultErrorMessage(body)
    },
)

internal class DeepInfraImageModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val apiKey: String,
) : ImageModel {

    override val provider: String = DEEPINFRA_PROVIDER_ID

    override suspend fun maxImagesPerCall(): Int = 1

    private val authHeaders get() = mapOf("Authorization" to "Bearer $apiKey")

    override suspend fun doGenerate(options: ImageCallOptions): ImageResult {
        val headers = combineHeaders(authHeaders, options.headers)
        return if (options.files.isNullOrEmpty()) generate(options, headers) else edit(options, headers)
    }

    private suspend fun generate(options: ImageCallOptions, headers: Map<String, String>): ImageResult {
        val size = options.size?.split("x")?.takeIf { it.size == 2 }
        val body = buildJsonObject {
            options.prompt?.let { put("prompt", it) }
            put("num_images", options.n)
            options.aspectRatio?.let { put("aspect_ratio", it) }
            // Strings, as the reference sends the split halves — some models take size, others take a
            // ratio, and DeepInfra itself is left to validate whichever arrived.
            size?.let {
                put("width", it[0])
                put("height", it[1])
            }
            options.seed?.let { put("seed", it) }
            options.providerOptions?.get(DEEPINFRA_PROVIDER_ID)?.forEach { (key, value) ->
                put(key, value)
            }
        }
        val result = http.postJson("$baseUrl/inference/$modelId", body, headers)
        val images = result.value.jsonObject["images"]?.jsonArray.orEmpty()
            .map { it.jsonPrimitive.content }
            // The endpoint answers data URIs; the prefix is envelope, not image.
            .map { BinaryData.Base64(it.substringAfter(";base64,", it)) }
        if (images.isEmpty()) throw NoContentGeneratedError("DeepInfra returned no images.")
        return ImageResult(
            images = images,
            request = result.requestInfo(),
            response = result.modalityResponse(modelId = modelId),
        )
    }

    /** The edit dialect: multipart to the OpenAI-compatible endpoint, `data[].b64_json` back. */
    private suspend fun edit(options: ImageCallOptions, headers: Map<String, String>): ImageResult {
        val parts = buildList {
            add(MultipartPart.Field("model", modelId))
            options.prompt?.let { add(MultipartPart.Field("prompt", it)) }
            options.files.orEmpty().forEachIndexed { index, file ->
                add(file.toUploadPart("image", "image-$index"))
            }
            options.mask?.let { add(it.toUploadPart("mask", "mask")) }
            add(MultipartPart.Field("n", options.n.toString()))
            options.size?.let { add(MultipartPart.Field("size", it)) }
            // A multipart field is text, so a structured value is serialized rather than unwrapped:
            // `jsonPrimitive` THROWS on an object or an array, and DeepInfra's option schema is open,
            // so an unrecognised nested option would have taken the call down with an untyped
            // IllegalArgumentException instead of reaching the wire. The reference stringifies here too.
            options.providerOptions?.get(DEEPINFRA_PROVIDER_ID)?.forEach { (key, value) ->
                val text = (value as? JsonPrimitive)?.content ?: value.toString()
                add(MultipartPart.Field(key, text))
            }
        }
        val result = http.postMultipartParts("$baseUrl/openai/images/edits", parts, headers)
        val images = result.value.jsonObject["data"]?.jsonArray.orEmpty()
            .mapNotNull { (it.jsonObject["b64_json"])?.jsonPrimitive?.content }
            .map { BinaryData.Base64(it) }
        if (images.isEmpty()) throw NoContentGeneratedError("DeepInfra returned no edited images.")
        return ImageResult(
            images = images,
            request = result.requestInfo(),
            response = result.modalityResponse(modelId = modelId),
        )
    }

    /**
     * A file as the edit form wants it: bytes under [field].
     *
     * A URL file is fetched first — the endpoint takes uploads, not links — through the same validated
     * downloader every other fetched asset uses, with no credentials attached: the URL is the caller's,
     * on a host that has no business seeing this vendor's key.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun ImageFile.toUploadPart(field: String, name: String): MultipartPart.File =
        when (this) {
            is ImageFile.Data -> MultipartPart.File(
                field = field,
                fileName = name,
                bytes = when (val payload = data) {
                    is BinaryData.Bytes -> payload.value
                    is BinaryData.Base64 -> Base64.decode(payload.value)
                },
                contentType = mediaType,
            )
            is ImageFile.Url -> {
                val downloaded = http.getBytes(url)
                MultipartPart.File(
                    field = field,
                    fileName = name,
                    bytes = downloaded.value,
                    contentType = mediaType ?: downloaded.headers["content-type"],
                )
            }
        }
}
