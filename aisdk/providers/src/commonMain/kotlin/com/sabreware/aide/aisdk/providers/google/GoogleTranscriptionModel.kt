package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.ResponseInfo
import com.sabreware.aide.aisdk.ResponseMetadata
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.TranscriptionStreamOptions
import com.sabreware.aide.aisdk.TranscriptionStreamResult
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.ProviderSocket
import com.sabreware.aide.aisdk.util.combineHeaders
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Gemini batch transcription, served by the **Interactions API** (`POST {base}/interactions`) — not by
 * `generateContent` with an audio part, which is what a reasonable guess would write and what would
 * quietly produce a chat answer *about* the audio instead of a transcript of it.
 *
 * The request is `{model, input: [{type: "audio", data, mime_type}], generation_config}`; diarization
 * and word timestamps are not top-level switches but members of the `mode` object, per
 * https://ai.google.dev/gemini-api/docs/transcribe. Word timings come back as `word_info` annotations
 * whose offsets are Google duration strings (`"0.100s"`), which is where segments come from.
 *
 * Live transcription (`gemini-3.5-transcribe-live`) is a different wire entirely — a Gemini Live
 * WebSocket session, served by [doStream] and [GoogleLiveTranscription]. A unary call on a live model id
 * therefore fails loudly rather than posting to an endpoint that would reject it, and a streaming call
 * on a unary id does the same.
 */
internal class GoogleTranscriptionModel(
    override val modelId: String,
    http: ProviderHttp,
    private val baseUrl: String = GOOGLE_DEFAULT_BASE_URL,
    /** Resolved per call, not per model — see [GoogleLanguageModel]'s constructor for why. */
    private val headers: suspend () -> Map<String, String> = { emptyMap() },
    /**
     * Absent unless the caller handed us the client. The live session is a socket protocol, and this
     * module never builds a transport of its own — so a model constructed from a bare [ProviderHttp]
     * offers no live session and returns null from [doStream] rather than opening one that cannot
     * connect.
     */
    private val socket: ProviderSocket? = null,
    /** The API key, which the Live handshake takes in the URL because it has no header to take. */
    private val apiKey: String? = null,
    private val finishGraceMs: Long = LIVE_TRANSCRIPTION_GRACE_MS,
) : TranscriptionModel {

    override val provider: String = GOOGLE_PROVIDER_ID

    private val http = http.withErrorStructure(GoogleErrorStructure)

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult {
        if (isGoogleLiveTranscriptionModel(modelId)) {
            throw InvalidArgumentError(
                argument = "modelId",
                message = "Model '$modelId' only supports streaming transcription. " +
                    "Use a unary model such as 'gemini-3.5-transcribe'.",
            )
        }

        val vendor = options.providerOptions?.forProvider(GOOGLE_PROVIDER_ID)
        val transcriptionConfig = buildTranscriptionConfig(vendor)

        val body = buildJsonObject {
            put("model", modelId)
            putJsonArray("input") {
                add(
                    buildJsonObject {
                        put("type", "audio")
                        put(
                            "data",
                            when (val audio = options.audio) {
                                is BinaryData.Base64 -> audio.value
                                is BinaryData.Bytes -> Base64.encode(audio.value)
                            },
                        )
                        put("mime_type", options.mediaType)
                    },
                )
            }
            if (transcriptionConfig != null) {
                put("generation_config", buildJsonObject { put("transcription_config", transcriptionConfig) })
            }
        }

        val result = http.postJson(
            url = "$baseUrl/interactions",
            body = body,
            headers = combineHeaders(headers(), options.headers),
        )

        val response = result.value.jsonObject
        var text = ""
        val segments = mutableListOf<TranscriptionResult.Segment>()
        response["steps"]?.jsonArray?.forEach { step ->
            step.jsonObject["content"]?.jsonArray?.forEach step@{ entry ->
                val content = entry.jsonObject
                if (content.optString("type") != "text") return@step
                val stepText = content.optString("text") ?: return@step
                text += stepText
                content["annotations"]?.jsonArray?.forEach { element ->
                    val annotation = element.jsonObject
                    if (annotation.optString("type") != "word_info") return@forEach
                    val word = annotation.optString("text") ?: return@forEach
                    val start = parseOffsetSeconds(annotation.optString("start_offset")) ?: return@forEach
                    val end = parseOffsetSeconds(annotation.optString("end_offset")) ?: return@forEach
                    segments += TranscriptionResult.Segment(text = word, startSecond = start, endSecond = end)
                }
            }
        }

        return TranscriptionResult(
            text = text,
            segments = segments,
            request = result.requestInfo(),
            response = result.modalityResponse(modelId = modelId, body = response.toString()),
            providerMetadata = (response["usage"] as? JsonObject)?.let { usage ->
                mapOf(GOOGLE_PROVIDER_ID to buildJsonObject { put("usage", usage) })
            },
        )
    }

    /**
     * Gemini Live transcription, over the `BidiGenerateContent` socket.
     *
     * The credential travels in the URL because the Live handshake has no header to take it in — which
     * is also why the reported [RequestInfo] is the SETUP BODY rather than the URL: a request record
     * quoting the URL would copy the API key into every log that reads one.
     */
    override suspend fun doStream(options: TranscriptionStreamOptions): TranscriptionStreamResult? {
        if (!isGoogleLiveTranscriptionModel(modelId)) {
            throw InvalidArgumentError(
                argument = "modelId",
                message = "Model '$modelId' does not support streaming transcription. " +
                    "Use a live model such as 'gemini-3.5-transcribe-live'.",
            )
        }
        val live = socket ?: return null
        validateGoogleLiveTranscriptionFormat(options.inputAudioFormat)

        val resolved = combineHeaders(headers(), options.headers)
        // Last case-variant wins, matching how the header map was built.
        val key = apiKey
            ?: resolved.entries.lastOrNull { it.key.equals("x-goog-api-key", ignoreCase = true) }?.value
            ?: throw InvalidArgumentError(
                argument = "apiKey",
                message = "A Google API key is required for streaming transcription.",
            )

        val setup = googleLiveTranscriptionSetup(
            modelId = modelId,
            vendor = options.providerOptions?.forProvider(GOOGLE_PROVIDER_ID),
        )

        return TranscriptionStreamResult(
            stream = googleLiveTranscriptionStream(
                socket = live,
                url = googleLiveTranslationUrl(baseUrl, key),
                // The key authenticates the URL; a header copy of it would be a second place for the
                // credential to reach a log, and the endpoint ignores it.
                headers = resolved.filterKeys { !it.equals("x-goog-api-key", ignoreCase = true) },
                audio = options.audio,
                setup = setup,
                inputRate = options.inputAudioFormat.rate ?: LIVE_TRANSCRIPTION_INPUT_RATE,
                mapper = GoogleLiveTranscriptionMapper(
                    warnings = emptyList(),
                    includeRawChunks = options.includeRawChunks,
                ),
                graceMs = finishGraceMs,
            ),
            request = RequestInfo(body = ProviderJson.encodeToString(JsonObject.serializer(), setup)),
            response = ResponseInfo(metadata = ResponseMetadata(modelId = modelId)),
        )
    }
}

/**
 * The Interactions API `transcription_config` (snake_case wire), or null when no option is set — an
 * empty `generation_config` is noise the endpoint does not need.
 *
 * Diarization and word timestamps live INSIDE the `mode` object, so asking for either forces a mode
 * (`verbatim` unless the caller chose one).
 */
private fun buildTranscriptionConfig(vendor: JsonObject?): JsonObject? {
    if (vendor == null) return null
    val mode = vendor.optString("mode")
    val diarization = vendor.optBoolean("diarization") == true
    val wordTimestamp = vendor.optBoolean("wordTimestamp") == true
    val config = buildJsonObject {
        vendor.optArray("languageCodes")?.let { put("language_codes", it) }
        vendor.optArray("customVocabulary")?.let { put("custom_vocabulary", it) }
        if (mode != null || diarization || wordTimestamp) {
            put(
                "mode",
                buildJsonObject {
                    put("type", (mode ?: "VERBATIM").lowercase())
                    if (diarization) put("diarization_mode", "speaker")
                    if (wordTimestamp) putJsonArray("timestamp_granularities") { add("word") }
                },
            )
        }
    }
    return config.takeIf { it.isNotEmpty() }
}

/** A Google duration offset (`"1s"`, `"9.400s"`) as seconds, or null when it does not parse. */
private fun parseOffsetSeconds(offset: String?): Double? =
    offset?.takeWhile { it.isDigit() || it == '.' || it == '-' }?.toDoubleOrNull()
