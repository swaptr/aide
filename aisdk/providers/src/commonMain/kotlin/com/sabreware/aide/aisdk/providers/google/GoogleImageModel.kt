package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.ImageResult
import com.sabreware.aide.aisdk.ImageUsage
import com.sabreware.aide.aisdk.ModalityResponse
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.util.ProviderHttp
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Gemini image generation.
 *
 * There is no image endpoint here at all: a Gemini image model IS a language model asked to answer with
 * `responseModalities: ["IMAGE"]`, so this class translates the image call onto [GoogleLanguageModel]
 * and collects the image file parts out of its answer. The reference reached the same shape when Google
 * retired the Imagen `:predict` surface from this API — a model id that does not start with `gemini-`
 * is rejected before any request is made, because the endpoint it would need no longer exists.
 *
 * What survives the translation and what cannot:
 *
 * - `aspectRatio` becomes `generationConfig.imageConfig.aspectRatio`; a caller's own `imageConfig`
 *   (e.g. `imageSize`) merges under it, with `aspectRatio` winning.
 * - `size` has no wire field — Gemini thinks in ratios — so it warns rather than being silently mapped
 *   to the nearest ratio, which would produce differently-cropped images than the caller asked for.
 * - Input images for editing go as inline bytes. A URL input is refused loudly: `generateContent`'s
 *   `fileUri` takes Files-API handles, not arbitrary links, so forwarding one would fail on Google's
 *   side with an error naming a URI the caller never wrote.
 * - `n > 1` and `mask` have no representation at all and are refused. [maxImagesPerCall] is therefore
 *   **1** — deliberately diverging from the reference's 10, which combines with its own n>1 rejection
 *   to fail any multi-image run; a truthful ceiling of one lets a runtime fan nine images out as nine
 *   calls that all succeed.
 * - `providerOptions["google"].googleSearch` becomes the `google.google_search` provider tool on the
 *   underlying call — image generation has no tools parameter of its own, and grounding an image in
 *   search results is a real Gemini capability that would otherwise be unreachable.
 */
internal class GoogleImageModel(
    override val modelId: String,
    http: ProviderHttp,
    baseUrl: String = GOOGLE_DEFAULT_BASE_URL,
    headers: suspend () -> Map<String, String> = { emptyMap() },
) : ImageModel {

    override val provider: String = GOOGLE_PROVIDER_ID

    private val languageModel = GoogleLanguageModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl,
        headers = headers,
    )

    override suspend fun maxImagesPerCall(): Int = 1

    override suspend fun doGenerate(options: ImageCallOptions): ImageResult {
        val (call, warnings) = googleImageCall(modelId, options)
        return languageModel.doGenerate(call).toGoogleImageResult(modelId, warnings)
    }
}

/**
 * An image call as the language-model call it is, plus what had to be warned about.
 *
 * Shared with the batch model, which submits the same body a day early: the validation (no mask, one
 * image, a Gemini id), the prompt and input images as user parts, `responseModalities: ["IMAGE"]`, the
 * `imageConfig` merge and the `googleSearch` tool all happen here so the two surfaces cannot drift.
 */
internal fun googleImageCall(modelId: String, options: ImageCallOptions): Pair<CallOptions, List<Warning>> {
    if (!modelId.startsWith("gemini-")) {
        throw UnsupportedFunctionalityError(
            functionality = "non-Gemini image model",
            message = "Google image models other than Gemini are no longer supported. " +
                "Use a model ID that starts with `gemini-`.",
        )
    }
    if (options.mask != null) {
        throw UnsupportedFunctionalityError(
            functionality = "mask",
            message = "Gemini image models do not support mask-based image editing.",
        )
    }
    if (options.n > 1) {
        throw UnsupportedFunctionalityError(
            functionality = "n",
            message = "Gemini image models do not support generating a set number of images " +
                "per call. Use n=1 or omit the n parameter.",
        )
    }

    val warnings = mutableListOf<Warning>()
    if (options.size != null) {
        warnings += Warning.Unsupported(
            feature = "size",
            details = "This model does not support the `size` option. Use `aspectRatio` instead.",
        )
    }

    val vendor = options.providerOptions?.forProvider(GOOGLE_PROVIDER_ID)
    val call = CallOptions(
        prompt = listOf(ModelMessage.User(userParts(options))),
        seed = options.seed,
        providerOptions = mapOf(GOOGLE_PROVIDER_ID to forwardedOptions(vendor, options)),
        tools = vendor?.optObject("googleSearch")?.let {
            listOf(
                Tool.ProviderDefined(
                    name = "google_search",
                    id = "google.google_search",
                    args = it,
                    providerExecuted = true,
                ),
            )
        },
        headers = options.headers,
    )
    return call to warnings
}

/**
 * The language model's answer as the image contract's result.
 *
 * [ImageResult.isRetryable] is the provider-independent classification the runtime's empty-result
 * retry reads: a blocked prompt is terminal, everything else is left for the runtime to decide.
 */
internal fun GenerateResult.toGoogleImageResult(modelId: String, warnings: List<Warning>): ImageResult {
    val images = content
        .filterIsInstance<Content.File>()
        .filter { it.mediaType.startsWith("image/") }
        .mapNotNull { (it.data as? FileData.Bytes)?.bytes }
        .map { BinaryData.Bytes(it) }

    return ImageResult(
        images = images,
        warnings = warnings,
        usage = usage.toImageUsage(),
        providerMetadata = mapOf(
            GOOGLE_PROVIDER_ID to buildJsonObject {
                providerMetadata?.get(GOOGLE_PROVIDER_ID)?.forEach { (key, value) -> put(key, value) }
                // One entry per image, the reference's own shape: a consumer indexes per-image
                // metadata by position, and an empty object marks "nothing to say about this one".
                putJsonArray("images") { images.forEach { add(JsonObject(emptyMap())) } }
            },
        ),
        response = ModalityResponse(
            modelId = modelId,
            timestamp = response?.metadata?.timestamp,
            id = response?.metadata?.id,
            headers = response?.headers,
        ),
        request = request,
        isRetryable = googleImageRetryability(finishReason),
    )
}

/** The prompt text, then each input image as inline bytes — the order Gemini reads an edit in. */
@OptIn(ExperimentalEncodingApi::class)
private fun userParts(options: ImageCallOptions): List<UserPart> = buildList {
    options.prompt?.let { add(UserPart.Text(it)) }
    options.files.orEmpty().forEach { file ->
        when (file) {
            is ImageFile.Data -> add(
                UserPart.File(
                    data = FileData.Bytes(
                        when (val data = file.data) {
                            is BinaryData.Bytes -> data.value
                            is BinaryData.Base64 -> Base64.decode(data.value)
                        },
                    ),
                    mediaType = file.mediaType,
                ),
            )

            is ImageFile.Url -> throw UnsupportedFunctionalityError(
                functionality = "image URL input",
                message = "Gemini image editing takes input images as inline bytes, " +
                    "not URLs; media type \"${file.mediaType ?: "image/*"}\" at ${file.url} " +
                    "was not passed as inline bytes.",
            )
        }
    }
}

/**
 * The caller's `providerOptions["google"]`, re-addressed to the language-model call.
 *
 * `googleSearch` is consumed (it became a tool), and `responseModalities`/`imageConfig` are
 * replaced rather than forwarded: the first is what makes this an image call at all, and the second
 * merges the caller's config under the neutral `aspectRatio`, which wins on conflict because it is
 * the field the caller set through the specification rather than through an escape hatch.
 */
private fun forwardedOptions(vendor: JsonObject?, options: ImageCallOptions): JsonObject {
    val userImageConfig = vendor?.optObject("imageConfig")
    return buildJsonObject {
        vendor?.forEach { (key, value) ->
            if (key !in CONSUMED_IMAGE_OPTIONS) put(key, value)
        }
        putJsonArray("responseModalities") { add("IMAGE") }
        if (options.aspectRatio != null || userImageConfig != null) {
            putJsonObject("imageConfig") {
                userImageConfig?.forEach { (key, value) -> put(key, value) }
                options.aspectRatio?.let { put("aspectRatio", it) }
            }
        }
    }
}

private val CONSUMED_IMAGE_OPTIONS = setOf("googleSearch", "responseModalities", "imageConfig")

/**
 * Whether a result with no images is worth another attempt.
 *
 * A prompt Gemini blocked is terminal — the same prompt is blocked the same way — so the runtime's
 * empty-result retry must not spend its attempts on it; anything else stays unclassified (`null`) and the
 * runtime decides. This is the provider-independent retryability the reference stamps on the result as
 * `isRetryable`, and it is the value [ImageResult] carries once the specification gives it the field.
 */
internal fun googleImageRetryability(finishReason: FinishReason): Boolean? =
    if (finishReason.unified == FinishReason.Unified.ContentFilter) false else null

/**
 * The language model's token accounting, re-shaped for the image contract.
 *
 * `totalTokens` prefers the vendor's own `totalTokenCount` over the derived sum, because Google's total
 * is authoritative where the two ever disagree — the same reason [ImageUsage.totalTokens] is carried
 * rather than computed everywhere else.
 */
private fun Usage.toImageUsage(): ImageUsage? {
    if (raw == null && inputTokens.total == null && outputTokens.total == null) return null
    return ImageUsage(
        inputTokens = inputTokens.total,
        outputTokens = outputTokens.total,
        raw = raw,
        totalTokens = raw?.optInt("totalTokenCount")
            ?: ((inputTokens.total ?: 0) + (outputTokens.total ?: 0)),
    )
}
