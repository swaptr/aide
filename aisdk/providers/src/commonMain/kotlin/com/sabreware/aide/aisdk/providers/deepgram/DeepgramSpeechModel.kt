package com.sabreware.aide.aisdk.providers.deepgram

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.SpeechResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optElement
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put

/**
 * The model ids that name a voice FAMILY rather than a voice.
 *
 * Deepgram's real model id is `<family>-<voice>-<language>`, so these are the two ids that have to be
 * composed from the call's `voice` and `language` before they will resolve at all.
 */
private val VOICE_FAMILY_IDS = setOf("aura", "aura-2")

/** Every encoding whose name may prefix a format like `linear16_24000`. */
private val ENCODINGS = setOf("linear16", "mulaw", "alaw", "mp3", "opus", "flac", "aac")

/** Encodings that take no container: the parameter is rejected rather than ignored. */
private val CONTAINERLESS = setOf("mp3", "flac", "aac")

/** Encodings whose sample rate is fixed by the codec, so sending one is an error. */
private val FIXED_RATE = setOf("mp3", "opus", "aac")

/** Encodings with no bitrate to set — lossless or uncompressed. */
private val NO_BIT_RATE = setOf("linear16", "mulaw", "alaw", "flac")

private val SAMPLE_RATES = mapOf(
    "linear16" to setOf(8_000, 16_000, 24_000, 32_000, 48_000),
    "mulaw" to setOf(8_000, 16_000),
    "alaw" to setOf(8_000, 16_000),
    "flac" to setOf(8_000, 16_000, 22_050, 32_000, 48_000),
)

/**
 * The contract's short format names expanded into the encoding/container pair Deepgram accepts.
 *
 * The two have to agree — `mp3` with a container is rejected, `opus` outside `ogg` is rejected — so a
 * name is expanded into both rather than passed through as one.
 */
private val OUTPUT_FORMATS = mapOf(
    "mp3" to ("mp3" to null),
    "wav" to ("linear16" to "wav"),
    "linear16" to ("linear16" to "wav"),
    "mulaw" to ("mulaw" to "wav"),
    "alaw" to ("alaw" to "wav"),
    "opus" to ("opus" to "ogg"),
    "ogg" to ("opus" to "ogg"),
    "flac" to ("flac" to null),
    "aac" to ("aac" to null),
    "pcm" to ("linear16" to "none"),
)

/**
 * Deepgram text-to-speech.
 *
 * Everything except the text itself is a QUERY parameter, and the parameters constrain each other: the
 * encoding decides whether a container, a sample rate and a bitrate are legal at all. Sending an
 * incompatible pair is a 400 naming one parameter, which reads as that parameter being wrong rather
 * than as the combination being wrong — so each is validated here against the encoding actually in play.
 */
internal class DeepgramSpeechModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val apiKey: String,
) : SpeechModel {

    override val provider: String = DEEPGRAM_PROVIDER_ID

    override suspend fun doGenerate(options: SpeechCallOptions): SpeechResult {
        val warnings = mutableListOf<Warning>()
        val vendor = options.providerOptions?.forProvider(DEEPGRAM_PROVIDER_ID)
        val query = linkedMapOf<String, String>()

        val upstreamModelId = resolveModelId(options, warnings)
        query["model"] = upstreamModelId

        applyOutputFormat(options.outputFormat ?: DEFAULT_FORMAT, query)
        vendor?.let { applyVendorOptions(it, query, warnings) }

        // The voice is part of the model id for a fully-qualified model, so a `voice` alongside one has
        // nowhere to go — and silently dropping it returns a different speaker than the caller asked for.
        if (upstreamModelId == modelId && options.voice != null && options.voice != modelId) {
            warnings += Warning.Unsupported(
                feature = "voice",
                details = "Deepgram TTS models embed the voice in the model ID. The voice parameter " +
                    "\"${options.voice}\" was ignored. Use the model ID to select a voice " +
                    "(e.g., \"aura-2-helena-en\").",
            )
        }
        // Not validated here: Deepgram accepts it for some languages and rejects it for others, and the
        // vendor's own message names the language, which a local range check could not.
        options.speed?.let { query["speed"] = it.toString() }
        if (upstreamModelId == modelId && options.language != null) {
            warnings += Warning.Unsupported(
                feature = "language",
                details = "Deepgram TTS models are language-specific via the model ID. Language " +
                    "parameter \"${options.language}\" was ignored. Select a model with the " +
                    "appropriate language suffix (e.g., \"-en\" for English).",
            )
        }
        if (options.instructions != null) {
            warnings += Warning.Unsupported(
                feature = "instructions",
                details = "Deepgram TTS REST API does not support instructions. " +
                    "Instructions parameter was ignored.",
            )
        }

        val body = buildJsonObject { put("text", options.text) }
        val result = http.postBytesForBytes(
            url = "$baseUrl/speak?" +
                query.entries.joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" },
            body = body,
            headers = combineHeaders(mapOf("Authorization" to "Token $apiKey"), options.headers),
        )
        if (result.value.isEmpty()) throw NoContentGeneratedError("Deepgram returned no audio.")

        return SpeechResult(
            audio = BinaryData.Bytes(result.value),
            warnings = warnings,
            request = result.requestInfo(),
            // Deepgram reports what it billed and which model actually ran in the response HEADERS, so
            // this is the only place a caller can reconcile a charge against a request.
            providerMetadata = usageMetadata(result.headers),
            response = result.modalityResponse(modelId = modelId),
        )
    }

    /**
     * The real model id, composed from the voice and language where the caller named only a family.
     *
     * A bare `aura-2` resolves to nothing at Deepgram, so refusing here is the difference between a
     * message naming the missing option and a 400 naming a model the caller never typed.
     */
    private fun resolveModelId(options: SpeechCallOptions, warnings: MutableList<Warning>): String {
        if (modelId !in VOICE_FAMILY_IDS) return modelId
        val voice = options.voice?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw InvalidArgumentError(
                message = "Deepgram speech model \"$modelId\" requires a `voice` to be set " +
                    "(e.g. voice: 'thalia').",
                argument = "voice",
            )
        if (options.language == "auto") {
            warnings += Warning.Compatibility(
                feature = "language",
                details = "Deepgram TTS models do not support automatic language detection. " +
                    "Language \"en\" was used instead.",
            )
        }
        val language = options.language?.takeIf { it != "auto" } ?: "en"
        return "$modelId-$voice-$language"
    }

    private fun applyOutputFormat(outputFormat: String, query: MutableMap<String, String>) {
        val name = outputFormat.lowercase()
        OUTPUT_FORMATS[name]?.let { (encoding, container) ->
            query["encoding"] = encoding
            container?.let { query["container"] = it }
            return
        }
        // `linear16_24000` and `wav_44100`: the rate rides on the name, and looking the whole string up
        // as a key drops it, returning audio at a rate the caller did not ask for.
        val parts = name.split('_')
        if (parts.size < 2) return
        val rate = parts[1].toIntOrNull()
        when (val first = parts[0]) {
            in ENCODINGS -> {
                query["encoding"] = first
                when {
                    first in setOf("linear16", "mulaw", "alaw") -> query["container"] = "wav"
                    first == "opus" -> query["container"] = "ogg"
                }
                if (rate != null && rate in SAMPLE_RATES[first].orEmpty()) {
                    query["sample_rate"] = rate.toString()
                }
            }

            "wav" -> {
                query["container"] = "wav"
                query["encoding"] = "linear16"
                rate?.let { query["sample_rate"] = it.toString() }
            }

            "ogg" -> {
                query["container"] = "ogg"
                query["encoding"] = "opus"
                rate?.let { query["sample_rate"] = it.toString() }
            }
        }
    }

    /**
     * The caller's own encoding, container, rate and bitrate — each dropped where the encoding forbids it.
     *
     * Overriding the encoding invalidates whatever the output format put alongside it, which is why the
     * incompatible members are removed here rather than left to collide at the vendor.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun applyVendorOptions(
        vendor: JsonObject,
        query: MutableMap<String, String>,
        warnings: MutableList<Warning>,
    ) {
        val requestedEncoding = vendor.optString("encoding")?.lowercase()
        val requestedContainer = vendor.optString("container")?.lowercase()

        if (requestedEncoding != null) {
            query["encoding"] = requestedEncoding
            applyContainer(requestedEncoding, requestedContainer, query, warnings)
            if (requestedEncoding in FIXED_RATE) query.remove("sample_rate")
            if (requestedEncoding in NO_BIT_RATE) query.remove("bit_rate")
        } else if (requestedContainer != null) {
            val previous = query["encoding"]?.lowercase()
            val implied = when (requestedContainer) {
                "wav" -> "linear16"
                "ogg" -> "opus"
                "none" -> "linear16"
                else -> null
            }
            query["container"] = requestedContainer
            if (implied != null && implied != previous) {
                query["encoding"] = implied
                if (implied in FIXED_RATE) query.remove("sample_rate")
                if (implied in NO_BIT_RATE) query.remove("bit_rate")
            }
        }

        val encoding = query["encoding"]?.lowercase().orEmpty()
        vendor.optInt("sampleRate")?.let { rate -> applySampleRate(encoding, rate, query, warnings) }
        vendor.optElement("bitRate")?.let { element ->
            val bitRate = (element as? JsonPrimitive)?.content
            if (bitRate != null) applyBitRate(encoding, bitRate, query, warnings)
        }

        vendor.optString("callback")?.let { query["callback"] = it }
        vendor.optString("callbackMethod")?.let { query["callback_method"] = it }
        vendor.optBoolean("mipOptOut")?.let { query["mip_opt_out"] = it.toString() }
        // Deepgram reads a repeated tag as comma-separated, which is also what its own `String(value)`
        // produces for an array.
        when (val tag = vendor.optElement("tag")) {
            is JsonArray -> query["tag"] = tag.joinToString(",") { (it as? JsonPrimitive)?.content.orEmpty() }
            is JsonPrimitive -> query["tag"] = tag.content
            else -> Unit
        }
    }

    private fun applyContainer(
        encoding: String,
        container: String?,
        query: MutableMap<String, String>,
        warnings: MutableList<Warning>,
    ) {
        if (container == null) {
            when {
                encoding in CONTAINERLESS -> query.remove("container")
                encoding == "opus" -> query["container"] = "ogg"
                encoding in setOf("linear16", "mulaw", "alaw") ->
                    query.getOrPut("container") { "wav" }
            }
            return
        }
        when {
            encoding in setOf("linear16", "mulaw", "alaw") ->
                if (container in setOf("wav", "none")) {
                    query["container"] = container
                } else {
                    warnings += Warning.Unsupported(
                        feature = "providerOptions",
                        details = "Encoding \"$encoding\" only supports containers \"wav\" or " +
                            "\"none\". Container \"$container\" was ignored.",
                    )
                }

            encoding == "opus" -> query["container"] = "ogg"

            encoding in CONTAINERLESS -> {
                warnings += Warning.Unsupported(
                    feature = "providerOptions",
                    details = "Encoding \"$encoding\" does not support container parameter. " +
                        "Container \"$container\" was ignored.",
                )
                query.remove("container")
            }
        }
    }

    private fun applySampleRate(
        encoding: String,
        rate: Int,
        query: MutableMap<String, String>,
        warnings: MutableList<Warning>,
    ) {
        val allowed = SAMPLE_RATES[encoding]
        when {
            allowed != null && rate !in allowed -> warnings += Warning.Unsupported(
                feature = "providerOptions",
                details = "Encoding \"$encoding\" only supports sample rates: " +
                    "${allowed.joinToString(", ")}. Sample rate $rate was ignored.",
            )

            encoding in FIXED_RATE -> warnings += Warning.Unsupported(
                feature = "providerOptions",
                details = "Encoding \"$encoding\" has a fixed sample rate and does not support " +
                    "sample_rate parameter. Sample rate $rate was ignored.",
            )

            else -> query["sample_rate"] = rate.toString()
        }
    }

    private fun applyBitRate(
        encoding: String,
        bitRate: String,
        query: MutableMap<String, String>,
        warnings: MutableList<Warning>,
    ) {
        val value = bitRate.toDoubleOrNull()
        val rejection = when {
            encoding == "mp3" && value?.toInt() !in setOf(32_000, 48_000) ->
                "Encoding \"mp3\" only supports bit rates: 32000, 48000. Bit rate $bitRate was ignored."

            encoding == "opus" && (value == null || value < 4_000 || value > 650_000) ->
                "Encoding \"opus\" supports bit rates between 4000 and 650000. " +
                    "Bit rate $bitRate was ignored."

            encoding == "aac" && (value == null || value < 4_000 || value > 192_000) ->
                "Encoding \"aac\" supports bit rates between 4000 and 192000. " +
                    "Bit rate $bitRate was ignored."

            encoding in NO_BIT_RATE ->
                "Encoding \"$encoding\" does not support bit_rate parameter. " +
                    "Bit rate $bitRate was ignored."

            else -> null
        }
        if (rejection != null) {
            warnings += Warning.Unsupported(feature = "providerOptions", details = rejection)
        } else {
            query["bit_rate"] = bitRate
        }
    }

    /**
     * What Deepgram billed and which model it ran, read off the response headers.
     *
     * `dg-project-id` is deliberately excluded: it identifies the account, not the request, and this map
     * is handed back to whatever is logging.
     */
    private fun usageMetadata(headers: Map<String, String>): Map<String, JsonObject>? {
        val payload = buildJsonObject {
            headers["dg-model-name"]?.let { put("modelName", it) }
            headers["dg-model-uuid"]?.let { put("modelUuid", it) }
            // A LIST, not the header's raw text. Deepgram comma-joins the uuids of the extra models a
            // request fell back to, and handing the string through makes a consumer re-split a field
            // whose separator this library already knows — one that would break the day a uuid contains
            // one. The reference records the parsed array.
            headers["dg-additional-model-uuids"]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.let { uuids -> putJsonArray("additionalModelUuids") { uuids.forEach { add(it) } } }
            // Counts, so integers. A `Double` serializes as `69.0`, which is a billed character count
            // that no longer compares equal to the one on the invoice.
            headers["dg-char-count"]?.toIntOrNull()?.let { put("charCount", it) }
            headers["dg-breaks-applied"]?.toIntOrNull()?.let { put("breaksApplied", it) }
            headers["dg-pronunciations-applied"]?.toIntOrNull()
                ?.let { put("pronunciationsApplied", it) }
            headers["dg-pronunciation-warnings"]?.let { put("pronunciationWarnings", it) }
            headers["dg-request-id"]?.let { put("requestId", it) }
        }
        return if (payload.isEmpty()) null else mapOf(DEEPGRAM_PROVIDER_ID to payload)
    }

    private companion object {
        const val DEFAULT_FORMAT = "mp3"
    }
}
