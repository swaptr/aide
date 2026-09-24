package com.sabreware.aide.aisdk.providers.fal

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.SpeechResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * fal.ai text-to-speech.
 *
 * Speech is on the SYNCHRONOUS host (`fal.run/{model}`), like images and unlike video and
 * transcription — the response carries a finished clip's URL, not a queue handle. The audio itself
 * lives at that URL, usually on `fal.media`: a second fetch, made WITHOUT the API key, because the
 * host is not the one the key belongs to and fal's file URLs are borne by the link itself.
 *
 * `output_format` is always sent as `url`. The API's other value, `hex`, changes the response shape to
 * inline audio this model does not read — the reference forwards a caller's `hex` and then fails to
 * parse the answer, which is a worse outcome than the warning-and-URL used here.
 */
internal class FalSpeechModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val apiKey: String,
) : SpeechModel {

    override val provider: String = FAL_PROVIDER_ID

    override suspend fun doGenerate(options: SpeechCallOptions): SpeechResult {
        val warnings = mutableListOf<Warning>()
        val vendor = options.providerOptions?.forProvider(FAL_PROVIDER_ID)

        if (options.language != null) {
            warnings += Warning.Unsupported(
                feature = "language",
                details = "fal speech models don't support 'language' directly; " +
                    "consider providerOptions.fal.language_boost",
            )
        }
        // The reference ignores instructions silently; a warning says why the delivery did not change.
        if (options.instructions != null) {
            warnings += Warning.Unsupported(
                feature = "instructions",
                details = "fal speech models take delivery settings via providerOptions.fal.voice_setting; " +
                    "the instructions parameter was ignored.",
            )
        }
        if (options.outputFormat != null && options.outputFormat != "url") {
            warnings += Warning.Unsupported(
                feature = "outputFormat",
                details = "Unsupported outputFormat: ${options.outputFormat}. Using 'url' instead.",
            )
        }

        val body = buildJsonObject {
            put("text", options.text)
            put("output_format", "url")
            options.voice?.let { put("voice", it) }
            options.speed?.let { put("speed", it) }
            // fal's speech options are documented in snake_case already (voice_setting, audio_setting,
            // language_boost, pronunciation_dict) and models take arbitrary extras, so everything is
            // passed through verbatim — a key this port has never heard of is the normal case.
            vendor?.forEach { (key, value) -> put(key, value) }
        }

        val result = http.postJson(
            url = "$baseUrl/$modelId",
            body = body,
            headers = combineHeaders(falAuthHeaders(apiKey), options.headers),
        )
        val payload = result.value.jsonObject
        val audioUrl = payload["audio"]?.jsonObject?.get("url")?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("fal returned no audio URL")

        // Fetched now rather than handed back as a link: fal's file URLs expire, so a stored link is
        // silence later instead of an error here. `trustedOrigin` keeps the key off `fal.media`.
        val audio = http.getBytes(audioUrl, trustedOrigin = baseUrl).value

        return SpeechResult(
            audio = BinaryData.Bytes(audio),
            warnings = warnings,
            request = result.requestInfo(),
            // The reference parses duration_ms and request_id and then drops both; the duration is the
            // one fact about the clip a caller cannot cheaply derive, so it rides in the metadata.
            providerMetadata = mapOf(
                FAL_PROVIDER_ID to buildJsonObject {
                    payload["duration_ms"]?.let { put("durationMs", it) }
                    payload["request_id"]?.let { put("requestId", it) }
                },
            ).takeIf { payload["duration_ms"] != null || payload["request_id"] != null },
            response = result.modalityResponse(modelId = modelId),
        )
    }
}
