package com.sabreware.aide.aisdk.providers.xai

import com.sabreware.aide.aisdk.BatchLanguageModel
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.ProviderFiles
import com.sabreware.aide.aisdk.RealtimeModel
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.openai.OpenAIResponsesLanguageModel
import com.sabreware.aide.aisdk.providers.openai.XaiResponsesQuirks
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.MediaType
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.defaultErrorMessage
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The provider id, and the namespace xAI payloads file under. */
public const val XAI_PROVIDER_ID: String = "xai"

/**
 * xAI's native endpoints: `/responses` for language models, `/tts` and `/stt` for audio.
 *
 * [languageModel] serves the Responses API, which is xAI's default surface upstream and the only one of
 * its two chat wires that carries reasoning as a replayable item. A caller that specifically wants Chat
 * Completions uses `Vendors.xai`, the same split the reference keeps between `xai(...)` and
 * `xai.chat(...)`. Two dialect differences ride in as quirks: xAI spells its finish reasons its own way
 * (`completed`, bare `length`), and its `input_tokens` may EXCLUDE cached tokens where OpenAI's
 * includes them.
 *
 * The audio endpoints are NOT OpenAI-shaped. `/tts` takes a nested `output_format` object rather than a
 * format string and can answer with either raw audio or a JSON envelope, and `/stt` is a multipart
 * upload whose part ORDER matters. Routing either through the OpenAI-compatible wire produces a request
 * xAI accepts the shape of and answers wrongly. Both ignore the model id — xAI serves one voice model
 * and one transcriber — so the id is carried for [SpeechModel.modelId] and never put on the wire.
 *
 * Beyond the modalities: [files] is the upload half of a file reference, and [batchLanguageModel] runs
 * the Responses model through xAI's Batch API — a JSONL upload through that same Files endpoint.
 */
public class XaiProvider internal constructor(
    http: ProviderHttp,
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

    override val providerId: String = XAI_PROVIDER_ID

    private val http = http.withErrorStructure(XaiErrors)

    private val endpoint = baseUrl.trimEnd('/')

    private val headers = combineHeaders(mapOf("Authorization" to "Bearer $apiKey"), extraHeaders)

    override fun languageModel(modelId: String): LanguageModel = OpenAIResponsesLanguageModel(
        modelId = modelId,
        http = http,
        headers = headers,
        provider = XAI_PROVIDER_ID,
        namespace = XAI_PROVIDER_ID,
        endpointUrl = "$endpoint/responses",
        // The model would otherwise install OpenAI's error reader, losing the `{code, error}` shape.
        errorStructure = XaiErrors,
        quirks = XaiResponsesQuirks,
    )

    /**
     * The same Responses model on xAI's Batch API — many requests submitted once as a JSONL file,
     * collected later. See [BatchLanguageModel] for the lifecycle and [XaiResponsesBatchModel] for the
     * one surprise: results come back in Chat Completions shape.
     */
    public fun batchLanguageModel(modelId: String): BatchLanguageModel =
        XaiResponsesBatchModel(modelId, http, endpoint, headers)

    /** The Files API — uploads that mint the ids a [com.sabreware.aide.aisdk.FileData.Reference] carries. */
    public fun files(): ProviderFiles = XaiFiles(http, endpoint, headers)

    override fun speechModel(modelId: String): SpeechModel =
        XaiSpeechModel(modelId, http, endpoint, headers)

    override fun transcriptionModel(modelId: String): TranscriptionModel =
        XaiTranscriptionModel(modelId, http, endpoint, headers)

    override fun videoModel(modelId: String): VideoModel =
        XaiVideoModel(modelId, http, endpoint, headers)

    /**
     * xAI's realtime voice API.
     *
     * Not a [Provider] modality: [RealtimeModel] mints a credential and translates frames rather than
     * owning a transport, so it has no slot on the contract every other modality shares. Reached
     * directly, by the caller that is going to open the socket.
     */
    public fun realtimeModel(modelId: String): RealtimeModel =
        XaiRealtimeModel(modelId, http, endpoint, headers)

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.x.ai/v1"
    }
}

/**
 * xAI's third error shape.
 *
 * `/tts` rejects a bad parameter with `{"code":"...","error":"..."}`, where the code is the part that
 * identifies the failure and the string is the sentence a user reads. The shared default finds the
 * string and drops the code, which is the difference between "speed must be between 0.7 and 1.5" and
 * knowing which of the two documented error families it belongs to; every other xAI shape — the
 * OpenAI-style `error.message` and the bare `error` string — the default already covers.
 */
internal val XaiErrors: ProviderErrorStructure = ProviderErrorStructure(
    extractMessage = { body ->
        val obj = body as? JsonObject
        val code = (obj?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val message = defaultErrorMessage(body)
        if (code != null && message != null) "$code: $message" else message
    },
)

/** Option name to wire name for the `/stt` form fields xAI spells differently from the option. */
private val XAI_STT_FIELD_NAMES = listOf(
    "audioFormat" to "audio_format",
    "sampleRate" to "sample_rate",
    "language" to "language",
    "format" to "format",
    "multichannel" to "multichannel",
    "channels" to "channels",
    "diarize" to "diarize",
    "fillerWords" to "filler_words",
)

@OptIn(ExperimentalEncodingApi::class)
internal class XaiTranscriptionModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : TranscriptionModel {

    override val provider: String = XAI_PROVIDER_ID

    override suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult {
        val bytes = when (val audio = options.audio) {
            is BinaryData.Bytes -> audio.value
            is BinaryData.Base64 -> Base64.decode(audio.value)
        }
        val vendor = options.providerOptions?.forProvider(XAI_PROVIDER_ID)
        val warnings = mutableListOf<Warning>()

        val fields = buildList {
            XAI_STT_FIELD_NAMES.forEach { (option, wire) ->
                vendor?.scalar(option)?.let { add(wire to it) }
            }
            // A repeated field name is how xAI reads a list here, so a caller may pass either one term
            // or an array and both arrive as terms rather than as a JSON-shaped string.
            vendor?.optArray("keyterm")?.forEach { add("keyterm" to it.jsonPrimitive.content) }
                ?: vendor?.optString("keyterm")?.let { add("keyterm" to it) }
        }
        if (vendor?.optBoolean("multichannel") == true && vendor.optInt("channels") == null) {
            // xAI transcribes the first channel and says nothing, so a stereo interview comes back with
            // one speaker missing rather than as a rejected request.
            warnings += Warning.Other(
                "providerOptions.xai.channels is required when providerOptions.xai.multichannel is " +
                    "true; xAI otherwise transcribes a single channel.",
            )
        }

        val result = http.postMultipart(
            url = "$baseUrl/stt",
            fileField = "file",
            // Sniffed rather than trusted: the extension is what xAI keys its decoder off, and a
            // caller's declared media type can be absent or wrong.
            fileName = "audio.${MediaType.detectOr(bytes, options.mediaType).substringAfter('/')}",
            fileBytes = bytes,
            fileContentType = options.mediaType,
            fields = fields,
            headers = combineHeaders(headers, options.headers),
            // The settings apply to the upload as the parts stream past, so a file that arrives first
            // is transcribed with every one of them ignored.
            fileLast = true,
        )
        val response = result.value.jsonObject

        return TranscriptionResult(
            text = response["text"]?.jsonPrimitive?.content.orEmpty(),
            // Words, not utterances: `/stt` returns one entry per word, and it is the only granularity
            // it offers.
            segments = response["words"]?.jsonArray.orEmpty().map { entry ->
                val word = entry.jsonObject
                TranscriptionResult.Segment(
                    text = word["text"]?.jsonPrimitive?.content.orEmpty(),
                    startSecond = word["start"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    endSecond = word["end"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                )
            },
            // xAI sends "" when it could not tell, which is not a language a caller can act on.
            language = response["language"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() },
            durationInSeconds = response["duration"]?.jsonPrimitive?.content?.toDoubleOrNull(),
            warnings = warnings,
            request = result.requestInfo(),
            response = result.modalityResponse(modelId = modelId, body = result.value.toString()),
        )
    }
}

/** Every `/stt` form field is a string on the wire, whatever JSON type the caller wrote it as. */
private fun JsonObject.scalar(key: String): String? =
    optString(key) ?: optBoolean(key)?.toString() ?: optInt(key)?.toString()
