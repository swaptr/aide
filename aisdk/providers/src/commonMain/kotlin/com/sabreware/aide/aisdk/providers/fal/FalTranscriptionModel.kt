package com.sabreware.aide.aisdk.providers.fal

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.util.JobStatus
import com.sabreware.aide.aisdk.util.PollPolicy
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.RetryPolicy
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.pollUntilDone
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * fal.ai speech-to-text (Whisper/Wizper), over the queue.
 *
 * Unlike speech and images, transcription is ASYNCHRONOUS: the audio goes up as a `data:` URI inside the
 * submit body — there is no upload step — and the transcript is collected from the queue's request URL.
 * fal reports "not finished yet" as an HTTP error whose `detail` says so, not as a status field, and the
 * poll request must not retry: with the default policy every check made before the job finished burned a
 * retry budget on a response that was never a failure. Both quirks are shared with [FalVideoModel].
 *
 * The base body pins `task: transcribe`, `diarize: true`, `chunk_level: word` — the reference's own
 * defaults. **One deliberate divergence:** the reference's option schema injects `language: "en"` and
 * `chunk_level: "segment"` the moment ANY unrelated fal option is passed (they are zod defaults, applied
 * on parse), so passing `batchSize` alone silently changes the language handling and the chunking. Here
 * an absent option stays absent — only keys the caller actually set reach the wire.
 */
@OptIn(ExperimentalEncodingApi::class)
internal class FalTranscriptionModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val queueUrl: String,
    private val apiKey: String,
    private val pollPolicy: PollPolicy,
    private val elapsedMillis: () -> Long,
) : TranscriptionModel {

    override val provider: String = FAL_PROVIDER_ID

    override suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult {
        val headers = combineHeaders(falAuthHeaders(apiKey), options.headers)
        val vendor = options.providerOptions?.get(FAL_PROVIDER_ID)

        val base64Audio = when (val audio = options.audio) {
            is BinaryData.Base64 -> audio.value
            is BinaryData.Bytes -> Base64.encode(audio.value)
        }

        val body = buildJsonObject {
            put("task", "transcribe")
            put("diarize", true)
            put("chunk_level", "word")
            vendor?.forEach { (key, value) ->
                if (value !is JsonNull) put(FAL_TRANSCRIPTION_FIELD_NAMES[key] ?: key, value)
            }
            put("audio_url", "data:${options.mediaType};base64,$base64Audio")
        }

        // The same prefix normalization as the queue's video path: `whisper`, `fal/whisper` and
        // `fal-ai/whisper` all name one model, and only one spelling routes.
        val model = modelId.removePrefix("fal-ai/").removePrefix("fal/")
        val submitted = http.postJson(
            url = "$queueUrl/fal-ai/$model",
            body = body,
            headers = headers,
        )
        val requestId = submitted.value.jsonObject["request_id"]?.jsonPrimitive?.content
            ?: throw NoContentGeneratedError("fal returned no request_id to poll")

        val result = pollUntilDone(policy = pollPolicy, elapsedMillis = elapsedMillis) {
            try {
                JobStatus.Succeeded(
                    http.withRetryPolicy(RetryPolicy.None)
                        .getJson("$queueUrl/fal-ai/$model/requests/$requestId", headers),
                )
            } catch (e: APICallError) {
                val detail = (e.data as? JsonObject)?.get("detail")?.jsonPrimitive?.content
                if (detail == FAL_STILL_IN_PROGRESS) {
                    JobStatus.InProgress()
                } else {
                    JobStatus.Failed(e.message ?: "fal reported a failed transcription")
                }
            }
        }
        val payload = result.value.jsonObject

        val chunks = payload["chunks"]?.jsonArray.orEmpty().map { it.jsonObject }
        val segments = chunks.map { chunk ->
            val timestamp = chunk["timestamp"]?.jsonArray
            TranscriptionResult.Segment(
                text = chunk["text"]?.jsonPrimitive?.content.orEmpty(),
                startSecond = timestamp?.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: 0.0,
                endSecond = timestamp?.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: 0.0,
            )
        }

        return TranscriptionResult(
            text = payload["text"]?.jsonPrimitive?.content.orEmpty(),
            segments = segments,
            language = payload["inferred_languages"]?.jsonArray?.firstOrNull()
                ?.jsonPrimitive?.content,
            durationInSeconds = segments.lastOrNull()?.endSecond,
            response = result.modalityResponse(modelId = modelId, body = result.value.toString()),
        )
    }
}

/** The `detail` fal answers a poll with while the transcript is still being produced. */
private const val FAL_STILL_IN_PROGRESS = "Request is still in progress"

/** The documented option keys, camelCase to fal's spelling; anything else passes through verbatim. */
private val FAL_TRANSCRIPTION_FIELD_NAMES = mapOf(
    "chunkLevel" to "chunk_level",
    "batchSize" to "batch_size",
    "numSpeakers" to "num_speakers",
)
