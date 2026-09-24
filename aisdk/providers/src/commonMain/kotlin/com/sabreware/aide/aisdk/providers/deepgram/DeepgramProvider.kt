package com.sabreware.aide.aisdk.providers.deepgram

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The provider id, and the namespace Deepgram payloads file under. */
public const val DEEPGRAM_PROVIDER_ID: String = "deepgram"

/**
 * Deepgram speech-to-text.
 *
 * Unlike OpenAI, Deepgram takes the audio as a RAW request body rather than a multipart upload, with the
 * format declared in `Content-Type`. Sending multipart here does not fail cleanly — it transcribes the
 * MIME envelope as if it were audio.
 *
 * Auth is `Authorization: Token …`, not `Bearer`.
 *
 * Its text-to-speech half is the mirror image: the text is the only thing in the body and every other
 * setting is a query parameter. See [DeepgramSpeechModel].
 */
public class DeepgramProvider(
    client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : Provider {

    override val providerId: String = DEEPGRAM_PROVIDER_ID

    private val http = ProviderHttp(client).withErrorStructure(DeepgramErrors)

    override fun speechModel(modelId: String): SpeechModel = DeepgramSpeechModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        apiKey = apiKey,
    )

    override fun transcriptionModel(modelId: String): TranscriptionModel = DeepgramTranscriptionModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        apiKey = apiKey,
    )

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.deepgram.com/v1"
    }
}

/**
 * Deepgram's error shape, which shares no key with anyone else's.
 *
 * `{"err_code":"INVALID_QUERY_PARAMETER","err_msg":"Invalid 'model' value of …","request_id":"…"}` —
 * flat, and named `err_msg`, so the default parser finds nothing and a caller is left with the raw body
 * excerpt for what is usually a one-line, actionable complaint about a query parameter.
 */
internal val DeepgramErrors: ProviderErrorStructure = ProviderErrorStructure(
    extractMessage = { body ->
        (body as? JsonObject)?.get("err_msg")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
    },
)

/**
 * The option names whose wire spelling differs from the contract's.
 *
 * Everything else — `language`, `diarize`, `punctuate`, `redact` and the rest — is already spelled the
 * way Deepgram spells it, and unrecognised keys go out under their own name so a parameter Deepgram adds
 * tomorrow is reachable without a release here.
 */
private val DEEPGRAM_PARAM_NAMES = mapOf(
    "detectEntities" to "detect_entities",
    "detectLanguage" to "detect_language",
    "fillerWords" to "filler_words",
    "smartFormat" to "smart_format",
    "uttSplit" to "utt_split",
)

@OptIn(ExperimentalEncodingApi::class)
internal class DeepgramTranscriptionModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val apiKey: String,
) : TranscriptionModel {

    override val provider: String = DEEPGRAM_PROVIDER_ID

    override suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult {
        val bytes = when (val audio = options.audio) {
            is BinaryData.Bytes -> audio.value
            is BinaryData.Base64 -> Base64.decode(audio.value)
        }

        // Nothing beyond the model by default. `utterances` and `detect_language` used to be pinned on
        // here, and both are billed features: every caller paid for word-level diarization and language
        // detection whether or not it read either.
        val warnings = mutableListOf<Warning>()
        val query = buildList {
            add("model" to modelId)
            options.providerOptions?.get(DEEPGRAM_PROVIDER_ID)?.forEach { (key, value) ->
                val rendered = queryValue(value)
                if (rendered == null) {
                    // A nested object has no query-string form; silence here was an option the caller
                    // set, paid nothing for, and never learned was ignored.
                    warnings += Warning.Unsupported(key, "Deepgram options must be primitives or arrays of them.")
                } else {
                    add((DEEPGRAM_PARAM_NAMES[key] ?: key) to rendered)
                }
            }
        }

        val result = http.postRawBytes(
            url = "$baseUrl/listen?" + query.joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" },
            payload = bytes,
            contentType = options.mediaType,
            headers = combineHeaders(mapOf("Authorization" to "Token $apiKey"), options.headers),
        )
        val response = result.value.jsonObject

        val channel = response["results"]?.jsonObject
            ?.get("channels")?.jsonArray?.firstOrNull()?.jsonObject
        val alternative = channel?.get("alternatives")?.jsonArray?.firstOrNull()?.jsonObject

        return TranscriptionResult(
            text = alternative?.get("transcript")?.jsonPrimitive?.content.orEmpty(),
            // Words, not utterances: utterances only exist when the caller paid for them, and a
            // consumer comparing two vendors' segments has to be comparing the same unit.
            segments = alternative?.get("words")?.jsonArray.orEmpty().map { entry ->
                val word = entry.jsonObject
                TranscriptionResult.Segment(
                    text = word["word"]?.jsonPrimitive?.content.orEmpty(),
                    startSecond = word["start"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    endSecond = word["end"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                )
            },
            language = channel?.get("detected_language")?.jsonPrimitive?.content,
            durationInSeconds = response["metadata"]?.jsonObject
                ?.get("duration")?.jsonPrimitive?.content?.toDoubleOrNull(),
            warnings = warnings,
            response = result.modalityResponse(modelId = modelId, body = result.value.toString()),
        )
    }
}

/**
 * One option rendered for a query string, or null where it must not be sent.
 *
 * Deepgram takes every parameter in the URL, so a list has to be flattened; it reads a repeated term as
 * comma-separated, which is also what the reference's `String(value)` produces for an array.
 */
private fun queryValue(value: JsonElement): String? = when (value) {
    is JsonNull -> null
    is JsonPrimitive -> value.content
    is JsonArray -> value.joinToString(",") { (it as? JsonPrimitive)?.content ?: it.toString() }
    else -> value.toString()
}
