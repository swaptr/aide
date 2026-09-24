package com.sabreware.aide.aisdk.providers.mistral

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optDouble
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.MultipartPart
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.mediaTypeToExtension
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Mistral speech-to-text (Voxtral), natively — not through the OpenAI-compatible path.
 *
 * The native wire is what makes the two camelCase options REACHABLE at all: the compat path never
 * mapped `timestampGranularities` or `contextBias`, so both silently stayed off the wire and every
 * transcript came back untimed and unbiased with nothing to say why. Here they are multipart fields
 * (`timestamp_granularities`, `context_bias`), each ARRAY value appended once per item — a joined
 * string is one value the server does not parse.
 *
 * Mistral documents `language` and `timestampGranularities` as mutually incompatible; the combination
 * is rejected here as [InvalidArgumentError] so the failure is a stable SDK error naming the options,
 * not a vendor 4xx naming neither. (The reference does the same.)
 *
 * The response is JSON with segments timed in SECONDS — no `verbose_json` request knob exists on this
 * endpoint; that flag belongs to the OpenAI-compatible shape the reference (and this port) does not use
 * for Mistral.
 */
@OptIn(ExperimentalEncodingApi::class)
internal class MistralTranscriptionModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : TranscriptionModel {

    override val provider: String = MISTRAL_PROVIDER_ID

    override suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult {
        val vendor = options.providerOptions?.get(MISTRAL_PROVIDER_ID)
        val language = vendor?.optString("language")
        val granularities = vendor?.optArray("timestampGranularities")

        if (language != null && granularities != null) {
            throw InvalidArgumentError(
                "providerOptions.mistral.language cannot be combined with " +
                    "providerOptions.mistral.timestampGranularities",
                "providerOptions",
            )
        }

        val bytes = when (val audio = options.audio) {
            is BinaryData.Bytes -> audio.value
            is BinaryData.Base64 -> Base64.decode(audio.value)
        }

        val parts = buildList {
            add(MultipartPart.Field("model", modelId))
            add(
                MultipartPart.File(
                    field = "file",
                    // Named with a real extension: Mistral infers the container from the name.
                    fileName = "audio.${mediaTypeToExtension(options.mediaType)}",
                    bytes = bytes,
                    contentType = options.mediaType,
                ),
            )
            language?.let { add(MultipartPart.Field("language", it)) }
            vendor?.optDouble("temperature")?.let { add(MultipartPart.Field("temperature", it.toString())) }
            granularities?.forEach {
                add(MultipartPart.Field("timestamp_granularities", it.jsonPrimitive.content))
            }
            vendor?.optBoolean("diarize")?.let { add(MultipartPart.Field("diarize", it.toString())) }
            vendor?.optArray("contextBias")?.forEach {
                add(MultipartPart.Field("context_bias", it.jsonPrimitive.content))
            }
        }

        val result = http.postMultipartParts(
            url = "$baseUrl/audio/transcriptions",
            parts = parts,
            headers = combineHeaders(headers, options.headers),
        )
        val payload = result.value.jsonObject

        val segments = payload["segments"]?.jsonArray.orEmpty().map { it.jsonObject }
        val mapped = segments.map { segment ->
            TranscriptionResult.Segment(
                text = segment["text"]?.jsonPrimitive?.content.orEmpty(),
                startSecond = segment["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                endSecond = segment["end"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            )
        }
        val usage = payload["usage"]?.jsonObject

        // Diarization and confidence have no place in the contract's flat segments; they are the reason
        // a caller enabled `diarize`, so they ride in the metadata rather than being dropped.
        val extraSegments = segments.filter {
            it["type"] != null || it["score"] != null || it["speaker_id"] != null
        }
        val metadata = buildJsonObject {
            usage?.let { u ->
                put(
                    "usage",
                    buildJsonObject {
                        u["prompt_tokens"]?.let { put("promptTokens", it) }
                        u["completion_tokens"]?.let { put("completionTokens", it) }
                        u["total_tokens"]?.let { put("totalTokens", it) }
                        u["prompt_audio_seconds"]?.let { put("promptAudioSeconds", it) }
                        u["request_count"]?.let { put("requestCount", it) }
                    },
                )
            }
            if (extraSegments.isNotEmpty()) {
                put(
                    "segments",
                    buildJsonArray {
                        extraSegments.forEach { segment ->
                            add(
                                buildJsonObject {
                                    segment["text"]?.let { put("text", it) }
                                    segment["start"]?.let { put("startSecond", it) }
                                    segment["end"]?.let { put("endSecond", it) }
                                    segment["type"]?.let { put("type", it) }
                                    segment["score"]?.let { put("score", it) }
                                    segment["speaker_id"]?.let { put("speakerId", it) }
                                },
                            )
                        }
                    },
                )
            }
        }

        return TranscriptionResult(
            text = payload["text"]?.jsonPrimitive?.content.orEmpty(),
            segments = mapped,
            language = payload["language"]?.jsonPrimitive?.takeIf { it.isString }?.content,
            durationInSeconds = usage?.get("prompt_audio_seconds")?.jsonPrimitive?.doubleOrNull
                ?: mapped.lastOrNull()?.endSecond,
            providerMetadata = metadata.takeIf { it.isNotEmpty() }
                ?.let { mapOf(MISTRAL_PROVIDER_ID to it) },
            response = result.modalityResponse(
                modelId = payload["model"]?.jsonPrimitive?.content ?: modelId,
                body = result.value.toString(),
            ),
        )
    }
}
