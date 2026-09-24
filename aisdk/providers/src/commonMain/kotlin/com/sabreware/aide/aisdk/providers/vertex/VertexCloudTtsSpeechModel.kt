package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.SpeechResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.google.GoogleErrorStructure
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Chirp 3: HD voices, on the Cloud Text-to-Speech API.
 *
 * Not a Vertex endpoint at all: Gemini TTS models go through Vertex `generateContent` (the reused
 * [com.sabreware.aide.aisdk.providers.google.GoogleSpeechModel]), but a `chirp*` model id routes HERE,
 * to `texttospeech.googleapis.com` — a single non-regional host, unlike everything else this provider
 * touches — reusing the same OAuth bearer credentials.
 *
 * The voice field does double duty. A fully-qualified Chirp name (`en-US-Chirp3-HD-Kore`) passes
 * through verbatim, with its locale prefix supplying the language; a bare name (`Kore`) is composed
 * with the language into the qualified form. Sending a bare `Kore` unqualified is a 400, which is why
 * the composition lives here and not in the caller.
 *
 * `LINEAR16` responses arrive as complete WAV (RIFF) files, so `wav` is the only honest output format —
 * anything else warns and returns WAV anyway, the reference's behaviour.
 */
internal class VertexCloudTtsSpeechModel(
    override val modelId: String,
    http: ProviderHttp,
    private val headers: suspend () -> Map<String, String>,
    private val synthesizeUrl: String = CLOUD_TTS_SYNTHESIZE_URL,
) : SpeechModel {

    override val provider: String = VERTEX_PROVIDER_ID

    private val http = http.withErrorStructure(GoogleErrorStructure)

    override suspend fun doGenerate(options: SpeechCallOptions): SpeechResult {
        val warnings = mutableListOf<Warning>()

        val voice = options.voice ?: DEFAULT_VOICE
        val voiceName: String
        val languageCode: String
        if (voice.contains(CHIRP3_HD_INFIX)) {
            voiceName = voice
            // The locale prefix may be absent (`Chirp3-HD-Kore`), in which case the language falls back.
            val localePrefix = voice.substringBefore(CHIRP3_HD_INFIX).trimEnd('-')
            languageCode = options.language ?: localePrefix.ifEmpty { DEFAULT_LANGUAGE }
        } else {
            languageCode = options.language ?: DEFAULT_LANGUAGE
            voiceName = "$languageCode-$CHIRP3_HD_INFIX-$voice"
        }

        if (options.instructions != null) {
            warnings += Warning.Unsupported(
                feature = "instructions",
                details = "Google Cloud Text-to-Speech Chirp 3: HD voices do not support the " +
                    "`instructions` option. It was ignored.",
            )
        }
        if (options.outputFormat != null && options.outputFormat != "wav") {
            warnings += Warning.Unsupported(
                feature = "outputFormat",
                details = "Unsupported output format: ${options.outputFormat}. Using wav instead.",
            )
        }

        val body = buildJsonObject {
            putJsonObject("input") { put("text", options.text) }
            putJsonObject("voice") {
                put("languageCode", languageCode)
                put("name", voiceName)
            }
            putJsonObject("audioConfig") {
                put("audioEncoding", "LINEAR16")
                options.speed?.let { put("speakingRate", it) }
            }
        }

        val result = http.postJson(synthesizeUrl, body, combineHeaders(headers(), options.headers))
        val audioContent = result.value.jsonObject.optString("audioContent")
        if (audioContent.isNullOrEmpty()) {
            throw NoContentGeneratedError("Google Cloud Text-to-Speech returned no audio.")
        }

        return SpeechResult(
            // Kept encoded: the wire delivered base64, and decoding here costs a copy the caller may
            // never need — see BinaryData's own KDoc.
            audio = BinaryData.Base64(audioContent),
            warnings = warnings,
            request = result.requestInfo(),
            providerMetadata = mapOf(
                VERTEX_PROVIDER_ID to buildJsonObject { put("mimeType", "audio/wav") },
            ),
            response = result.modalityResponse(modelId = modelId),
        )
    }

    internal companion object {
        /** Standard synthesis has no regional host — one URL, worldwide. */
        const val CLOUD_TTS_SYNTHESIZE_URL: String =
            "https://texttospeech.googleapis.com/v1/text:synthesize"

        const val DEFAULT_VOICE: String = "Kore"
        const val DEFAULT_LANGUAGE: String = "en-US"

        /** `<locale>-Chirp3-HD-<voice>` is the qualified voice-name shape. */
        const val CHIRP3_HD_INFIX: String = "Chirp3-HD"
    }
}
