package com.sabreware.aide.aisdk.providers.openaicompatible

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.EmbeddingResult
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.ImageResult
import com.sabreware.aide.aisdk.ImageUsage
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.SpeechResult
import com.sabreware.aide.aisdk.TooManyEmbeddingValuesForCallError
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.providers.media.toDataUri
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Text embeddings over the OpenAI-compatible wire.
 *
 * [maxPerCall] is a constructor value rather than a probe: no server reports its own limit, and the
 * failure when it is exceeded is a 400 that names a token count rather than the actual problem.
 */
internal class OpenAICompatibleEmbeddingModel(
    override val provider: String,
    override val modelId: String,
    private val http: ProviderHttp,
    /** The complete `/embeddings` endpoint: Azure and Perplexity both build it from more than a base. */
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val maxPerCall: Int = DEFAULT_MAX_PER_CALL,
) : EmbeddingModel {

    override suspend fun maxEmbeddingsPerCall(): Int = maxPerCall

    override suspend fun doEmbed(options: EmbeddingCallOptions): EmbeddingResult {
        if (options.values.size > maxPerCall) {
            // Caught here so the caller learns the ceiling, rather than from a 400 about token counts.
            throw TooManyEmbeddingValuesForCallError(provider, modelId, maxPerCall, options.values)
        }
        val body = buildJsonObject {
            put("model", modelId)
            put("input", buildJsonArray { options.values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            // Asked for explicitly, not left to the server. OpenAI defaults to float so this is invisible
            // there, but a compatible host that defaults to base64 answers with strings where the parser
            // below reads numbers — a wrong-shaped response from a request that looked fine.
            put("encoding_format", "float")
        }.withModalityOptions(options.providerOptions?.get(provider))
        val result = http.postJson(url, body, combineHeaders(headers, options.headers))
        val response = result.value.jsonObject

        val embeddings = response["data"]?.jsonArray.orEmpty()
            // Servers do not promise ordering, and an embedding matched to the wrong input is worse than
            // an error because nothing detects it.
            .sortedBy { it.jsonObject["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }
            .map { entry ->
                entry.jsonObject["embedding"]?.jsonArray?.map { it.jsonPrimitive.content.toDouble() }.orEmpty()
            }

        return EmbeddingResult(
            embeddings = embeddings,
            usage = response["usage"]?.jsonObject?.get("prompt_tokens")?.jsonPrimitive?.content?.toIntOrNull(),
            response = result.modalityResponse(modelId = modelId),
        )
    }

    private companion object {
        // OpenAI's documented ceiling; hosts that allow more can be told so.
        const val DEFAULT_MAX_PER_CALL = 2048
    }
}

/** How a vendor's `/images/generations` body departs from OpenAI's. */
public enum class ImageRequestDialect {
    /** The OpenAI shape: `prompt`, `n`, `size`, `response_format`. No image inputs. */
    OpenAI,

    /**
     * ByteDance's Ark: no `n` field, image-to-image via `image` (a data URI, or an array of them),
     * camelCase option names rewritten into its nested option objects, and `response_format`
     * pinned to `b64_json` because the API does not let a caller choose.
     */
    ByteDance,
}

/** Image generation over `/images/generations`. */
internal class OpenAICompatibleImageModel(
    override val provider: String,
    override val modelId: String,
    private val http: ProviderHttp,
    /** The complete `/images/generations` endpoint. */
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val dialect: ImageRequestDialect = ImageRequestDialect.OpenAI,
) : ImageModel {

    /**
     * Whether this model tolerates `response_format`.
     *
     * The gpt-image and chatgpt-image families return base64 unconditionally and reject the parameter,
     * so asking for what they already do is a 400 on every generation. Derived from the id because the
     * caller has no way to know, and because the failure is a rejected request rather than a field that
     * quietly goes missing.
     */
    private val acceptsResponseFormat: Boolean
        get() = !modelId.lowercase().let { it.contains("gpt-image") || it.contains("chatgpt-image") }

    override suspend fun doGenerate(options: ImageCallOptions): ImageResult {
        val warnings = mutableListOf<Warning>()
        if (options.aspectRatio != null) {
            // OpenAI takes an explicit size; a ratio has no wire field and silently ignoring it produces
            // a correctly-generated image of the wrong shape.
            warnings += Warning.Unsupported("aspectRatio", "This wire takes an explicit size instead.")
        }
        if (options.seed != null) warnings += Warning.Unsupported("seed")

        val body = when (dialect) {
            ImageRequestDialect.OpenAI -> openAiBody(options, warnings)
            ImageRequestDialect.ByteDance -> byteDanceBody(options, warnings)
        }
        val result = http.postJson(url, body, combineHeaders(headers, options.headers))
        val response = result.value.jsonObject

        val images = response["data"]?.jsonArray.orEmpty().mapNotNull { entry ->
            val obj = entry.jsonObject
            obj["b64_json"]?.jsonPrimitive?.content?.let { BinaryData.Base64(it) }
                // A URL is NOT base64, and wrapping one as if it were hands every downstream decoder
                // garbage instead of an error. xAI returns a URL as its normal shape, as do DALL-E 2
                // and 3, so this is reachable today rather than theoretical: fetch the bytes.
                ?: obj["url"]?.jsonPrimitive?.content?.let { imageUrl ->
                    BinaryData.Bytes(http.getBytes(imageUrl, trustedOrigin = url).value)
                }
        }
        if (images.isEmpty()) throw NoContentGeneratedError("The image endpoint returned no images.")

        return ImageResult(
            images = images,
            warnings = warnings,
            usage = response["usage"]?.jsonObject?.let { usage ->
                ImageUsage(
                    inputTokens = usage["input_tokens"]?.jsonPrimitive?.content?.toIntOrNull(),
                    outputTokens = usage["output_tokens"]?.jsonPrimitive?.content?.toIntOrNull(),
                    // Read rather than derived: vendors bill a total that is not input plus output, which
                    // is the whole reason the field exists separately from them.
                    totalTokens = usage["total_tokens"]?.jsonPrimitive?.content?.toIntOrNull(),
                )
            },
            response = result.modalityResponse(modelId = modelId),
        )
    }

    private fun openAiBody(options: ImageCallOptions, warnings: MutableList<Warning>): JsonObject {
        // Silence here was an edit call that quietly ran as text-to-image.
        if (!options.files.isNullOrEmpty()) {
            warnings += Warning.Unsupported("files", "/images/generations takes no input images; an edit endpoint would.")
        }
        if (options.mask != null) warnings += Warning.Unsupported("mask", "This wire has no mask input.")
        return buildJsonObject {
            put("model", modelId)
            options.prompt?.let { put("prompt", it) }
            put("n", options.n)
            options.size?.let { put("size", it) }
            // Ask for bytes rather than a URL: a generated-image URL expires, and a caller that stored it
            // finds a broken image later rather than an error now. The gpt-image and chatgpt-image
            // families are the exception twice over — they return base64 already AND reject the
            // parameter, so sending it turned every generation on those models into a 400.
            if (acceptsResponseFormat) put("response_format", "b64_json")
        }.withModalityOptions(options.providerOptions?.get(provider))
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun byteDanceBody(options: ImageCallOptions, warnings: MutableList<Warning>): JsonObject {
        if (options.n > 1) {
            warnings += Warning.Unsupported("n", "ByteDance's image API has no output-count field.")
        }
        if (options.mask != null) {
            warnings += Warning.Unsupported("mask", "ByteDance does not take a separate mask.")
        }
        val vendor = options.providerOptions?.get(provider) ?: JsonObject(emptyMap())
        return buildJsonObject {
            put("model", modelId)
            options.prompt?.let { put("prompt", it) }
            options.size?.let { put("size", it) }
            options.files?.takeIf { it.isNotEmpty() }?.let { files ->
                val uris = files.map { it.toDataUri() }
                // One file is a bare string, several are an array — the wire's own distinction.
                put("image", if (uris.size == 1) JsonPrimitive(uris.single()) else JsonArray(uris.map(::JsonPrimitive)))
            }
            vendor["watermark"]?.let { put("watermark", it) }
            vendor["outputFormat"]?.let { put("output_format", it) }
            // A resolution level ("2K") deliberately overrides the pixel size above.
            vendor["size"]?.let { put("size", it) }
            vendor["sequentialImageGeneration"]?.let { put("sequential_image_generation", it) }
            vendor["maxImages"]?.let {
                put("sequential_image_generation_options", buildJsonObject { put("max_images", it) })
            }
            vendor["optimizePromptMode"]?.let {
                put("optimize_prompt_options", buildJsonObject { put("mode", it) })
            }
            vendor.forEach { (key, value) -> if (key !in BYTEDANCE_HANDLED) put(key, value) }
            // Not caller-overridable: the API returns base64 or nothing useful.
            put("response_format", "b64_json")
        }
    }

    private companion object {
        val BYTEDANCE_HANDLED = setOf(
            "watermark", "outputFormat", "size", "sequentialImageGeneration", "maxImages", "optimizePromptMode",
        )
    }
}

/**
 * Text to speech over `/audio/speech`.
 *
 * The response is raw audio, not JSON, which is why this reaches for the byte transport rather than
 * [ProviderHttp.postJson].
 */
internal class OpenAICompatibleSpeechModel(
    override val provider: String,
    override val modelId: String,
    private val http: ProviderHttp,
    /** The complete `/audio/speech` endpoint. */
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
) : SpeechModel {

    override suspend fun doGenerate(options: SpeechCallOptions): SpeechResult {
        val warnings = mutableListOf<Warning>()
        if (options.language != null) warnings += Warning.Unsupported("language")

        val body = buildJsonObject {
            put("model", modelId)
            put("input", options.text)
            put("voice", options.voice ?: DEFAULT_VOICE)
            options.outputFormat?.let { put("response_format", it) }
            options.speed?.let { put("speed", it) }
            options.instructions?.let { put("instructions", it) }
        }.withModalityOptions(options.providerOptions?.get(provider))

        val result = http.postBytesForBytes(url, body, combineHeaders(headers, options.headers))
        if (result.value.isEmpty()) throw NoContentGeneratedError("The speech endpoint returned no audio.")

        return SpeechResult(
            audio = BinaryData.Bytes(result.value),
            warnings = warnings,
            request = result.requestInfo(),
            response = result.modalityResponse(modelId = modelId),
        )
    }

    private companion object {
        // A voice is required; OpenAI documents no default, so a request without one is a 400.
        const val DEFAULT_VOICE = "alloy"
    }
}

/**
 * Speech to text over `/audio/transcriptions`.
 *
 * The only one of these endpoints that is not JSON in: it takes a multipart upload. `verbose_json` is
 * requested rather than the default, because the default returns only the text and drops the segments,
 * language and duration the contract has fields for — and a caller cannot ask for them back afterwards.
 */
@OptIn(ExperimentalEncodingApi::class)
internal class OpenAICompatibleTranscriptionModel(
    override val provider: String,
    override val modelId: String,
    private val http: ProviderHttp,
    /** The complete `/audio/transcriptions` endpoint. */
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
) : TranscriptionModel {

    /**
     * Whether this model accepts `response_format: verbose_json`.
     *
     * Whisper does; the gpt-4o transcribe models reject it. Derived from the id rather than asked of the
     * caller, because a caller has no way to know and the failure is a 400 rather than a missing field.
     */
    private val supportsVerboseJson: Boolean get() = !modelId.lowercase().contains("gpt-4o")

    /** `gpt-4o-transcribe-diarize` labels who spoke, and only with its own format and a chunking strategy. */
    private val isDiarizationModel: Boolean get() = modelId == DIARIZATION_MODEL

    override suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult {
        val bytes = when (val audio = options.audio) {
            is BinaryData.Bytes -> audio.value
            // A base64 payload has to be decoded: multipart carries bytes, and uploading the base64 TEXT
            // produces a confident transcription of noise.
            is BinaryData.Base64 -> Base64.decode(audio.value)
        }

        val result = http.postMultipart(
            url = url,
            fileField = "file",
            // Several servers infer the format from the extension and reject a name without one.
            fileName = "audio.${options.mediaType.substringAfter('/').substringBefore(';')}",
            fileBytes = bytes,
            fileContentType = options.mediaType,
            fields = buildList {
                add("model" to modelId)
                val vendor = options.providerOptions?.get(provider)
                // `verbose_json` is what carries segments, language and duration — but gpt-4o-transcribe
                // and gpt-4o-mini-transcribe REJECT it outright, so asking unconditionally 400s the two
                // newest models in exchange for fields they were never going to return. The diarization
                // model has a format of its own, and a caller's explicit choice wins over all of it.
                val responseFormat = vendor.stringOption("responseFormat") ?: when {
                    isDiarizationModel -> "diarized_json"
                    supportsVerboseJson -> "verbose_json"
                    else -> null
                }
                responseFormat?.let { add("response_format" to it) }
                chunkingStrategy(vendor)?.let { add("chunking_strategy" to it) }
                // Multipart carries no nesting, so a provider option becomes a field of its own rather
                // than a JSON blob. This is the only route `language`, `prompt` and
                // `timestamp_granularities[]` have — the contract carries none of them, deliberately,
                // because each differs per vendor.
                addAll(vendor?.let { JsonObject(it - "responseFormat" - "chunkingStrategy") }.toMultipartFields())
            },
            headers = combineHeaders(headers, options.headers),
        )
        val response = result.value.jsonObject
        // Who said each span, which the contract's segment has no field for: filed under the vendor so
        // a caller that asked the diarization model for speakers can read them back.
        val diarized = response["segments"]?.jsonArray.orEmpty().mapNotNull { entry ->
            val segment = entry.jsonObject
            val speaker = segment["speaker"]?.jsonPrimitive?.content ?: return@mapNotNull null
            buildJsonObject {
                put("text", segment["text"]?.jsonPrimitive?.content.orEmpty())
                put("startSecond", segment["start"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0)
                put("endSecond", segment["end"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0)
                put("speaker", speaker)
            }
        }

        return TranscriptionResult(
            text = response["text"]?.jsonPrimitive?.content.orEmpty(),
            // Whisper returns `segments`; the gpt-4o transcribe models return `words` and no segments
            // at all — which is what the reference's own recorded fixture contains, so reading only
            // `segments` yields an empty timeline against the endpoint most likely to be called.
            segments = response["segments"]?.jsonArray?.map { it.jsonObject.toSegment("text") }
                ?: response["words"]?.jsonArray?.map { it.jsonObject.toSegment("word") }
                ?: emptyList(),
            // The wire says `"english"`; the contract says ISO-639-1. An unrecognised name is dropped
            // rather than passed through, because a caller that switches on a language code cannot tell
            // a code from a name and would silently take the wrong branch.
            language = response["language"]?.jsonPrimitive?.content?.let { ISO_639_1[it.lowercase()] },
            durationInSeconds = response["duration"]?.jsonPrimitive?.content?.toDoubleOrNull(),
            providerMetadata = diarized.takeIf { it.isNotEmpty() }?.let {
                mapOf(provider to buildJsonObject { put("segments", JsonArray(it)) })
            },
            request = result.requestInfo(),
            response = result.modalityResponse(modelId = modelId),
        )
    }

    /**
     * `chunking_strategy`: `auto`, or a `server_vad` object carried as JSON text — the one multipart
     * field OpenAI documents as a serialized object. The diarization model needs one to run at all,
     * so it defaults to `auto` there and to nothing elsewhere.
     */
    private fun chunkingStrategy(vendor: JsonObject?): String? =
        when (val strategy = vendor?.get("chunkingStrategy")) {
            null -> if (isDiarizationModel) "auto" else null
            is JsonPrimitive -> strategy.content
            is JsonObject -> buildJsonObject {
                strategy["type"]?.let { put("type", it) }
                strategy["threshold"]?.let { put("threshold", it) }
                strategy["prefixPaddingMs"]?.let { put("prefix_padding_ms", it) }
                strategy["silenceDurationMs"]?.let { put("silence_duration_ms", it) }
            }.toString()
            else -> null
        }
}

private const val DIARIZATION_MODEL = "gpt-4o-transcribe-diarize"

private fun JsonObject?.stringOption(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * One timed span, whether the server called it a segment or a word.
 *
 * [textKey] is the only difference between the two shapes: `segments[].text` against `words[].word`.
 */
private fun JsonObject.toSegment(textKey: String): TranscriptionResult.Segment =
    TranscriptionResult.Segment(
        text = this[textKey]?.jsonPrimitive?.content.orEmpty(),
        startSecond = this["start"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
        endSecond = this["end"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
    )

/**
 * Whisper's language NAMES, mapped to the codes the contract documents.
 *
 * Ported verbatim from the reference rather than derived: Whisper emits a fixed set of English names,
 * and a general name-to-code library would still have to special-case the ones it spells differently.
 */
private val ISO_639_1: Map<String, String> = mapOf(
    "afrikaans" to "af", "arabic" to "ar", "armenian" to "hy", "azerbaijani" to "az",
    "belarusian" to "be", "bosnian" to "bs", "bulgarian" to "bg", "catalan" to "ca",
    "chinese" to "zh", "croatian" to "hr", "czech" to "cs", "danish" to "da",
    "dutch" to "nl", "english" to "en", "estonian" to "et", "finnish" to "fi",
    "french" to "fr", "galician" to "gl", "german" to "de", "greek" to "el",
    "hebrew" to "he", "hindi" to "hi", "hungarian" to "hu", "icelandic" to "is",
    "indonesian" to "id", "italian" to "it", "japanese" to "ja", "kannada" to "kn",
    "kazakh" to "kk", "korean" to "ko", "latvian" to "lv", "lithuanian" to "lt",
    "macedonian" to "mk", "malay" to "ms", "maori" to "mi", "marathi" to "mr",
    "nepali" to "ne", "norwegian" to "no", "persian" to "fa", "polish" to "pl",
    "portuguese" to "pt", "romanian" to "ro", "russian" to "ru", "serbian" to "sr",
    "slovak" to "sk", "slovenian" to "sl", "spanish" to "es", "swahili" to "sw",
    "swedish" to "sv", "tagalog" to "tl", "tamil" to "ta", "thai" to "th",
    "turkish" to "tr", "ukrainian" to "uk", "urdu" to "ur", "vietnamese" to "vi",
    "welsh" to "cy",
)

/**
 * Spreads the caller's `providerOptions[provider]` into a modality request body.
 *
 * The specification carries no `quality`, `background`, `language` or `input_type` on its call options,
 * and that is the reference's design rather than an omission: those knobs differ per vendor and per
 * model, so they travel namespaced under the provider that understands them. Which means a provider that
 * does not spread them drops every one of them silently — the call succeeds, and the image comes back at
 * the wrong quality with no warning that anything was ignored.
 *
 * The keys carrying the CALL itself are protected, and the protected set differs from the chat model's:
 * a modality body's payload lives under `input` or `prompt` where a chat body's lives under `messages`.
 * One arriving through this door would replace what the caller actually passed, which is a far worse
 * failure than the dropped setting this exists to fix.
 */
private fun JsonObject.withModalityOptions(extra: JsonObject?): JsonObject {
    if (extra.isNullOrEmpty()) return this
    return JsonObject(this + extra.filterKeys { it !in RESERVED_MODALITY_KEYS })
}

private val RESERVED_MODALITY_KEYS = setOf("model", "input", "prompt", "file", "n", "response_format")

/**
 * A provider-options object as repeated multipart fields.
 *
 * An array becomes one field per element under the same name, which is how these endpoints spell a list —
 * `timestamp_granularities[]` sent as a JSON array is accepted and then ignored, so the timings a caller
 * asked for never arrive and nothing reports why.
 */
private fun JsonObject?.toMultipartFields(): List<Pair<String, String>> {
    if (this == null) return emptyList()
    return entries.flatMap { (key, value) ->
        when (value) {
            is JsonArray -> value.map { key to it.jsonPrimitive.content }
            is JsonPrimitive -> listOf(key to value.content)
            // An object has no multipart spelling at all; sending its JSON text would be read as a
            // literal string by every server that parses this form.
            else -> emptyList()
        }
    }
}
