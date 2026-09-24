package com.sabreware.aide.aisdk.providers.mistral

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.SpeechResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.combineHeaders
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Mistral text-to-speech (Voxtral TTS).
 *
 * Two traps for anyone arriving from the OpenAI shape, both silent or confusing rather than loud:
 *
 * - **The voice field is `voice_id`**, not `voice` — the reason the generic OpenAI-compatible speech
 *   path cannot serve this endpoint at all.
 * - **The audio comes back as JSON**, `{"audio_data": "<base64>"}`, not as raw bytes. A port of the
 *   bytes-response shape reads the JSON envelope as an audio file.
 *
 * One-off voice cloning rides `providerOptions.mistral.refAudio` (a base64 clip); when present it
 * REPLACES the voice — Mistral takes one or the other — and the recorded request body redacts it, since
 * a request log that embeds a whole reference recording is its own leak.
 */
internal class MistralSpeechModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : SpeechModel {

    override val provider: String = MISTRAL_PROVIDER_ID

    override suspend fun doGenerate(options: SpeechCallOptions): SpeechResult {
        val warnings = mutableListOf<Warning>()
        val vendor = options.providerOptions?.forProvider(MISTRAL_PROVIDER_ID)
        val refAudio = vendor?.optString("refAudio")

        val format = options.outputFormat?.let { requested ->
            requested.takeIf { it in SUPPORTED_FORMATS } ?: run {
                warnings += Warning.Unsupported(
                    feature = "outputFormat",
                    details = "Unsupported output format: $requested. Using mp3 instead.",
                )
                null
            }
        } ?: "mp3"

        if (options.instructions != null) {
            warnings += Warning.Unsupported(
                feature = "instructions",
                details = "Mistral speech models do not support the `instructions` option. " +
                    "Use a reference audio clip to guide delivery.",
            )
        }
        if (options.speed != null) {
            warnings += Warning.Unsupported(
                feature = "speed",
                details = "Mistral speech models do not support the `speed` option. It was ignored.",
            )
        }
        if (options.language != null) {
            warnings += Warning.Unsupported(
                feature = "language",
                details = "Mistral speech models do not support the `language` option. " +
                    "Language is inferred from the input text and voice.",
            )
        }

        val body = buildJsonObject {
            put("model", modelId)
            put("input", options.text)
            // One or the other: a reference clip replaces the catalogue voice outright.
            if (refAudio == null) options.voice?.let { put("voice_id", it) } else put("ref_audio", refAudio)
            put("response_format", format)
            put("stream", false)
        }

        val result = http.postJson(
            url = "$baseUrl/audio/speech",
            body = body,
            headers = combineHeaders(headers, options.headers),
        )
        val audio = result.value.jsonObject["audio_data"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("Mistral returned no audio_data.")

        val redactedBody = if (refAudio == null) {
            body
        } else {
            buildJsonObject {
                body.forEach { (key, value) -> if (key != "ref_audio") put(key, value) }
                put("ref_audio", "[redacted]")
            }
        }

        return SpeechResult(
            // Kept base64, exactly as received — decoding here costs a copy the caller may not want.
            audio = BinaryData.Base64(audio),
            warnings = warnings,
            request = RequestInfo(ProviderJson.encodeToString(JsonElement.serializer(), redactedBody)),
            response = result.modalityResponse(modelId = modelId),
        )
    }

    private companion object {
        /** What the endpoint documents; anything else is clamped to mp3 with a warning. */
        val SUPPORTED_FORMATS = setOf("pcm", "wav", "mp3", "flac", "opus")
    }
}
