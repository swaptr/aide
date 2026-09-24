package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.providers.google.GoogleErrorStructure
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Cloud Speech-to-Text v2, for the non-Gemini transcription ids (Chirp, telephony models).
 *
 * A third Google host again: Speech-to-Text regions are their OWN menu — Chirp lives in regions Vertex
 * does not serve and vice versa — so the region is overridable per call via
 * `providerOptions["google-vertex"].region` rather than inherited blindly from the provider's Vertex
 * location.
 *
 * The request declares no media type: `autoDecodingConfig` has the service sniff the container itself,
 * which is why [TranscriptionCallOptions.mediaType] is not sent — the field is honoured by not being
 * needed, not ignored.
 *
 * Word time offsets and automatic punctuation default ON (the reference's choice): timings are what
 * populate [TranscriptionResult.segments], and a caller who wants them off says so through
 * `enableWordTimeOffsets` / `enableAutomaticPunctuation`.
 */
internal class VertexTranscriptionModel(
    override val modelId: String,
    http: ProviderHttp,
    private val projectId: String,
    private val location: String,
    private val headers: suspend () -> Map<String, String>,
) : TranscriptionModel {

    override val provider: String = VERTEX_PROVIDER_ID

    private val http = http.withErrorStructure(GoogleErrorStructure)

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult {
        val vendor = options.providerOptions.vertexVendorOptions()
        val region = vendor?.optString("region") ?: location

        val body = buildJsonObject {
            putJsonObject("config") {
                put("model", modelId)
                put("languageCodes", vendor?.optArray("languageCodes") ?: DEFAULT_LANGUAGE_CODES)
                // Sniffed server-side; see the class KDoc for why no media type is sent.
                putJsonObject("autoDecodingConfig") {}
                putJsonObject("features") {
                    put("enableWordTimeOffsets", vendor?.optBoolean("enableWordTimeOffsets") ?: true)
                    put(
                        "enableAutomaticPunctuation",
                        vendor?.optBoolean("enableAutomaticPunctuation") ?: true,
                    )
                }
            }
            put(
                "content",
                when (val audio = options.audio) {
                    is BinaryData.Base64 -> audio.value
                    is BinaryData.Bytes -> Base64.encode(audio.value)
                },
            )
        }

        val host = if (region == "global") "speech.googleapis.com" else "$region-speech.googleapis.com"
        val url = "https://$host/v2/projects/$projectId/locations/$region/recognizers/_:recognize"
        val result = http.postJson(url, body, combineHeaders(headers(), options.headers))

        // Results are sequential portions of the audio; the transcript is their primary alternatives
        // joined, and the segments are the word timings inside them.
        val results = result.value.jsonObject["results"]?.jsonArray.orEmpty().map { it.jsonObject }
        val text = results
            .mapNotNull { it.optArray("alternatives")?.firstOrNull()?.jsonObject?.optString("transcript") }
            .joinToString(" ")
            .trim()
        val segments = results.flatMap { entry ->
            entry.optArray("alternatives")?.firstOrNull()?.jsonObject
                ?.optArray("words").orEmpty().mapNotNull { element ->
                    val word = element.jsonObject
                    val start = parseVertexDurationSeconds(word.optString("startOffset"))
                    val end = parseVertexDurationSeconds(word.optString("endOffset"))
                    val spoken = word.optString("word")
                    if (spoken == null || start == null || end == null) {
                        null
                    } else {
                        TranscriptionResult.Segment(text = spoken, startSecond = start, endSecond = end)
                    }
                }
        }

        return TranscriptionResult(
            text = text,
            segments = segments,
            language = results.firstOrNull()?.optString("languageCode").toIso639(),
            durationInSeconds = parseVertexDurationSeconds(
                result.value.jsonObject.optObject("metadata")?.optString("totalBilledDuration"),
            ),
            request = result.requestInfo(),
            response = result.modalityResponse(modelId = modelId),
        )
    }

    private companion object {
        /** `auto` asks the service to detect; the field itself is required. */
        val DEFAULT_LANGUAGE_CODES: JsonArray = buildJsonArray { add("auto") }
    }
}

/**
 * BCP-47 → bare ISO 639-1, the contract's language shape: `en-US` → `en`.
 *
 * Only a two-letter primary subtag qualifies — a three-letter code or an unparseable tag is reported as
 * absent rather than truncated into a code it is not.
 */
private fun String?.toIso639(): String? =
    this?.substringBefore('-')?.takeIf { it.length == 2 }?.lowercase()
