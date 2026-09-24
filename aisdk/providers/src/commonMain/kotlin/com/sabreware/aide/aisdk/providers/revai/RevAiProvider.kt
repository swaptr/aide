package com.sabreware.aide.aisdk.providers.revai

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
import com.sabreware.aide.aisdk.util.pollUntilDone
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The provider id, and the namespace Rev AI payloads file under. */
public const val REVAI_PROVIDER_ID: String = "revai"

/**
 * Rev AI speech-to-text.
 *
 * Asynchronous, with a transcript fetched from a THIRD endpoint once the job finishes — and that fetch
 * needs a vendor `Accept` header, because the default response is a different format entirely.
 *
 * Its transcript is also shaped unlike anyone else's: monologues of ELEMENTS, where each element is a
 * word or a punctuation mark with its own timestamps. Text is reassembled by concatenating element
 * values, and a client that expects sentence-level segments finds none.
 */
public class RevAiProvider(
    client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val pollPolicy: PollPolicy = PollPolicy(),
    private val elapsedMillis: () -> Long,
) : Provider {

    override val providerId: String = REVAI_PROVIDER_ID

    private val http = ProviderHttp(client)

    override fun transcriptionModel(modelId: String): TranscriptionModel = RevAiTranscriptionModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        apiKey = apiKey,
        pollPolicy = pollPolicy,
        elapsedMillis = elapsedMillis,
    )

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.rev.ai/speechtotext/v1"

        /** Without this the transcript endpoint returns a different format than the JSON we parse. */
        public const val TRANSCRIPT_ACCEPT: String = "application/vnd.rev.transcript.v1.0+json"
    }
}

@OptIn(ExperimentalEncodingApi::class)
internal class RevAiTranscriptionModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val apiKey: String,
    private val pollPolicy: PollPolicy,
    private val elapsedMillis: () -> Long,
) : TranscriptionModel {

    override val provider: String = REVAI_PROVIDER_ID

    override suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult {
        val bytes = when (val audio = options.audio) {
            is BinaryData.Bytes -> audio.value
            is BinaryData.Base64 -> Base64.decode(audio.value)
        }
        val headers = combineHeaders(mapOf("Authorization" to "Bearer $apiKey"), options.headers)

        // Everything about the job other than the media rides in one `config` part. Omitting it — which
        // is what this used to do — means the model is never named, so `machine` / `human` / `low_cost`
        // is a no-op transcribed and billed at whatever the account defaults to.
        val config = buildJsonObject {
            put("transcriber", modelId)
            // Rev AI's own option names are already its wire names, so they go out verbatim.
            options.providerOptions?.get(REVAI_PROVIDER_ID)
                ?.forEach { (key, value) -> if (value !is JsonNull) put(key, value) }
        }

        val submitted = http.postMultipart(
            url = "$baseUrl/jobs",
            fileField = "media",
            fileName = "audio.${MediaType.detectOr(bytes, options.mediaType).substringAfter('/')}",
            fileBytes = bytes,
            fileContentType = options.mediaType,
            fields = listOf("config" to config.toString()),
            headers = headers,
        ).value.jsonObject
        val jobId = submitted["id"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("Rev AI returned no job id")

        pollUntilDone(policy = pollPolicy, elapsedMillis = elapsedMillis) {
            val job = http.getJson("$baseUrl/jobs/$jobId", headers).value.jsonObject
            when (job["status"]?.jsonPrimitive?.content) {
                "transcribed" -> JobStatus.Succeeded(Unit)
                "in_progress" -> JobStatus.InProgress()
                else -> JobStatus.Failed(
                    job["failure_detail"]?.jsonPrimitive?.content
                        ?: "Rev AI reported status ${job["status"]}",
                )
            }
        }

        val result = http.getJson(
            url = "$baseUrl/jobs/$jobId/transcript",
            // The vendor Accept header is load-bearing: without it this endpoint answers in another
            // format entirely, and the parse fails on a job that transcribed perfectly.
            headers = headers + mapOf("Accept" to RevAiProvider.TRANSCRIPT_ACCEPT),
        )

        val monologues = result.value.jsonObject["monologues"]?.jsonArray.orEmpty()
            .map { it.jsonObject["elements"]?.jsonArray.orEmpty().map { element -> element.jsonObject } }
        val segments = mutableListOf<TranscriptionResult.Segment>()
        var duration = 0.0

        monologues.forEach { elements ->
            val pending = StringBuilder()
            var start = 0.0
            var started = false
            elements.forEach { element ->
                // Elements are words AND punctuation; only the words carry timings, and punctuation
                // joins the word before it rather than becoming a segment of its own.
                pending.append(element["value"]?.jsonPrimitive?.content.orEmpty())
                if (element["type"]?.jsonPrimitive?.content != "text") return@forEach
                val end = element["end_ts"]?.jsonPrimitive?.content?.toDoubleOrNull()
                if (end != null && end > duration) duration = end
                val ts = element["ts"]?.jsonPrimitive?.content?.toDoubleOrNull()
                if (!started && ts != null) {
                    start = ts
                    started = true
                }
                // A timed element CLOSES a segment. One segment per monologue is the thing this
                // replaced, and it fused every speaker turn into a single un-seekable block.
                if (end != null && started) {
                    if (pending.isNotBlank()) {
                        segments += TranscriptionResult.Segment(pending.toString().trim(), start, end)
                    }
                    pending.clear()
                    started = false
                }
            }
            if (started && pending.isNotBlank()) {
                segments += TranscriptionResult.Segment(
                    text = pending.toString().trim(),
                    startSecond = start,
                    endSecond = if (duration > start) duration else start + 1,
                )
            }
        }

        return TranscriptionResult(
            // Monologues are separate speaker turns, so they need a space between them; joining with
            // nothing fused the last word of one turn onto the first word of the next.
            text = monologues.joinToString(" ") { elements ->
                elements.joinToString("") { it["value"]?.jsonPrimitive?.content.orEmpty() }
            },
            segments = segments,
            // The submit response is where Rev AI names the language; the transcript never mentions it,
            // which is why the contract field was permanently null.
            language = submitted["language"]?.jsonPrimitive?.content,
            durationInSeconds = duration,
            response = result.modalityResponse(modelId = modelId, body = result.value.toString()),
        )
    }
}
