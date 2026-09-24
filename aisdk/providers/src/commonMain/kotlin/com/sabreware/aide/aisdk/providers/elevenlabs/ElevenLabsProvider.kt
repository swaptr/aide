package com.sabreware.aide.aisdk.providers.elevenlabs

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.SpeechResult
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optDouble
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderSocket
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The provider id, and the namespace ElevenLabs payloads file under. */
public const val ELEVENLABS_PROVIDER_ID: String = "elevenlabs"

/**
 * ElevenLabs text-to-speech.
 *
 * Three things differ from every OpenAI-shaped speech endpoint, and all three fail confusingly:
 *
 * - The **voice is part of the URL**, not the body, and the model is the body field. That is the reverse
 *   of OpenAI, so a straight port of that call shape produces a 404 rather than a validation error.
 * - The **output format is a query parameter** whose values name a codec, a sample rate and a bitrate at
 *   once — `mp3_44100_128`, not `mp3`. See [ELEVENLABS_OUTPUT_FORMATS].
 * - Auth is `xi-api-key`, not a bearer token.
 */
public class ElevenLabsProvider internal constructor(
    private val http: ProviderHttp,
    apiKey: String,
    baseUrl: String,
    private val defaultVoiceId: String,
    extraHeaders: Map<String, String>,
    /**
     * Absent unless the caller handed us the client. Live transcription is a socket protocol, and this
     * module never builds a transport of its own — so a provider constructed from a bare [ProviderHttp]
     * offers no realtime model, and says so by returning null from `doStream` rather than by binding a
     * session that cannot open.
     */
    private val socket: ProviderSocket? = null,
) : Provider {

    public constructor(
        client: HttpClient,
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        /** Used when a call names no voice; ElevenLabs has no server-side default. */
        defaultVoiceId: String = DEFAULT_VOICE_ID,
        headers: Map<String, String> = emptyMap(),
    ) : this(ProviderHttp(client), apiKey, baseUrl, defaultVoiceId, headers, ProviderSocket(client))

    override val providerId: String = ELEVENLABS_PROVIDER_ID

    private val endpoint = baseUrl.trimEnd('/')

    private val headers = combineHeaders(mapOf("xi-api-key" to apiKey), extraHeaders)

    override fun speechModel(modelId: String): SpeechModel = ElevenLabsSpeechModel(
        modelId = modelId,
        http = http,
        baseUrl = endpoint,
        headers = headers,
        defaultVoiceId = defaultVoiceId,
    )

    override fun transcriptionModel(modelId: String): TranscriptionModel = ElevenLabsTranscriptionModel(
        modelId = modelId,
        http = http,
        baseUrl = endpoint,
        headers = headers,
        socket = socket,
    )

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.elevenlabs.io/v1"

        /** "Rachel" — ElevenLabs' own documented example voice. */
        public const val DEFAULT_VOICE_ID: String = "21m00Tcm4TlvDq8ikWAM"
    }
}

/**
 * The spec's short format names, mapped to the codec/rate/bitrate triples the API's enum accepts.
 *
 * Passing the spec's own documented `"mp3"` straight through is rejected: ElevenLabs has no such value.
 * A name that is not in this table is forwarded unchanged rather than clamped — the enum has more
 * members than are worth mirroring, and a caller naming one directly means it.
 */
internal val ELEVENLABS_OUTPUT_FORMATS: Map<String, String> = mapOf(
    "mp3" to "mp3_44100_128",
    "mp3_32" to "mp3_44100_32",
    "mp3_64" to "mp3_44100_64",
    "mp3_96" to "mp3_44100_96",
    "mp3_128" to "mp3_44100_128",
    "mp3_192" to "mp3_44100_192",
    "pcm" to "pcm_44100",
    "pcm_16000" to "pcm_16000",
    "pcm_22050" to "pcm_22050",
    "pcm_24000" to "pcm_24000",
    "pcm_44100" to "pcm_44100",
    "ulaw" to "ulaw_8000",
)

internal fun elevenLabsOutputFormat(name: String?): String =
    name?.let { ELEVENLABS_OUTPUT_FORMATS[it] ?: it } ?: "mp3_44100_128"

internal class ElevenLabsSpeechModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
    private val defaultVoiceId: String,
) : SpeechModel {

    override val provider: String = ELEVENLABS_PROVIDER_ID

    override suspend fun doGenerate(options: SpeechCallOptions): SpeechResult {
        val warnings = mutableListOf<Warning>()
        val vendor = options.providerOptions?.forProvider(ELEVENLABS_PROVIDER_ID)

        // ElevenLabs steers delivery through voice_settings, not free text; accepting instructions
        // silently would look like they were applied.
        if (options.instructions != null) {
            warnings += Warning.Unsupported(
                feature = "instructions",
                details = "ElevenLabs speech models do not support instructions. " +
                    "Instructions parameter was ignored.",
            )
        }

        // `speed` and the vendor's own voice settings share one object, so it is built first and sent
        // only if something landed in it — an empty `voice_settings` overrides the voice's saved
        // settings with nothing, which is audible.
        val voiceSettings = buildJsonObject {
            options.speed?.let { put("speed", it) }
            vendor?.optObject("voiceSettings")?.let { settings ->
                settings.optDouble("stability")?.let { put("stability", it) }
                settings.optDouble("similarityBoost")?.let { put("similarity_boost", it) }
                settings.optDouble("style")?.let { put("style", it) }
                settings.optBoolean("useSpeakerBoost")?.let { put("use_speaker_boost", it) }
            }
        }

        val body = buildJsonObject {
            put("text", options.text)
            put("model_id", modelId)
            (options.language ?: vendor?.optString("languageCode"))?.let { put("language_code", it) }
            if (voiceSettings.isNotEmpty()) put("voice_settings", voiceSettings)
            vendor?.let { putVendorFields(it) }
        }

        val query = buildMap {
            // Always sent. The API's own default matches ours, but sending it means the request records
            // which format the returned audio is actually in.
            put("output_format", elevenLabsOutputFormat(options.outputFormat))
            vendor?.optBoolean("enableLogging")?.let { put("enable_logging", it.toString()) }
        }

        // The voice is the PATH here, and the model is the body — the reverse of OpenAI's shape.
        val voiceId = options.voice ?: defaultVoiceId
        val url = "$baseUrl/text-to-speech/$voiceId?" +
            query.entries.joinToString("&") { (key, value) -> "$key=$value" }

        val result = http.postBytesForBytes(url, body, combineHeaders(headers, options.headers))
        if (result.value.isEmpty()) throw NoContentGeneratedError("ElevenLabs returned no audio.")

        return SpeechResult(
            audio = BinaryData.Bytes(result.value),
            warnings = warnings,
            request = result.requestInfo(),
            response = result.modalityResponse(modelId = modelId),
        )
    }

    /** The vendor options that map straight onto a body field, in the reference's order. */
    private fun JsonObjectBuilder.putVendorFields(vendor: JsonObject) {
        vendor.optArray("pronunciationDictionaryLocators")?.let { locators ->
            putJsonArray("pronunciation_dictionary_locators") {
                locators.forEach { entry ->
                    val locator = entry.jsonObject
                    add(
                        buildJsonObject {
                            locator.optString("pronunciationDictionaryId")
                                ?.let { put("pronunciation_dictionary_id", it) }
                            locator.optString("versionId")?.let { put("version_id", it) }
                        },
                    )
                }
            }
        }
        vendor.optInt("seed")?.let { put("seed", it) }
        vendor.optString("previousText")?.let { put("previous_text", it) }
        vendor.optString("nextText")?.let { put("next_text", it) }
        vendor.optArray("previousRequestIds")?.let { put("previous_request_ids", it) }
        vendor.optArray("nextRequestIds")?.let { put("next_request_ids", it) }
        vendor.optString("applyTextNormalization")?.let { put("apply_text_normalization", it) }
        vendor.optBoolean("applyLanguageTextNormalization")
            ?.let { put("apply_language_text_normalization", it) }
    }
}
