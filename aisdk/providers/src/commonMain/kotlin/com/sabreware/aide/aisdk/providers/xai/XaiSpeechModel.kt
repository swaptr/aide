package com.sabreware.aide.aisdk.providers.xai

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.SpeechResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optElement
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * xAI text-to-speech.
 *
 * Two response shapes behind one request: `/tts` normally answers with raw audio, but with
 * `with_timestamps` it answers with a JSON envelope carrying base64 audio and per-character timings
 * instead. A client that reads bytes unconditionally hands the caller a JSON document as if it were an
 * mp3, which plays as silence.
 */
@OptIn(ExperimentalEncodingApi::class)
internal class XaiSpeechModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : SpeechModel {

    override val provider: String = XAI_PROVIDER_ID

    override suspend fun doGenerate(options: SpeechCallOptions): SpeechResult {
        val warnings = mutableListOf<Warning>()
        val vendor = options.providerOptions?.forProvider(XAI_PROVIDER_ID)
        val withTimestamps = vendor?.optBoolean("withTimestamps") == true

        val codec = options.outputFormat?.lowercase()?.let { requested ->
            if (requested in SUPPORTED_CODECS) {
                requested
            } else {
                warnings += Warning.Unsupported(
                    feature = "outputFormat",
                    details = "Unsupported output format: ${options.outputFormat}. Using mp3 instead.",
                )
                DEFAULT_CODEC
            }
        } ?: DEFAULT_CODEC

        if (options.instructions != null) {
            warnings += Warning.Unsupported(
                feature = "instructions",
                details = "xAI speech models do not support the `instructions` option. " +
                    "Use xAI speech tags in `text` to control delivery.",
            )
        }

        val body = buildJsonObject {
            put("text", options.text)
            // xAI has no server-side default voice; omitting it is a 400 rather than a house voice.
            put("voice_id", options.voice ?: DEFAULT_VOICE)
            // `auto` is xAI's own detect sentinel, so an unset language still names a value.
            put("language", options.language ?: "auto")
            put("output_format", outputFormat(codec, vendor, warnings))
            options.speed?.let { put("speed", it) }
            vendor?.optInt("optimizeStreamingLatency")?.let { put("optimize_streaming_latency", it) }
            vendor?.optBoolean("textNormalization")?.let { put("text_normalization", it) }
            if (withTimestamps) put("with_timestamps", true)
            vendor?.optObject("replace")?.let { put("replace", it) }
        }

        val result = http.postBytesForBytes(
            url = "$baseUrl/tts",
            body = body,
            headers = combineHeaders(headers, options.headers),
        )
        val envelope = if (withTimestamps) parseJsonObject(result.value.decodeToString()) else null
        val audio = when {
            envelope == null -> result.value
            else -> envelope["audio"]?.jsonPrimitive?.content?.let(Base64::decode)
                ?: throw NoContentGeneratedError("xAI returned a timestamps envelope with no audio")
        }
        if (audio.isEmpty()) throw NoContentGeneratedError("xAI returned no audio.")

        return SpeechResult(
            audio = BinaryData.Bytes(audio),
            warnings = warnings,
            request = result.requestInfo(),
            // The trace id is on every response, success or failure, and is what a support ticket is
            // answered against — so it is namespaced rather than left in the raw headers.
            providerMetadata = metadata(result.headers["x-trace-id"], envelope),
            response = result.modalityResponse(modelId = modelId),
        )
    }

    /**
     * `output_format` is an object, not the format string the contract speaks.
     *
     * `bit_rate` is mp3-only: xAI rejects it outright on the PCM-shaped codecs, so a caller who set it
     * once and then changed container would get a 400 on a request that looks unrelated to the change.
     */
    private fun outputFormat(
        codec: String,
        vendor: JsonObject?,
        warnings: MutableList<Warning>,
    ): JsonObject = buildJsonObject {
        put("codec", codec)
        vendor?.optInt("sampleRate")?.let { put("sample_rate", it) }
        vendor?.optInt("bitRate")?.let { bitRate ->
            if (codec == "mp3") {
                put("bit_rate", bitRate)
            } else {
                warnings += Warning.Unsupported(
                    feature = "providerOptions",
                    details = "xAI `bitRate` is supported only for mp3 output. It was ignored.",
                )
            }
        }
    }

    private fun metadata(traceId: String?, envelope: JsonObject?): ProviderMetadata = mapOf(
        XAI_PROVIDER_ID to buildJsonObject {
            traceId?.let { put("traceId", it) }
            envelope?.get("duration")?.let { put("duration", it) }
            envelope?.get("content_type")?.let { put("contentType", it) }
            envelope?.optObject("audio_timestamps")?.let { timestamps ->
                put(
                    "audioTimestamps",
                    buildJsonObject {
                        timestamps.optElement("graph_chars")?.let { put("graphChars", it) }
                        timestamps.optElement("graph_times")?.let { put("graphTimes", it) }
                    },
                )
            }
        },
    )

    private companion object {
        const val DEFAULT_CODEC = "mp3"
        const val DEFAULT_VOICE = "eve"
        val SUPPORTED_CODECS = setOf("mp3", "wav", "pcm", "mulaw", "alaw")
    }
}
