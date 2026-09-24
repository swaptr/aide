package com.sabreware.aide.aisdk.providers.cartesia

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.RealtimeModel
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.SpeechResult
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.providers.options.optDouble
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderSocket
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The provider id, and the namespace Cartesia payloads file under. */
public const val CARTESIA_PROVIDER_ID: String = "cartesia"

/**
 * Cartesia text-to-speech.
 *
 * Two things are unusual. The API is **version-pinned by header** — `Cartesia-Version` is required on
 * every request, and omitting it is rejected rather than defaulted, which is a failure that looks
 * nothing like a missing header. And the output format is a **triple** whose members have to agree:
 * mp3 takes a bitrate and no encoding, raw and wav take an encoding and no bitrate. See
 * [cartesiaOutputFormat].
 */
public class CartesiaProvider internal constructor(
    http: ProviderHttp,
    apiKey: String,
    baseUrl: String,
    private val apiVersion: String,
    extraHeaders: Map<String, String>,
    /**
     * Absent unless the caller handed us the client. Ink 2's live transcription is a socket protocol,
     * and this module never builds a transport of its own — see [CartesiaTranscriptionModel].
     */
    private val socket: ProviderSocket? = null,
) : Provider {

    public constructor(
        client: HttpClient,
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        apiVersion: String = DEFAULT_API_VERSION,
        headers: Map<String, String> = emptyMap(),
    ) : this(ProviderHttp(client), apiKey, baseUrl, apiVersion, headers, ProviderSocket(client))

    override val providerId: String = CARTESIA_PROVIDER_ID

    private val api = http.withErrorStructure(CartesiaErrors)

    private val endpoint = baseUrl.trimEnd('/')

    private val headers = combineHeaders(
        mapOf(
            "Authorization" to "Bearer $apiKey",
            // Required on every request, not optional.
            "Cartesia-Version" to apiVersion,
        ),
        extraHeaders,
    )

    override fun speechModel(modelId: String): SpeechModel = CartesiaSpeechModel(
        modelId = modelId,
        http = api,
        baseUrl = endpoint,
        headers = headers,
    )

    override fun transcriptionModel(modelId: String): TranscriptionModel = CartesiaTranscriptionModel(
        modelId = modelId,
        http = api,
        baseUrl = endpoint,
        headers = headers,
        apiVersion = apiVersion,
        socket = socket,
    )

    /**
     * Ink 2 over the realtime contract — live speech-to-text for a client that opens the socket itself.
     *
     * Not a [Provider] modality, for the reason xAI's is not: a [RealtimeModel] mints a credential and
     * translates frames rather than owning a transport, so it has no slot on the contract every other
     * modality shares. It is the second way to reach Ink 2 live. [transcriptionModel]'s `doStream` opens
     * the socket HERE, over the injected client, for a caller that holds both the key and the audio;
     * this one is for the browser, phone or voice pipeline that holds the microphone and must never hold
     * the key.
     */
    public fun realtimeModel(modelId: String): RealtimeModel = CartesiaRealtimeModel(
        modelId = modelId,
        http = api,
        baseUrl = endpoint,
        apiVersion = apiVersion,
        headers = headers,
    )

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.cartesia.ai"
        public const val DEFAULT_API_VERSION: String = "2026-03-01"
    }
}

/**
 * Cartesia spells an error `{error_code, title, message, request_id}` — no `error` wrapper, so the
 * OpenAI-shaped default finds `message` alone and drops the title that says which parameter was wrong.
 */
internal val CartesiaErrors: ProviderErrorStructure = ProviderErrorStructure(
    extractMessage = { body ->
        val obj = body as? JsonObject
        val title = obj?.get("title")?.jsonPrimitive?.content
        val message = obj?.get("message")?.jsonPrimitive?.content
        if (title != null && message != null) "$title: $message" else message
    },
)

/** Container, encoding, sample rate and bitrate, of which Cartesia accepts one consistent combination. */
internal data class CartesiaOutputFormat(
    val container: String,
    val encoding: String?,
    val sampleRate: Int,
    val bitRate: Int?,
)

private val DEFAULT_OUTPUT_FORMAT =
    CartesiaOutputFormat(container = "mp3", encoding = null, sampleRate = 44_100, bitRate = 128_000)

private val OUTPUT_FORMATS = mapOf(
    "alaw" to CartesiaOutputFormat("raw", "pcm_alaw", 8_000, null),
    "mp3" to DEFAULT_OUTPUT_FORMAT,
    "mulaw" to CartesiaOutputFormat("raw", "pcm_mulaw", 8_000, null),
    "pcm" to CartesiaOutputFormat("raw", "pcm_f32le", 44_100, null),
    "raw" to CartesiaOutputFormat("raw", "pcm_f32le", 44_100, null),
    "wav" to CartesiaOutputFormat("wav", "pcm_s16le", 44_100, null),
)

private val SAMPLE_RATES = setOf(8_000, 16_000, 22_050, 24_000, 44_100, 48_000)

/**
 * Expands a format name into Cartesia's triple, letting the caller's own options override each member.
 *
 * The name may carry a sample rate — `pcm_24000` — which is why this is a parse rather than a lookup:
 * treating the whole string as a key drops the rate silently and returns 44.1kHz audio to a caller who
 * asked for 24k. Getting a member wrong is either a 400 or, worse, audio that plays as noise.
 */
@Suppress("CyclomaticComplexMethod")
internal fun cartesiaOutputFormat(
    name: String,
    vendor: JsonObject?,
    warnings: MutableList<Warning>,
): CartesiaOutputFormat {
    val parts = name.lowercase().split('_')
    val mapped = OUTPUT_FORMATS[parts.first()]
    var resolved = mapped ?: DEFAULT_OUTPUT_FORMAT

    if (mapped == null) {
        warnings += Warning.Unsupported(
            feature = "outputFormat",
            details = "Unknown output format \"$name\". Falling back to mp3. Use " +
                "providerOptions.cartesia to configure container, encoding, and sampleRate directly.",
        )
    } else if (parts.size > 1) {
        val rate = parts[1].toIntOrNull()
        if (parts.size == 2 && rate != null && rate in SAMPLE_RATES) {
            resolved = resolved.copy(sampleRate = rate)
        } else {
            warnings += Warning.Unsupported(
                feature = "outputFormat",
                details = "Unsupported Cartesia sample rate in output format \"$name\". " +
                    "Using ${resolved.sampleRate} Hz instead.",
            )
        }
    }

    val container = vendor?.optString("container") ?: resolved.container
    val sampleRate = vendor?.optInt("sampleRate") ?: resolved.sampleRate

    if (container == "mp3") {
        if (vendor?.optString("encoding") != null) {
            warnings += Warning.Unsupported(
                feature = "providerOptions.cartesia.encoding",
                details = "Cartesia MP3 output does not accept an encoding. " +
                    "The encoding option was ignored.",
            )
        }
        return CartesiaOutputFormat(
            container = container,
            encoding = null,
            sampleRate = sampleRate,
            bitRate = vendor?.optInt("bitRate") ?: resolved.bitRate ?: DEFAULT_BIT_RATE,
        )
    }

    if (vendor?.optInt("bitRate") != null) {
        warnings += Warning.Unsupported(
            feature = "providerOptions.cartesia.bitRate",
            details = "Cartesia raw and WAV output do not accept a bit rate. " +
                "The bitRate option was ignored.",
        )
    }
    return CartesiaOutputFormat(
        container = container,
        // A caller who switched an mp3 format to a raw container by option alone has no encoding to
        // inherit, so the one that matches the container they asked for is used.
        encoding = vendor?.optString("encoding")
            ?: resolved.encoding
            ?: if (container == "wav") "pcm_s16le" else "pcm_f32le",
        sampleRate = sampleRate,
        bitRate = null,
    )
}

private const val DEFAULT_BIT_RATE = 128_000

/** Cartesia's documented range. Outside it the request is rejected rather than clamped. */
private val SPEED_RANGE = 0.6..1.5

internal class CartesiaSpeechModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : SpeechModel {

    override val provider: String = CARTESIA_PROVIDER_ID

    override suspend fun doGenerate(options: SpeechCallOptions): SpeechResult {
        val warnings = mutableListOf<Warning>()
        val vendor = options.providerOptions?.forProvider(CARTESIA_PROVIDER_ID)

        if (options.instructions != null) {
            warnings += Warning.Unsupported(
                feature = "instructions",
                details = "Cartesia speech models do not support instructions. " +
                    "Instructions parameter was ignored.",
            )
        }

        // There is no server-side default, and inventing one here picks a voice the caller never chose
        // and bills them for it. The reference refuses for the same reason.
        val voice = options.voice
            ?: throw InvalidArgumentError(
                message = "Cartesia speech models require a `voice` to be set.",
                argument = "voice",
            )

        val format = cartesiaOutputFormat(options.outputFormat ?: "mp3", vendor, warnings)

        val body = buildJsonObject {
            put("model_id", modelId)
            // Cartesia calls the input `transcript`, not `text`.
            put("transcript", options.text)
            putJsonObject("voice") {
                put("mode", "id")
                put("id", voice)
            }
            putJsonObject("output_format") {
                put("container", format.container)
                format.encoding?.let { put("encoding", it) }
                put("sample_rate", format.sampleRate)
                format.bitRate?.let { put("bit_rate", it) }
            }
            (vendor?.optString("language") ?: options.language)?.let { put("language", it) }

            val speed = vendor?.optDouble("speed") ?: options.speed
            when {
                speed == null -> Unit
                speed in SPEED_RANGE -> putJsonObject("generation_config") { put("speed", speed) }
                else -> warnings += Warning.Unsupported(
                    feature = "speed",
                    details = "Cartesia speed must be between 0.6 and 1.5. The speed option was ignored.",
                )
            }
        }

        val result = http.postBytesForBytes(
            url = "$baseUrl/tts/bytes",
            body = body,
            headers = combineHeaders(headers, options.headers),
        )
        if (result.value.isEmpty()) throw NoContentGeneratedError("Cartesia returned no audio.")

        return SpeechResult(
            audio = BinaryData.Bytes(result.value),
            warnings = warnings,
            request = result.requestInfo(),
            response = result.modalityResponse(modelId = modelId),
        )
    }
}
