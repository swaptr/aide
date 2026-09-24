package com.sabreware.aide.aisdk.providers.assemblyai

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.JobStatus
import com.sabreware.aide.aisdk.util.PollPolicy
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.pollUntilDone
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The provider id, and the namespace AssemblyAI payloads file under. */
public const val ASSEMBLYAI_PROVIDER_ID: String = "assemblyai"

/**
 * AssemblyAI speech-to-text.
 *
 * Three steps rather than two: the audio is UPLOADED first, and the transcript job then references the
 * returned URL. There is no way to post audio and job together, so a client that models this as a single
 * submit-and-poll gets a 400 about a missing `audio_url`.
 *
 * Auth is a BARE key in the Authorization header — no `Bearer`, no `Token`. Adding a scheme is a 401 that
 * reads as an invalid key.
 */
public class AssemblyAiProvider(
    client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val pollPolicy: PollPolicy = PollPolicy(),
    private val elapsedMillis: () -> Long,
) : Provider {

    override val providerId: String = ASSEMBLYAI_PROVIDER_ID

    private val http = ProviderHttp(client)

    override fun transcriptionModel(modelId: String): TranscriptionModel = AssemblyAiTranscriptionModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        apiKey = apiKey,
        pollPolicy = pollPolicy,
        elapsedMillis = elapsedMillis,
    )

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.assemblyai.com/v2"

        /** Named in every model warning below, because the choice of model is the thing it explains. */
        public const val MODEL_DOCS_URL: String =
            "https://www.assemblyai.com/docs/pre-recorded-audio/select-the-speech-model"

        /** The one model the singular `speech_model` parameter still accepts. */
        internal const val LEGACY_MODEL = "best"
    }
}

/** Option name to wire name, for the keys AssemblyAI spells differently from the contract. */
private val ASSEMBLYAI_PARAM_NAMES = mapOf(
    "audioEndAt" to "audio_end_at",
    "audioStartFrom" to "audio_start_from",
    "autoChapters" to "auto_chapters",
    "autoHighlights" to "auto_highlights",
    "boostParam" to "boost_param",
    "contentSafety" to "content_safety",
    "contentSafetyConfidence" to "content_safety_confidence",
    "customSpelling" to "custom_spelling",
    "entityDetection" to "entity_detection",
    "filterProfanity" to "filter_profanity",
    "formatText" to "format_text",
    "iabCategories" to "iab_categories",
    "keytermsPrompt" to "keyterms_prompt",
    "languageCode" to "language_code",
    "languageConfidenceThreshold" to "language_confidence_threshold",
    "languageDetection" to "language_detection",
    "languageDetectionOptions" to "language_detection_options",
    "redactPii" to "redact_pii",
    "redactPiiAudio" to "redact_pii_audio",
    "redactPiiAudioOptions" to "redact_pii_audio_options",
    "redactPiiAudioQuality" to "redact_pii_audio_quality",
    "redactPiiPolicies" to "redact_pii_policies",
    "redactPiiReturnUnredacted" to "redact_pii_return_unredacted",
    "redactPiiSub" to "redact_pii_sub",
    "redactStaticEntities" to "redact_static_entities",
    "removeAudioTags" to "remove_audio_tags",
    "sentimentAnalysis" to "sentiment_analysis",
    "speakerLabels" to "speaker_labels",
    "speakerOptions" to "speaker_options",
    "speakersExpected" to "speakers_expected",
    "speechThreshold" to "speech_threshold",
    "summaryModel" to "summary_model",
    "summaryType" to "summary_type",
    "webhookAuthHeaderName" to "webhook_auth_header_name",
    "webhookAuthHeaderValue" to "webhook_auth_header_value",
    "webhookUrl" to "webhook_url",
    "wordBoost" to "word_boost",
)

/**
 * The keys inside AssemblyAI's three nested config objects.
 *
 * Nested rather than flat on the wire, so the rename has to reach one level down; the objects are fully
 * documented, which is why converting their keys cannot touch anything a caller invented.
 */
private val ASSEMBLYAI_NESTED_PARAM_NAMES = mapOf(
    "speaker_options" to mapOf(
        "minSpeakersExpected" to "min_speakers_expected",
        "maxSpeakersExpected" to "max_speakers_expected",
    ),
    "language_detection_options" to mapOf(
        "expectedLanguages" to "expected_languages",
        "fallbackLanguage" to "fallback_language",
        "codeSwitching" to "code_switching",
        "codeSwitchingConfidenceThreshold" to "code_switching_confidence_threshold",
    ),
    "redact_pii_audio_options" to mapOf(
        "returnRedactedNoSpeechAudio" to "return_redacted_no_speech_audio",
        "overrideAudioRedactionMethod" to "override_audio_redaction_method",
    ),
)

/** The audio-intelligence results that have no place in the contract's flat `segments`. */
private val ASSEMBLYAI_METADATA_KEYS = mapOf(
    "utterances" to "utterances",
    "sentiment_analysis_results" to "sentimentAnalysisResults",
    "entities" to "entities",
    "content_safety_labels" to "contentSafetyLabels",
    "iab_categories_result" to "iabCategoriesResult",
    "auto_highlights_result" to "autoHighlightsResult",
)

@OptIn(ExperimentalEncodingApi::class)
internal class AssemblyAiTranscriptionModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val apiKey: String,
    private val pollPolicy: PollPolicy,
    private val elapsedMillis: () -> Long,
) : TranscriptionModel {

    override val provider: String = ASSEMBLYAI_PROVIDER_ID

    override suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult {
        val bytes = when (val audio = options.audio) {
            is BinaryData.Bytes -> audio.value
            is BinaryData.Base64 -> Base64.decode(audio.value)
        }
        // Bare, with no scheme. Adding "Bearer" is a 401 that reads as an invalid key.
        val headers = combineHeaders(mapOf("Authorization" to apiKey), options.headers)
        val warnings = mutableListOf<Warning>()
        val vendorOptions = options.providerOptions?.get(ASSEMBLYAI_PROVIDER_ID)

        val uploadUrl = http.postRawBytes(
            url = "$baseUrl/upload",
            payload = bytes,
            // `application/octet-stream`, not the caller's media type. AssemblyAI's upload endpoint
            // stores whatever bytes it is given and sniffs the container itself; it is the transcript
            // job that decides how to decode them. The reference records the same header, and a
            // gateway that validates the declared type against the body rejects `audio/wav` for an mp3
            // the caller mislabelled — a failure at upload, before any transcription was attempted.
            contentType = "application/octet-stream",
            headers = headers,
        ).value.jsonObject["upload_url"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("AssemblyAI returned no upload_url")

        val job = http.postJson(
            url = "$baseUrl/transcript",
            body = requestBody(uploadUrl, vendorOptions, warnings),
            headers = headers,
        ).value.jsonObject
        val id = job["id"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("AssemblyAI returned no transcript id")

        val result = pollUntilDone(policy = pollPolicy, elapsedMillis = elapsedMillis) {
            val poll = http.getJson("$baseUrl/transcript/$id", headers)
            when (poll.value.jsonObject["status"]?.jsonPrimitive?.content) {
                "completed" -> JobStatus.Succeeded(poll)
                "queued", "processing" -> JobStatus.InProgress()
                else -> JobStatus.Failed(
                    poll.value.jsonObject["error"]?.jsonPrimitive?.content
                        ?: "AssemblyAI reported an error",
                )
            }
        }
        val finished = result.value.jsonObject

        val words = finished["words"]?.jsonArray.orEmpty().map { it.jsonObject }
        // AssemblyAI reports MILLISECONDS where the contract is seconds; passing them through yields
        // timings a thousand times too long, which looks like a hung player.
        val lastWordEnd = words.lastOrNull()?.get("end")?.jsonPrimitive?.content?.toDoubleOrNull()

        return TranscriptionResult(
            text = finished["text"]?.jsonPrimitive?.content.orEmpty(),
            segments = words.map { word ->
                TranscriptionResult.Segment(
                    text = word["text"]?.jsonPrimitive?.content.orEmpty(),
                    startSecond = (word["start"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) / MS,
                    endSecond = (word["end"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) / MS,
                )
            },
            language = finished["language_code"]?.jsonPrimitive?.content,
            durationInSeconds = finished["audio_duration"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: lastWordEnd?.let { it / MS },
            warnings = warnings,
            providerMetadata = providerMetadata(finished),
            response = result.modalityResponse(modelId = modelId, body = result.value.toString()),
        )
    }

    /**
     * The transcript job, with the model routed to whichever parameter accepts it.
     *
     * `speech_model` takes `best` and nothing else: every other model — `universal-2`, `universal-3-pro`,
     * `universal-3-5-pro` — is rejected there and has to go through the `speech_models` array. Sending
     * the singular unconditionally, which is what this used to do, meant AssemblyAI worked for exactly
     * one model and 400'd for every other.
     */
    private fun requestBody(
        uploadUrl: String,
        vendorOptions: JsonObject?,
        warnings: MutableList<Warning>,
    ): JsonObject = buildJsonObject {
        if (modelId == AssemblyAiProvider.LEGACY_MODEL) {
            put("speech_model", modelId)
            warnings += Warning.Deprecated(
                setting = "model '$modelId'",
                message = "The 'best' model is a legacy AssemblyAI model. Use 'universal-3-5-pro' " +
                    "instead. See documentation: ${AssemblyAiProvider.MODEL_DOCS_URL}",
            )
        } else {
            put("speech_models", buildJsonArray { add(modelId) })
            flagshipNudge(modelId)?.let { warnings += it }
        }
        vendorOptions?.forEach { (key, value) ->
            if (value !is JsonNull) put(ASSEMBLYAI_PARAM_NAMES[key] ?: key, nested(key, value))
        }
        vendorOptions?.let { warnings += conflictWarnings(it) }
        put("audio_url", uploadUrl)
    }
}

/** Renames the keys inside a documented nested config; anything else is copied untouched. */
private fun nested(key: String, value: JsonElement): JsonElement {
    val names = ASSEMBLYAI_NESTED_PARAM_NAMES[ASSEMBLYAI_PARAM_NAMES[key] ?: key] ?: return value
    val obj = value as? JsonObject ?: return value
    return JsonObject(obj.entries.associate { (inner, v) -> (names[inner] ?: inner) to v })
}

/**
 * The forward-looking nudge toward AssemblyAI's current flagship.
 *
 * Not a deprecation — both models still work — so the message says what to move to and stops there.
 */
private fun flagshipNudge(modelId: String): Warning? = when (modelId) {
    "universal-3-pro" -> Warning.Other(
        "'universal-3-5-pro' is AssemblyAI's latest flagship model and is set to replace " +
            "'universal-3-pro'. See ${AssemblyAiProvider.MODEL_DOCS_URL}",
    )

    "universal-2" -> Warning.Other(
        "'universal-3-5-pro' is AssemblyAI's latest flagship model. " +
            "See ${AssemblyAiProvider.MODEL_DOCS_URL}",
    )

    else -> null
}

/**
 * Options that only take effect alongside a prerequisite, and the deprecated boost pair.
 *
 * AssemblyAI either rejects the request outright or silently ignores the option, so warn rather than
 * repair: mutating a caller's input to make it valid hides which of two settings they actually wanted.
 */
private fun conflictWarnings(options: JsonObject): List<Warning> = buildList {
    val deprecatedBoost = listOf("wordBoost", "boostParam").filter { options.isSet(it) }
    if (deprecatedBoost.isNotEmpty()) {
        add(
            Warning.Deprecated(
                setting = deprecatedBoost.joinToString(", "),
                message = "'wordBoost' and 'boostParam' are deprecated and are rejected by " +
                    "'universal-3-pro' / 'universal-3-5-pro' and 'slam-1'. Use 'keytermsPrompt' instead.",
            ),
        )
    }
    val unredacted = options.isSet("redactPiiReturnUnredacted") || options.isSet("redactStaticEntities")
    if (unredacted && !options.isSet("redactPii")) {
        add(
            Warning.Other(
                "'redactPiiReturnUnredacted' and 'redactStaticEntities' require 'redactPii' to be " +
                    "enabled; AssemblyAI rejects the request otherwise.",
            ),
        )
    }
    if (options.isSet("redactPiiAudioOptions") && !options.isSet("redactPiiAudio")) {
        add(
            Warning.Other(
                "'redactPiiAudioOptions' only applies when 'redactPiiAudio' is enabled; it is " +
                    "otherwise ignored.",
            ),
        )
    }
    if (options.isSet("languageCode") && options.isSet("languageDetection")) {
        add(
            Warning.Other(
                "'languageDetection' cannot be combined with an explicit 'languageCode'; AssemblyAI " +
                    "rejects requests that set both.",
            ),
        )
    }
}

/**
 * Diarization and audio-intelligence results, namespaced rather than dropped.
 *
 * None of it fits the contract's flat `segments`, and it is the reason a caller chose AssemblyAI over the
 * cheap option. Timings inside these objects stay in MILLISECONDS, as AssemblyAI sends them — unlike
 * `segments`, which the contract defines in seconds.
 */
private fun providerMetadata(transcript: JsonObject): Map<String, JsonObject>? {
    val payload = ASSEMBLYAI_METADATA_KEYS.mapNotNull { (wire, name) ->
        transcript[wire]?.takeIf { it !is JsonNull }?.let { name to it }
    }
    return if (payload.isEmpty()) null else mapOf(ASSEMBLYAI_PROVIDER_ID to JsonObject(payload.toMap()))
}

/** Whether the caller actually set [key] — an explicit null means "leave it off", not "enable it". */
private fun JsonObject.isSet(key: String): Boolean = this[key].let { it != null && it !is JsonNull }

private const val MS = 1000
