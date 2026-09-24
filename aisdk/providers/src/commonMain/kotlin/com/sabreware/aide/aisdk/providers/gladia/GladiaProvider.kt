package com.sabreware.aide.aisdk.providers.gladia

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.util.JobStatus
import com.sabreware.aide.aisdk.util.MediaType
import com.sabreware.aide.aisdk.util.PollPolicy
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.parseJsonObject
import com.sabreware.aide.aisdk.util.pollUntilDone
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The provider id, and the namespace Gladia payloads file under. */
public const val GLADIA_PROVIDER_ID: String = "gladia"

/**
 * Gladia speech-to-text.
 *
 * Three steps, like AssemblyAI: upload, start a job against the returned URL, poll the `result_url` the
 * job hands back. Auth is `x-gladia-key`, not a bearer.
 *
 * Its result nests deeply — `result.transcription.full_transcript`, with the duration under
 * `result.metadata` — so the shape is worth reading once here rather than rediscovering from a null.
 */
public class GladiaProvider(
    client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val pollPolicy: PollPolicy = PollPolicy(),
    private val elapsedMillis: () -> Long,
) : Provider {

    override val providerId: String = GLADIA_PROVIDER_ID

    private val http = ProviderHttp(client)

    override fun transcriptionModel(modelId: String): TranscriptionModel = GladiaTranscriptionModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        apiKey = apiKey,
        pollPolicy = pollPolicy,
        elapsedMillis = elapsedMillis,
    )

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.gladia.io"
    }
}

/** Option name to wire name, for the keys Gladia spells differently from the contract. */
private val GLADIA_PARAM_NAMES = mapOf(
    "audioToLlm" to "audio_to_llm",
    "audioToLlmConfig" to "audio_to_llm_config",
    "callbackConfig" to "callback_config",
    "codeSwitchingConfig" to "code_switching_config",
    "contextPrompt" to "context_prompt",
    "customMetadata" to "custom_metadata",
    "customSpelling" to "custom_spelling",
    "customSpellingConfig" to "custom_spelling_config",
    "customVocabulary" to "custom_vocabulary",
    "customVocabularyConfig" to "custom_vocabulary_config",
    "detectLanguage" to "detect_language",
    "diarizationConfig" to "diarization_config",
    "displayMode" to "display_mode",
    "enableCodeSwitching" to "enable_code_switching",
    "nameConsistency" to "name_consistency",
    "namedEntityRecognition" to "named_entity_recognition",
    "punctuationEnhanced" to "punctuation_enhanced",
    "sentimentAnalysis" to "sentiment_analysis",
    "structuredDataExtraction" to "structured_data_extraction",
    "structuredDataExtractionConfig" to "structured_data_extraction_config",
    "subtitlesConfig" to "subtitles_config",
    "summarizationConfig" to "summarization_config",
    "translationConfig" to "translation_config",
)

/**
 * The keys inside Gladia's documented config objects.
 *
 * Renaming reaches exactly one level, and only for these four: `custom_metadata` is arbitrary caller
 * data and `custom_spelling_config.spelling_dictionary` is keyed by the caller's own words, so a blanket
 * camel-to-snake pass over the whole payload would rewrite a user's key and silently change what they
 * asked for.
 */
private val GLADIA_NESTED_PARAM_NAMES = mapOf(
    "custom_vocabulary_config" to mapOf("defaultIntensity" to "default_intensity"),
    "subtitles_config" to mapOf(
        "minimumDuration" to "minimum_duration",
        "maximumDuration" to "maximum_duration",
        "maximumCharactersPerRow" to "maximum_characters_per_row",
        "maximumRowsPerCaption" to "maximum_rows_per_caption",
    ),
    "diarization_config" to mapOf(
        "numberOfSpeakers" to "number_of_speakers",
        "minSpeakers" to "min_speakers",
        "maxSpeakers" to "max_speakers",
    ),
    "translation_config" to mapOf(
        "targetLanguages" to "target_languages",
        "matchOriginalUtterances" to "match_original_utterances",
    ),
    "custom_spelling_config" to mapOf("spellingDictionary" to "spelling_dictionary"),
)

@OptIn(ExperimentalEncodingApi::class)
internal class GladiaTranscriptionModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val apiKey: String,
    private val pollPolicy: PollPolicy,
    private val elapsedMillis: () -> Long,
) : TranscriptionModel {

    override val provider: String = GLADIA_PROVIDER_ID

    override suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult {
        val bytes = when (val audio = options.audio) {
            is BinaryData.Bytes -> audio.value
            is BinaryData.Base64 -> Base64.decode(audio.value)
        }
        val headers = combineHeaders(mapOf("x-gladia-key" to apiKey), options.headers)

        val audioUrl = http.postMultipart(
            url = "$baseUrl/v2/upload",
            fileField = "audio",
            fileName = "audio.${MediaType.detectOr(bytes, options.mediaType).substringAfter('/')}",
            fileBytes = bytes,
            fileContentType = options.mediaType,
            headers = headers,
        ).value.jsonObject["audio_url"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("Gladia returned no audio_url")

        val resultUrl = http.postJson(
            url = "$baseUrl/v2/pre-recorded",
            body = buildJsonObject {
                options.providerOptions?.get(GLADIA_PROVIDER_ID)?.forEach { (key, value) ->
                    if (value !is JsonNull) put(GLADIA_PARAM_NAMES[key] ?: key, nested(key, value))
                }
                put("audio_url", audioUrl)
            },
            headers = headers,
        ).value.jsonObject["result_url"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("Gladia returned no result_url")

        // The result URL is whatever the job named, so it is fetched with the SSRF guard on and the key
        // pinned to our own origin: a vendor response naming a foreign host would otherwise be handed
        // the API key, which is a credential-exfiltration primitive rather than a transcription bug.
        val poll = pollUntilDone(policy = pollPolicy, elapsedMillis = elapsedMillis) {
            val response = http.getBytes(resultUrl, headers, trustedOrigin = baseUrl)
            val body = parseJsonObject(response.value.decodeToString())
            when (body["status"]?.jsonPrimitive?.content) {
                "done" -> JobStatus.Succeeded(response.map { body })
                "queued", "processing" -> JobStatus.InProgress()
                else -> JobStatus.Failed("Gladia reported status ${body["status"]}")
            }
        }
        val finished = poll.value

        // Each level down is a place a client silently gets an empty transcript, so each one that is
        // missing fails here instead: "" is indistinguishable from a genuinely silent recording.
        val result = finished["result"]?.jsonObject
            ?: throw NoContentGeneratedError("Gladia reported done with no result")
        val transcription = result["transcription"]?.jsonObject
            ?: throw NoContentGeneratedError("Gladia returned a result with no transcription")
        val text = transcription["full_transcript"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("Gladia returned a transcription with no full_transcript")

        return TranscriptionResult(
            text = text,
            segments = transcription["utterances"]?.jsonArray.orEmpty().map { entry ->
                val utterance = entry.jsonObject
                TranscriptionResult.Segment(
                    text = utterance["text"]?.jsonPrimitive?.content.orEmpty(),
                    startSecond = utterance["start"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    endSecond = utterance["end"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                )
            },
            language = transcription["languages"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content,
            durationInSeconds = result["metadata"]?.jsonObject
                ?.get("audio_duration")?.jsonPrimitive?.content?.toDoubleOrNull(),
            // Summaries, translations, moderation and named entities all live in the job payload and
            // have nowhere to go in the contract; namespaced, they survive.
            providerMetadata = mapOf(GLADIA_PROVIDER_ID to finished),
            response = poll.modalityResponse(modelId = modelId, body = finished.toString()),
        )
    }
}

/** Renames the keys inside a documented nested config; anything else is copied untouched. */
private fun nested(key: String, value: JsonElement): JsonElement {
    val names = GLADIA_NESTED_PARAM_NAMES[GLADIA_PARAM_NAMES[key] ?: key] ?: return value
    val obj = value as? JsonObject ?: return value
    return JsonObject(obj.entries.associate { (inner, v) -> (names[inner] ?: inner) to v })
}
