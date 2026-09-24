package com.sabreware.aide.aisdk.providers.groq

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optDouble
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.MediaType
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The provider id, and the namespace Groq payloads file under. */
public const val GROQ_PROVIDER_ID: String = "groq"

/**
 * Groq's Whisper endpoint.
 *
 * Groq's chat models are OpenAI-compatible and are served from the table; `/audio/transcriptions` is
 * close to OpenAI's but not the same, and the differences are the ones that decide whether a request
 * works: a list option is a repeated `name[]` field rather than a JSON array, and Groq returns
 * `segments` only for `verbose_json` while returning `words` for a word granularity — so a reader that
 * knows only one of the two produces an empty `segments` for a transcript full of timings.
 */
public class GroqProvider internal constructor(
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

    override val providerId: String = GROQ_PROVIDER_ID

    private val endpoint = baseUrl.trimEnd('/')

    private val headers = combineHeaders(mapOf("Authorization" to "Bearer $apiKey"), extraHeaders)

    override fun transcriptionModel(modelId: String): TranscriptionModel =
        GroqTranscriptionModel(modelId, http, endpoint, headers)

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.groq.com/openai/v1"
    }
}

@OptIn(ExperimentalEncodingApi::class)
internal class GroqTranscriptionModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : TranscriptionModel {

    override val provider: String = GROQ_PROVIDER_ID

    override suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult {
        val bytes = when (val audio = options.audio) {
            is BinaryData.Bytes -> audio.value
            is BinaryData.Base64 -> Base64.decode(audio.value)
        }
        val vendor = options.providerOptions?.forProvider(GROQ_PROVIDER_ID)
        val warnings = mutableListOf<Warning>()

        val fields = buildList {
            add("model" to modelId)
            vendor?.optString("language")?.let { add("language" to it) }
            vendor?.optString("prompt")?.let { add("prompt" to it) }
            vendor?.optDouble("temperature")?.let { add("temperature" to it.toString()) }
            responseFormat(vendor, warnings)?.let { add("response_format" to it) }
            // The `[]` suffix is Groq's own spelling of a repeated field. Sending the bare name once
            // with a JSON array is accepted and ignored, so the timings simply never arrive.
            vendor?.optArray("timestampGranularities")?.forEach {
                add("timestamp_granularities[]" to it.jsonPrimitive.content)
            }
        }

        val result = http.postMultipart(
            url = "$baseUrl/audio/transcriptions",
            fileField = "file",
            // Sniffed rather than trusted: Whisper keys its decoder off the extension, and a caller's
            // declared media type can be absent or wrong.
            fileName = "audio.${MediaType.detectOr(bytes, options.mediaType).substringAfter('/')}",
            fileBytes = bytes,
            fileContentType = options.mediaType,
            fields = fields,
            headers = combineHeaders(headers, options.headers),
        )
        val response = result.value.jsonObject

        return TranscriptionResult(
            text = response["text"]?.jsonPrimitive?.content.orEmpty(),
            segments = segments(response),
            language = response["language"]?.jsonPrimitive?.content,
            durationInSeconds = response["duration"]?.jsonPrimitive?.content?.toDoubleOrNull(),
            warnings = warnings,
            request = result.requestInfo(),
            response = result.modalityResponse(modelId = modelId, body = result.value.toString()),
        )
    }

    /**
     * `text` is dropped rather than forwarded.
     *
     * Groq answers it with a bare transcript instead of a JSON document, which this transport cannot
     * read — and the field carries nothing the contract needs, since a plain-text response has neither
     * segments nor a language. Omitting it leaves Groq's own default, which returns the same transcript
     * inside an envelope; forwarding it would turn every such call into a parse failure.
     */
    private fun responseFormat(vendor: JsonObject?, warnings: MutableList<Warning>): String? {
        val requested = vendor?.optString("responseFormat") ?: return null
        if (requested != "text") return requested
        warnings += Warning.Unsupported(
            feature = "providerOptions.groq.responseFormat",
            details = "Groq's 'text' response format returns a bare transcript rather than JSON. " +
                "The request was sent without it; the transcript is unchanged.",
        )
        return null
    }
}

/**
 * Segments, from whichever of the two arrays this response format produced.
 *
 * `verbose_json` returns `segments`; a word granularity returns `words`, spelling the text `word`.
 * Reading only the first is an empty `segments` for a transcript that is full of timings.
 */
private fun segments(response: JsonObject): List<TranscriptionResult.Segment> {
    response["segments"]?.jsonArray?.let { segments ->
        return segments.map { it.jsonObject.toSegment("text") }
    }
    return response["words"]?.jsonArray.orEmpty().map { it.jsonObject.toSegment("word") }
}

private fun JsonObject.toSegment(textKey: String) = TranscriptionResult.Segment(
    text = this[textKey]?.jsonPrimitive?.content.orEmpty(),
    startSecond = this["start"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
    endSecond = this["end"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
)
