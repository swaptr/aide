package com.sabreware.aide.aisdk.providers.lmnt

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.SpeechResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optDouble
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.client.HttpClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The provider id, and the namespace LMNT payloads file under. */
public const val LMNT_PROVIDER_ID: String = "lmnt"

/** LMNT text-to-speech. Auth is `x-api-key`, and the endpoint returns raw audio bytes. */
public class LmntProvider internal constructor(
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

    override val providerId: String = LMNT_PROVIDER_ID

    private val endpoint = baseUrl.trimEnd('/')

    private val headers = combineHeaders(mapOf("x-api-key" to apiKey), extraHeaders)

    override fun speechModel(modelId: String): SpeechModel = LmntSpeechModel(
        modelId = modelId,
        http = http,
        baseUrl = endpoint,
        headers = headers,
    )

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.lmnt.com"

        /** LMNT's own documented default voice. */
        public const val DEFAULT_VOICE: String = "ava"

        /** The formats LMNT accepts. Anything else is a 400, so it is clamped with a warning instead. */
        public val SUPPORTED_FORMATS: Set<String> = setOf("aac", "mp3", "mulaw", "raw", "wav")
    }
}

internal class LmntSpeechModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : SpeechModel {

    override val provider: String = LMNT_PROVIDER_ID

    override suspend fun doGenerate(options: SpeechCallOptions): SpeechResult {
        val warnings = mutableListOf<Warning>()
        val vendor = options.providerOptions?.forProvider(LMNT_PROVIDER_ID)

        // LMNT steers delivery through the voice, not free text.
        if (options.instructions != null) warnings += Warning.Unsupported("instructions")

        val format = options.outputFormat?.let { requested ->
            if (requested in LmntProvider.SUPPORTED_FORMATS) {
                requested
            } else {
                // Falling through with an unsupported value is a 400; clamping with a warning at least
                // produces audio and says what happened.
                warnings += Warning.Unsupported(
                    feature = "outputFormat",
                    details = "Unsupported output format: $requested. Using mp3 instead.",
                )
                DEFAULT_FORMAT
            }
        } ?: DEFAULT_FORMAT

        val body = buildJsonObject {
            put("model", modelId)
            put("text", options.text)
            put("voice", options.voice ?: LmntProvider.DEFAULT_VOICE)
            put("response_format", format)
            // The vendor's own speed wins over the generic one, which is the reference's rule for every
            // option the two layers both name.
            (vendor?.optDouble("speed") ?: options.speed)?.let { put("speed", it) }
            options.language?.let { put("language", it) }
            vendor?.optBoolean("conversational")?.let { put("conversational", it) }
            vendor?.optDouble("length")?.let { put("length", it) }
            vendor?.optInt("seed")?.let { put("seed", it) }
            vendor?.optDouble("temperature")?.let { put("temperature", it) }
            vendor?.optDouble("topP")?.let { put("top_p", it) }
            vendor?.optInt("sampleRate")?.let { put("sample_rate", it) }
        }

        val result = http.postBytesForBytes(
            url = "$baseUrl/v1/ai/speech/bytes",
            body = body,
            headers = combineHeaders(headers, options.headers),
        )
        if (result.value.isEmpty()) throw NoContentGeneratedError("LMNT returned no audio.")

        return SpeechResult(
            audio = BinaryData.Bytes(result.value),
            warnings = warnings,
            request = result.requestInfo(),
            response = result.modalityResponse(modelId = modelId),
        )
    }

    private companion object {
        const val DEFAULT_FORMAT = "mp3"
    }
}
