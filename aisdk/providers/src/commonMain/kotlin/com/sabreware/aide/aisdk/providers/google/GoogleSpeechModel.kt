package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.SpeechResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Google's own documented example voice, used when the caller names none. */
internal const val GOOGLE_DEFAULT_TTS_VOICE: String = "Kore"

/** Gemini TTS returns raw PCM at 24kHz when the response mime type names no rate. */
internal const val GOOGLE_DEFAULT_TTS_SAMPLE_RATE: Int = 24_000

/**
 * Gemini TTS, which is not a speech endpoint at all: it is `generateContent` asked for
 * `responseModalities: ["AUDIO"]`, with the voice chosen through `speechConfig`.
 *
 * Two consequences follow, and both are why this model exists rather than a generic shape:
 *
 * - **Delivery direction is prompt text.** Gemini honours natural-language style direction in the
 *   spoken content itself, so `instructions` are prepended to the text — except under a multi-speaker
 *   config, whose transcript starts with speaker labels that a prepended sentence would corrupt.
 * - **The audio comes back as headerless raw PCM** (`audio/L16;rate=24000`), which no media sniffer can
 *   identify and no player will open. It is wrapped in a minimal WAV container by default;
 *   `outputFormat = "pcm"` returns the naked bytes for callers feeding a pipeline that wants them.
 */
internal class GoogleSpeechModel(
    override val modelId: String,
    http: ProviderHttp,
    private val baseUrl: String = GOOGLE_DEFAULT_BASE_URL,
    /** Resolved per call, not per model — see [GoogleLanguageModel]'s constructor for why. */
    private val headers: suspend () -> Map<String, String> = { emptyMap() },
) : SpeechModel {

    override val provider: String = GOOGLE_PROVIDER_ID

    private val http = http.withErrorStructure(GoogleErrorStructure)

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun doGenerate(options: SpeechCallOptions): SpeechResult {
        val warnings = mutableListOf<Warning>()
        val vendor = options.providerOptions?.forProvider(GOOGLE_PROVIDER_ID)

        // Multi-speaker (a provider option, passed verbatim) wins over the single top-level voice.
        val multiSpeaker = vendor?.optObject("multiSpeakerVoiceConfig")
        val speechConfig = if (multiSpeaker != null) {
            buildJsonObject { put("multiSpeakerVoiceConfig", multiSpeaker) }
        } else {
            buildJsonObject {
                putJsonObject("voiceConfig") {
                    putJsonObject("prebuiltVoiceConfig") {
                        put("voiceName", options.voice ?: GOOGLE_DEFAULT_TTS_VOICE)
                    }
                }
            }
        }

        var promptText = options.text
        if (options.instructions != null) {
            if (multiSpeaker != null) {
                warnings += Warning.Unsupported(
                    feature = "instructions",
                    details = "Google Gemini TTS ignores `instructions` when `multiSpeakerVoiceConfig` " +
                        "is set, because prepending them would break multi-speaker transcript parsing.",
                )
            } else {
                promptText = "${options.instructions}: ${options.text}"
            }
        }

        if (options.speed != null) {
            warnings += Warning.Unsupported(
                feature = "speed",
                details = "Google Gemini TTS models do not support the `speed` option. It was ignored.",
            )
        }

        if (options.language != null) {
            warnings += Warning.Unsupported(
                feature = "language",
                details = "Google Gemini TTS models do not support the `language` option. " +
                    "Language is detected automatically from the input text.",
            )
        }

        // Only two shapes exist: WAV-wrapped (default) and the naked PCM underneath it.
        val wantsRawPcm = options.outputFormat == "pcm"
        if (options.outputFormat != null && options.outputFormat != "wav" && !wantsRawPcm) {
            warnings += Warning.Unsupported(
                feature = "outputFormat",
                details = "Unsupported output format: ${options.outputFormat}. Using wav instead.",
            )
        }

        val body = buildJsonObject {
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", promptText) }) }
                    },
                )
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("AUDIO") }
                put("speechConfig", speechConfig)
            }
        }

        val result = http.postJson(
            url = "$baseUrl/models/$modelId:generateContent",
            body = body,
            headers = combineHeaders(headers(), options.headers),
        )

        // One audio result per request: the first inline-data part is the payload.
        val inline = (result.value.jsonObject["candidates"] as? kotlinx.serialization.json.JsonArray)
            ?.asSequence()
            ?.mapNotNull { it.jsonObject.optObject("content") }
            ?.flatMap { content -> content["parts"]?.jsonArray?.asSequence() ?: emptySequence() }
            ?.mapNotNull { it.jsonObject.optObject("inlineData") }
            ?.firstOrNull { it.stringOrNull("data") != null }

        val mimeType = inline?.stringOrNull("mimeType")
        val pcm = inline?.stringOrNull("data")?.let { Base64.decode(it) } ?: ByteArray(0)
        if (pcm.isEmpty()) throw NoContentGeneratedError("Google Gemini TTS returned no audio.")

        val sampleRate = parseSampleRate(mimeType) ?: GOOGLE_DEFAULT_TTS_SAMPLE_RATE
        val audio = if (wantsRawPcm) pcm else addWavHeader(pcm, sampleRate)
        if (wantsRawPcm) {
            warnings += Warning.Unsupported(
                feature = "outputFormat",
                details = "Returning raw PCM audio (signed 16-bit little-endian, mono, $sampleRate Hz). " +
                    "These bytes have no container header and are not directly playable; " +
                    "see providerMetadata.google for the sample rate and mime type.",
            )
        }

        return SpeechResult(
            audio = BinaryData.Bytes(audio),
            warnings = warnings,
            request = result.requestInfo(),
            response = result.modalityResponse(modelId = modelId, body = result.value.toString()),
            providerMetadata = mapOf(
                GOOGLE_PROVIDER_ID to buildJsonObject {
                    put("sampleRate", sampleRate)
                    mimeType?.let { put("mimeType", it) } ?: put("mimeType", JsonNull)
                },
            ),
        )
    }
}

/** The rate in a PCM mime type such as `audio/L16;rate=24000`, or null when it names none. */
private fun parseSampleRate(mimeType: String?): Int? =
    mimeType?.let { Regex("rate=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

/**
 * Wraps raw signed 16-bit little-endian mono PCM in a minimal 44-byte RIFF/WAVE container, so the
 * output is playable and detectable as `audio/wav` instead of mislabelled by signature sniffing.
 */
@Suppress("MagicNumber")
private fun addWavHeader(pcm: ByteArray, sampleRate: Int): ByteArray {
    val channels = 1
    val bitsPerSample = 16
    val blockAlign = channels * bitsPerSample / 8
    val byteRate = sampleRate * blockAlign

    val header = ByteArray(WAV_HEADER_SIZE)
    header.putAscii(0, "RIFF")
    header.putIntLe(4, 36 + pcm.size)
    header.putAscii(8, "WAVE")
    header.putAscii(12, "fmt ")
    header.putIntLe(16, 16) // PCM fmt chunk size
    header.putShortLe(20, 1) // audio format = PCM
    header.putShortLe(22, channels)
    header.putIntLe(24, sampleRate)
    header.putIntLe(28, byteRate)
    header.putShortLe(32, blockAlign)
    header.putShortLe(34, bitsPerSample)
    header.putAscii(36, "data")
    header.putIntLe(40, pcm.size)
    return header + pcm
}

private const val WAV_HEADER_SIZE = 44

private fun ByteArray.putAscii(offset: Int, text: String) {
    text.forEachIndexed { i, c -> this[offset + i] = c.code.toByte() }
}

@Suppress("MagicNumber")
private fun ByteArray.putIntLe(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    this[offset + 2] = ((value shr 16) and 0xFF).toByte()
    this[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

@Suppress("MagicNumber")
private fun ByteArray.putShortLe(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
}
