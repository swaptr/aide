package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.InvalidArgumentError
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Gemini transcription on Vertex — and a different wire from the Gemini API's own transcription.
 *
 * The Gemini API serves `gemini-3.5-transcribe` through its Interactions surface (`/interactions`,
 * snake_case config — see [com.sabreware.aide.aisdk.providers.google.GoogleTranscriptionModel]); Vertex
 * serves the SAME model id through `generateContent`, with the audio as an `inlineData` part and the
 * recognition options in `generationConfig.audioTranscriptionConfig`, camelCase. Pointing either
 * implementation at the other host produces a 404, which is exactly why they are two classes and not a
 * base-URL parameter.
 *
 * Word timings come back inside `audioTranscription.words` on the answer's parts, and become
 * [TranscriptionResult.segments]; plain text parts win over the transcription block's own text when
 * both are present, matching the reference.
 *
 * The `-live` variants are a Gemini Live WebSocket session (`LlmBidiService/BidiGenerateContent`),
 * deliberately not implemented here — the socket half of transcription lands with the live-STT work.
 * A unary call on a live id fails loudly instead of posting to an endpoint that would reject it.
 */
internal class VertexGeminiTranscriptionModel(
    override val modelId: String,
    http: ProviderHttp,
    private val baseUrl: String,
    private val headers: suspend () -> Map<String, String>,
) : TranscriptionModel {

    override val provider: String = VERTEX_PROVIDER_ID

    private val http = http.withErrorStructure(GoogleErrorStructure)

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult {
        if (modelId.contains("-live")) {
            throw InvalidArgumentError(
                argument = "modelId",
                message = "Model '$modelId' only supports streaming transcription. " +
                    "Use a unary model such as 'gemini-3.5-transcribe'.",
            )
        }

        val vendor = options.providerOptions.vertexVendorOptions()
        val transcriptionConfig = buildAudioTranscriptionConfig(vendor)

        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", options.mediaType)
                                put(
                                    "data",
                                    when (val audio = options.audio) {
                                        is BinaryData.Base64 -> audio.value
                                        is BinaryData.Bytes -> Base64.encode(audio.value)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (transcriptionConfig != null) {
                putJsonObject("generationConfig") { put("audioTranscriptionConfig", transcriptionConfig) }
            }
        }

        val result = http.postJson(
            url = "$baseUrl/models/$modelId:generateContent",
            body = body,
            headers = combineHeaders(headers(), options.headers),
        )

        val parts = result.value.jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.optObject("content")?.optArray("parts").orEmpty().map { it.jsonObject }

        val plainText = parts.mapNotNull { it.optString("text") }.joinToString("")
        val transcriptionText = parts
            .mapNotNull { it.optObject("audioTranscription")?.optString("text") }
            .joinToString("")

        var language: String? = null
        val segments = mutableListOf<TranscriptionResult.Segment>()
        for (part in parts) {
            val transcription = part.optObject("audioTranscription") ?: continue
            if (language == null) language = transcription.optString("languageCode")
            transcription.optArray("words").orEmpty().forEach { element ->
                val word = element.jsonObject
                val spoken = word.optString("word")
                val start = parseVertexDurationSeconds(word.optString("startOffset"))
                val end = parseVertexDurationSeconds(word.optString("endOffset"))
                if (spoken != null && start != null && end != null) {
                    segments += TranscriptionResult.Segment(text = spoken, startSecond = start, endSecond = end)
                }
            }
        }

        return TranscriptionResult(
            text = plainText.ifEmpty { transcriptionText },
            segments = segments,
            language = language,
            request = result.requestInfo(),
            providerMetadata = result.value.jsonObject.optObject("usageMetadata")?.let {
                mapOf(VERTEX_PROVIDER_ID to buildJsonObject { put("usageMetadata", it) })
            },
            response = result.modalityResponse(modelId = modelId),
        )
    }
}

/**
 * `AudioTranscriptionConfig`, camelCase, only the fields the caller set — an empty config is omitted
 * entirely rather than sent as `{}`.
 */
private fun buildAudioTranscriptionConfig(vendor: JsonObject?): JsonObject? {
    if (vendor == null) return null
    val config = buildJsonObject {
        vendor.optArray("languageCodes")?.let { put("languageCodes", it) }
        vendor.optArray("customVocabulary")?.let { put("customVocabulary", it) }
        vendor.optBoolean("wordTimestamp")?.let { put("wordTimestamp", it) }
        vendor.optBoolean("diarization")?.let { put("diarization", it) }
        vendor.optString("mode")?.let { put("mode", it) }
    }
    return config.takeIf { it.isNotEmpty() }
}
