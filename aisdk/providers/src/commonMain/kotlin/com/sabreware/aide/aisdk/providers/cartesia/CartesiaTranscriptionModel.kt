package com.sabreware.aide.aisdk.providers.cartesia

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.NoContentGeneratedError
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
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optElement
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.MediaType
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderSocket
import com.sabreware.aide.aisdk.util.combineHeaders
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Whether [modelId] names the socket-only family.
 *
 * `ink-2` and its variants have no batch endpoint at all, so posting a file to them is not a slower
 * path to the same transcript — it is a 4xx about a model the caller believes is supported.
 */
private fun isStreamOnly(modelId: String): Boolean =
    modelId == "ink-2" || modelId.startsWith("ink-2-")

/**
 * Cartesia speech-to-text.
 *
 * A multipart upload naming the model in a `model` field, against the same version-pinned API as the
 * speech half — so it inherits the `Cartesia-Version` header, without which the request is rejected
 * rather than defaulted.
 */
@OptIn(ExperimentalEncodingApi::class)
internal class CartesiaTranscriptionModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
    private val apiVersion: String,
    /**
     * Absent unless the caller handed us the client. Live transcription is a socket protocol, and this
     * module never builds a transport of its own — so a model constructed from a bare [ProviderHttp]
     * offers no live session and says so by returning null from [doStream], rather than by opening one
     * that cannot connect.
     */
    private val socket: ProviderSocket? = null,
) : TranscriptionModel {

    override val provider: String = CARTESIA_PROVIDER_ID

    override suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult {
        if (isStreamOnly(modelId)) {
            throw UnsupportedFunctionalityError("non-streaming transcription with $modelId")
        }
        val warnings = mutableListOf<Warning>()
        val vendor = options.providerOptions?.forProvider(CARTESIA_PROVIDER_ID)
        val bytes = when (val audio = options.audio) {
            is BinaryData.Bytes -> audio.value
            is BinaryData.Base64 -> Base64.decode(audio.value)
        }

        if (vendor?.optElement("streaming") != null) {
            warnings += Warning.Unsupported(
                feature = "providerOptions.cartesia.streaming",
                details = "Cartesia batch transcription does not support streaming options.",
            )
        }

        val fields = buildList {
            add("model" to modelId)
            vendor?.optString("language")?.let { add("language" to it) }
            // Repeated under one name, brackets included: Cartesia reads the pair as a list, and a
            // single comma-joined value is rejected as an unknown granularity.
            vendor?.optArray("timestampGranularities")?.forEach { granularity ->
                (granularity as? JsonPrimitive)?.content
                    ?.let { add("timestamp_granularities[]" to it) }
            }
        }

        val result = http.postMultipart(
            url = "$baseUrl/stt",
            fileField = "file",
            fileName = "audio.${MediaType.detectOr(bytes, options.mediaType).substringAfter('/')}",
            fileBytes = bytes,
            fileContentType = options.mediaType,
            fields = fields,
            headers = combineHeaders(headers, options.headers),
        )
        val response = result.value.jsonObject

        return TranscriptionResult(
            text = response["text"]?.jsonPrimitive?.content.orEmpty(),
            segments = response["words"]?.jsonArray.orEmpty().map { entry ->
                val word = entry.jsonObject
                TranscriptionResult.Segment(
                    // Cartesia calls the field `word`, not `text`, in the one place the contract calls
                    // it text.
                    text = word["word"]?.jsonPrimitive?.content.orEmpty(),
                    startSecond = word["start"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    endSecond = word["end"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                )
            },
            language = response["language"]?.jsonPrimitive?.content,
            durationInSeconds = response["duration"]?.jsonPrimitive?.content?.toDoubleOrNull(),
            warnings = warnings,
            request = result.requestInfo(),
            response = result.modalityResponse(modelId = modelId, body = result.value.toString()),
        )
    }

    /**
     * Ink 2 live transcription, over a socket.
     *
     * Two things are decided before the socket opens, and both are silent failures otherwise. The
     * session authenticates with a **short-lived access token** minted over HTTP rather than with the
     * API key, so the key never reaches a URL. And turn detection selects a different ENDPOINT, not a
     * flag — see [cartesiaStreamingUrl].
     */
    override suspend fun doStream(options: TranscriptionStreamOptions): TranscriptionStreamResult? {
        if (!isStreamOnly(modelId)) {
            throw UnsupportedFunctionalityError("streaming transcription with $modelId")
        }
        val live = socket ?: return null

        val warnings = mutableListOf<Warning>()
        val vendor = options.providerOptions?.forProvider(CARTESIA_PROVIDER_ID)
        val streaming = vendor?.optObject("streaming")
        val language = vendor?.optString("language")

        // Ink 2 is English-only today. Sending another language is accepted at the handshake and then
        // transcribed as English, so the mismatch never surfaces as an error — only as a wrong
        // transcript.
        if (language != null && language != "en") {
            throw InvalidArgumentError(
                message = "Cartesia Ink 2 currently supports English only.",
                argument = "providerOptions.cartesia.language",
            )
        }
        if (vendor?.optArray("timestampGranularities") != null) {
            warnings += Warning.Unsupported(
                feature = "providerOptions.cartesia.timestampGranularities",
                details = "Cartesia streaming transcription does not support timestamp granularities.",
            )
        }

        val encoding = cartesiaResolvedEncoding(options.inputAudioFormat, streaming, warnings)
        val useTurnDetection = streaming?.optBoolean("turnDetection") != false
        val callHeaders = combineHeaders(headers, options.headers)
        val token = http.postJson(
            url = "$baseUrl/access-token",
            body = CARTESIA_ACCESS_TOKEN_BODY,
            headers = callHeaders,
        ).value.jsonObject["token"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("Cartesia returned no streaming access token.")

        val url = cartesiaStreamingUrl(
            baseUrl = baseUrl,
            apiVersion = apiVersion,
            modelId = modelId,
            encoding = encoding,
            format = options.inputAudioFormat,
            language = language,
            token = token,
            useTurnDetection = useTurnDetection,
        )

        return TranscriptionStreamResult(
            stream = cartesiaRealtimeStream(
                socket = live,
                url = url,
                // The token authenticates the URL; the API key has no business on this socket, and a
                // header copy of it would be a second place for a long-lived credential to be logged.
                headers = emptyMap(),
                audio = options.audio,
                useTurnDetection = useTurnDetection,
                mapper = CartesiaRealtimeMapper(
                    warnings = warnings,
                    language = language ?: "en",
                    useTurnDetection = useTurnDetection,
                    includeRawChunks = options.includeRawChunks,
                ),
            ),
            // The URL minus its access token — see [cartesiaRedactedUrl]. There is no request body.
            request = RequestInfo(body = cartesiaRedactedUrl(url)),
            response = ResponseInfo(metadata = ResponseMetadata(modelId = modelId)),
        )
    }
}
