package com.sabreware.aide.aisdk.providers.elevenlabs

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
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optElement
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.MediaType
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderSocket
import com.sabreware.aide.aisdk.util.combineHeaders
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The model that only ever answers over a live socket.
 *
 * Posting a file to it is not a slower path to the same transcript — the batch endpoint rejects the id
 * outright — so the refusal names the combination rather than letting the vendor produce a 4xx about a
 * model the caller believes is supported.
 */
private const val REALTIME_MODEL_ID = "scribe_v2_realtime"

/**
 * ElevenLabs speech-to-text.
 *
 * A multipart upload where the model id is a FIELD rather than part of the path — the opposite of the
 * speech half, where the voice is the path and the model is the body.
 *
 * `diarize` is sent as true unless the caller says otherwise, because speaker labels are what the
 * per-word timings are worth having for and ElevenLabs does not charge separately for them.
 */
@OptIn(ExperimentalEncodingApi::class)
internal class ElevenLabsTranscriptionModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
    private val socket: ProviderSocket?,
) : TranscriptionModel {

    override val provider: String = ELEVENLABS_PROVIDER_ID

    override suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult {
        if (modelId == REALTIME_MODEL_ID) {
            throw UnsupportedFunctionalityError("non-streaming transcription with $modelId")
        }
        val warnings = mutableListOf<Warning>()
        val vendor = options.providerOptions?.forProvider(ELEVENLABS_PROVIDER_ID)
        val bytes = when (val audio = options.audio) {
            is BinaryData.Bytes -> audio.value
            is BinaryData.Base64 -> Base64.decode(audio.value)
        }

        if (vendor?.optElement("streaming") != null) {
            warnings += Warning.Unsupported(
                feature = "providerOptions.elevenlabs.streaming",
                details = "ElevenLabs batch transcription does not support streaming options.",
            )
        }

        val fields = buildList {
            add("model_id" to modelId)
            add("diarize" to (vendor?.optBoolean("diarize") ?: true).toString())
            vendor?.optString("languageCode")?.let { add("language_code" to it) }
            vendor?.optBoolean("tagAudioEvents")?.let { add("tag_audio_events" to it.toString()) }
            vendor?.optInt("numSpeakers")?.let { add("num_speakers" to it.toString()) }
            vendor?.optString("timestampsGranularity")?.let { add("timestamps_granularity" to it) }
            vendor?.optString("fileFormat")?.let { add("file_format" to it) }
        }

        val result = http.postMultipart(
            url = "$baseUrl/speech-to-text",
            fileField = "file",
            // Sniffed rather than assumed: the caller's declared type can be absent or wrong, and the
            // extension is what the server keys its decoder off.
            fileName = "audio.${MediaType.detectOr(bytes, options.mediaType).substringAfter('/')}",
            fileBytes = bytes,
            fileContentType = options.mediaType,
            fields = fields,
            headers = combineHeaders(headers, options.headers),
        )
        val response = result.value.jsonObject
        val words = response["words"]?.jsonArray.orEmpty().map { it.jsonObject }

        return TranscriptionResult(
            text = response["text"]?.jsonPrimitive?.content.orEmpty(),
            segments = words.map { word ->
                TranscriptionResult.Segment(
                    text = word["text"]?.jsonPrimitive?.content.orEmpty(),
                    startSecond = word["start"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    endSecond = word["end"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                )
            },
            language = response["language_code"]?.jsonPrimitive?.content,
            // There is no duration field: the last word's end is the only thing the response says about
            // how long the audio was.
            durationInSeconds = words.lastOrNull()?.get("end")?.jsonPrimitive?.content?.toDoubleOrNull(),
            warnings = warnings,
            request = result.requestInfo(),
            response = result.modalityResponse(modelId = modelId, body = result.value.toString()),
        )
    }

    /**
     * Null when no socket transport was supplied, which is the contract's own way of saying a model
     * cannot stream: only the client-taking constructor can build one, and a provider assembled without
     * a client has no way to open a session.
     */
    override suspend fun doStream(options: TranscriptionStreamOptions): TranscriptionStreamResult? {
        if (modelId != REALTIME_MODEL_ID) {
            throw UnsupportedFunctionalityError("streaming transcription with $modelId")
        }
        val socket = socket ?: return null

        val vendor = options.providerOptions?.forProvider(ELEVENLABS_PROVIDER_ID)
        val streaming = vendor?.optObject("streaming")
        val warnings = mutableListOf<Warning>()

        // The batch options are not merely ignored on this endpoint — they configure a decoder that is
        // not running. Warning names each one, so a caller who moved a working batch call to the socket
        // learns why the speaker labels vanished.
        for (option in BATCH_ONLY_OPTIONS) {
            if (vendor?.optElement(option) != null) {
                warnings += Warning.Unsupported(
                    feature = "providerOptions.elevenlabs.$option",
                    details = "ElevenLabs realtime transcription does not support $option.",
                )
            }
        }

        val includeTimestamps = streaming?.optBoolean("includeTimestamps") == true
        val includeLanguageDetection = streaming?.optBoolean("includeLanguageDetection") == true
        // ElevenLabs documents these as mutually exclusive, and language detection rides the
        // timestamp-bearing commit, so it needs the same field. Sending the pair yields a session that
        // silently reports neither rather than a rejection naming the conflict.
        if (streaming?.optBoolean("filterBackgroundAudio") == true &&
            (includeTimestamps || includeLanguageDetection)
        ) {
            throw InvalidArgumentError(
                message = "providerOptions.elevenlabs.streaming.filterBackgroundAudio cannot be " +
                    "combined with includeTimestamps or includeLanguageDetection.",
                argument = "providerOptions",
            )
        }

        val language = vendor?.optString("languageCode")
        val format = elevenLabsRealtimeFormat(options.inputAudioFormat)
        val url = elevenLabsRealtimeUrl(
            baseUrl = baseUrl,
            modelId = modelId,
            format = format,
            languageCode = language,
            streaming = streaming,
        )

        return TranscriptionStreamResult(
            stream = elevenLabsRealtimeStream(
                socket = socket,
                url = url,
                headers = combineHeaders(headers, options.headers),
                audio = options.audio,
                format = format,
                previousText = streaming?.optString("previousText"),
                mapper = ElevenLabsRealtimeMapper(
                    warnings = warnings,
                    includeTimestamps = includeTimestamps,
                    includeLanguageDetection = includeLanguageDetection,
                    includeRawChunks = options.includeRawChunks,
                    detectedLanguage = language,
                ),
            ),
            request = RequestInfo(body = url),
            response = ResponseInfo(metadata = ResponseMetadata(modelId = modelId)),
        )
    }
}

/** Options that configure the batch decoder, which the realtime endpoint does not run. */
private val BATCH_ONLY_OPTIONS = listOf(
    "diarize",
    "fileFormat",
    "numSpeakers",
    "tagAudioEvents",
    "timestampsGranularity",
)
