package com.sabreware.aide.aisdk.providers.fishaudio

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.SpeechResult
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optDouble
import com.sabreware.aide.aisdk.providers.options.optElement
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.MediaType
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The provider id, and the namespace Fish Audio payloads file under. */
public const val FISH_AUDIO_PROVIDER_ID: String = "fish-audio"

/**
 * The key Fish Audio's own `providerOptions` and `providerMetadata` entries live under.
 *
 * Deliberately NOT [FISH_AUDIO_PROVIDER_ID]: the reference namespaces this vendor's options as
 * `fishAudio`, and options are an interop surface — a caller porting a call across implementations must
 * not have to rename the key.
 */
public const val FISH_AUDIO_OPTIONS_KEY: String = "fishAudio"

/**
 * Fish Audio: text-to-speech and speech-to-text.
 *
 * The voice here is a `reference_id` — a cloned-voice handle rather than a name from a fixed catalogue —
 * which is why there is no sensible built-in default and the field is simply omitted when unset. The TTS
 * model is selected by an HTTP header rather than a body field.
 */
public class FishAudioProvider internal constructor(
    private val http: ProviderHttp,
    apiKey: String,
    baseUrl: String,
    extraHeaders: Map<String, String>,
) : Provider {

    public constructor(
        client: HttpClient,
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        headers: Map<String, String> = emptyMap(),
    ) : this(ProviderHttp(client), apiKey, baseUrl, headers)

    override val providerId: String = FISH_AUDIO_PROVIDER_ID

    private val endpoint = baseUrl.trimEnd('/')

    private val headers = combineHeaders(mapOf("Authorization" to "Bearer $apiKey"), extraHeaders)

    override fun speechModel(modelId: String): SpeechModel =
        FishAudioSpeechModel(modelId, http, endpoint, headers)

    override fun transcriptionModel(modelId: String): TranscriptionModel =
        FishAudioTranscriptionModel(modelId, http, endpoint, headers)

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.fish.audio"
        public val SUPPORTED_FORMATS: Set<String> = setOf("wav", "pcm", "mp3", "opus")
    }
}

internal class FishAudioSpeechModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : SpeechModel {

    override val provider: String = FISH_AUDIO_PROVIDER_ID

    override suspend fun doGenerate(options: SpeechCallOptions): SpeechResult {
        val warnings = mutableListOf<Warning>()
        val vendor = options.providerOptions?.forProvider(FISH_AUDIO_OPTIONS_KEY)

        if (options.instructions != null) {
            warnings += Warning.Unsupported(
                feature = "instructions",
                details = "Fish Audio does not support instructions. The instructions option was ignored.",
            )
        }
        if (options.language != null) {
            warnings += Warning.Unsupported(
                feature = "language",
                details = "Fish Audio infers the language from the input text and the selected voice, " +
                    "and has no language parameter. The language option was ignored.",
            )
        }

        val format = options.outputFormat?.lowercase()?.let { requested ->
            if (requested in FishAudioProvider.SUPPORTED_FORMATS) {
                requested
            } else {
                warnings += Warning.Unsupported(
                    feature = "outputFormat",
                    details = "Fish Audio does not support the output format " +
                        "\"${options.outputFormat}\". Falling back to mp3. " +
                        "Supported formats are wav, pcm, mp3, opus.",
                )
                DEFAULT_FORMAT
            }
        } ?: DEFAULT_FORMAT

        val prosody = buildJsonObject {
            options.speed?.let { speed ->
                if (speed in SPEED_RANGE) {
                    put("speed", speed)
                } else {
                    warnings += Warning.Unsupported(
                        feature = "speed",
                        details = "Fish Audio speed must be between 0.5 and 2. " +
                            "The speed option was ignored.",
                    )
                }
            }
            vendor?.optDouble("volume")?.let { put("volume", it) }
            vendor?.optBoolean("normalizeLoudness")?.let { normalize ->
                if (modelId == "s1") {
                    // s1 accepts the field and does nothing with it, which is indistinguishable from
                    // working unless you measure the output.
                    warnings += Warning.Unsupported(
                        feature = "providerOptions.fishAudio.normalizeLoudness",
                        details = "Fish Audio ignores normalizeLoudness on s1. " +
                            "It is supported by the S2 family (s2-pro, s2.1-pro).",
                    )
                } else {
                    put("normalize_loudness", normalize)
                }
            }
        }

        val body = buildJsonObject {
            put("text", options.text)
            put("format", format)
            // A cloned-voice handle rather than a catalogue name, so there is no default worth
            // inventing. The vendor option wins so a multi-speaker array is expressible at all.
            (vendor?.optElement("referenceId") ?: options.voice?.let(::JsonPrimitive))
                ?.let { put("reference_id", it) }
            if (prosody.isNotEmpty()) put("prosody", prosody)
            vendor?.let { putVendorFields(it, format, warnings) }
        }

        val result = http.postBytesForBytes(
            url = "$baseUrl/v1/tts",
            body = body,
            // The model is chosen by header here, not by a body field. Caller headers come last so a
            // caller can point one call at another model without a second provider.
            headers = combineHeaders(headers, mapOf("model" to modelId), options.headers),
        )
        if (result.value.isEmpty()) throw NoContentGeneratedError("Fish Audio returned no audio.")

        return SpeechResult(
            audio = BinaryData.Bytes(result.value),
            warnings = warnings,
            request = result.requestInfo(),
            response = result.modalityResponse(modelId = modelId),
        )
    }

    private fun JsonObjectBuilder.putVendorFields(
        vendor: JsonObject,
        format: String,
        warnings: MutableList<Warning>,
    ) {
        vendor.optInt("sampleRate")?.let { put("sample_rate", it) }
        vendor.optInt("mp3Bitrate")?.let { bitrate ->
            if (format == "mp3") {
                put("mp3_bitrate", bitrate)
            } else {
                warnings += Warning.Unsupported(
                    feature = "providerOptions.fishAudio.mp3Bitrate",
                    details = "mp3Bitrate only applies to mp3 output. " +
                        "The option was ignored for $format output.",
                )
            }
        }
        vendor.optInt("opusBitrate")?.let { bitrate ->
            if (format == "opus") {
                put("opus_bitrate", bitrate)
            } else {
                warnings += Warning.Unsupported(
                    feature = "providerOptions.fishAudio.opusBitrate",
                    details = "opusBitrate only applies to opus output. " +
                        "The option was ignored for $format output.",
                )
            }
        }
        vendor.optString("latency")?.let { put("latency", it) }
        vendor.optDouble("temperature")?.let { put("temperature", it) }
        vendor.optDouble("topP")?.let { put("top_p", it) }
        vendor.optInt("chunkLength")?.let { put("chunk_length", it) }
        vendor.optInt("minChunkLength")?.let { put("min_chunk_length", it) }
        vendor.optBoolean("normalize")?.let { put("normalize", it) }
        vendor.optInt("maxNewTokens")?.let { put("max_new_tokens", it) }
        vendor.optDouble("repetitionPenalty")?.let { put("repetition_penalty", it) }
        vendor.optBoolean("conditionOnPreviousChunks")?.let { put("condition_on_previous_chunks", it) }
        vendor.optDouble("earlyStopThreshold")?.let { put("early_stop_threshold", it) }
        vendor.optArray("features")?.let { put("features", it) }
    }

    private companion object {
        const val DEFAULT_FORMAT = "mp3"

        /** Fish Audio's documented `prosody.speed` range. */
        val SPEED_RANGE = 0.5..2.0
    }
}

@OptIn(ExperimentalEncodingApi::class)
internal class FishAudioTranscriptionModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : TranscriptionModel {

    override val provider: String = FISH_AUDIO_PROVIDER_ID

    override suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult {
        val vendor = options.providerOptions?.forProvider(FISH_AUDIO_OPTIONS_KEY)
        val bytes = when (val audio = options.audio) {
            is BinaryData.Bytes -> audio.value
            is BinaryData.Base64 -> Base64.decode(audio.value)
        }

        val fields = buildList {
            vendor?.optString("language")?.let { add("language" to it) }
            // Fish Audio defaults this to true, and with it true the response carries no segments at
            // all — so a contract field that looks supported returns nothing on every call. Ask for
            // timestamps by default and let a caller trade them back for the latency.
            add("ignore_timestamps" to (vendor?.optBoolean("ignoreTimestamps") ?: false).toString())
        }

        val result = http.postMultipart(
            url = "$baseUrl/v1/asr",
            fileField = "audio",
            // Sniffed rather than assumed: the caller's declared type can be absent or wrong, and the
            // extension is what several servers key their decoder off.
            fileName = "audio.${MediaType.detectOr(bytes, options.mediaType).substringAfter('/')}",
            fileBytes = bytes,
            fileContentType = options.mediaType,
            fields = fields,
            headers = combineHeaders(headers, options.headers),
        )
        val response = result.value.jsonObject

        return TranscriptionResult(
            text = response["text"]?.jsonPrimitive?.content.orEmpty(),
            segments = response["segments"]?.jsonArray.orEmpty().map { entry ->
                val segment = entry.jsonObject
                TranscriptionResult.Segment(
                    text = segment["text"]?.jsonPrimitive?.content.orEmpty(),
                    startSecond = segment["start"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    endSecond = segment["end"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                )
            },
            // `language_code` is the ISO-639-1 code and reports what was DETECTED, which is not
            // necessarily what was requested — Fish Audio's detection overrides the hint.
            language = response["language_code"]?.jsonPrimitive?.content,
            durationInSeconds = response["duration"]?.jsonPrimitive?.content?.toDoubleOrNull(),
            request = result.requestInfo(),
            providerMetadata = response["language"]?.jsonPrimitive?.content?.let { name ->
                // The display name, e.g. `English`. Its exact form is not guaranteed, so `language`
                // above is the value to branch on and this one is for showing a user.
                mapOf(FISH_AUDIO_OPTIONS_KEY to buildJsonObject { put("language", name) })
            },
            response = result.modalityResponse(modelId = modelId),
        )
    }
}
