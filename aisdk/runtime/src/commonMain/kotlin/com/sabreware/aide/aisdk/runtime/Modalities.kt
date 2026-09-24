package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.NoSpeechGeneratedError
import com.sabreware.aide.aisdk.RerankingCallOptions
import com.sabreware.aide.aisdk.RerankingModel
import com.sabreware.aide.aisdk.RerankingResult
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.SpeechResult
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.util.RetryPolicy
import com.sabreware.aide.aisdk.util.withRetry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ---------------------------------------------------------------------------------------------------
// The modalities whose call is already one request.
//
// These wrappers are thin on purpose — there is no batching to do and no fan-out to bound. What they
// add is the two things a bare `doGenerate` leaves to every caller and most callers skip: a retry
// policy, and the check that the model actually produced something. A transcription endpoint answering
// 200 with an empty transcript is common enough (silence, an unsupported codec the vendor accepted
// anyway) that letting it through as a legitimate empty string turns a failed job into a saved one.
// ---------------------------------------------------------------------------------------------------

/**
 * Transcribes recorded audio.
 *
 * For a running microphone see [TranscriptionModel.doStream], which is a different contract: this one
 * takes a complete buffer and answers once.
 *
 * ```kotlin
 * val model = DeepgramProvider(client, apiKey).transcriptionModel("nova-3")
 * val result = transcribe(
 *     model,
 *     TranscriptionCallOptions(audio = BinaryData.Bytes(wavBytes), mediaType = "audio/wav"),
 * )
 * println(result.text)
 * ```
 *
 * @param model the transcription model to call.
 * @param options the recorded audio, its media type, and any provider options or headers.
 * @param retry retry policy; [RetryPolicy.None] because providers already retry inside their transport.
 */
public suspend fun transcribe(
    model: TranscriptionModel,
    options: TranscriptionCallOptions,
    retry: RetryPolicy = RetryPolicy.None,
): TranscriptionResult {
    val result = withRetry(retry) { model.doGenerate(options) }
    // Segments without text is a legitimate shape — a diarizing vendor may return per-speaker spans and
    // no joined transcript — so emptiness is only a failure when BOTH are absent.
    if (result.text.isBlank() && result.segments.isEmpty()) {
        throw NoTranscriptGeneratedError()
    }
    return result
}

/**
 * Synthesizes speech from text.
 *
 * ```kotlin
 * val model = DeepgramProvider(client, apiKey).speechModel("aura-2")
 * val result = generateSpeech(model, SpeechCallOptions(text = "Your download has finished.", voice = "thalia"))
 * play(result.audio)
 * ```
 *
 * @param model the speech model to call.
 * @param options the text, the voice, the output format, and any provider options or headers.
 * @param retry retry policy; [RetryPolicy.None] because providers already retry inside their transport.
 */
public suspend fun generateSpeech(
    model: SpeechModel,
    options: SpeechCallOptions,
    retry: RetryPolicy = RetryPolicy.None,
): SpeechResult {
    if (options.text.isBlank()) throw InvalidArgumentError("text must not be blank.", "text")
    val result = withRetry(retry) { model.doGenerate(options) }
    // Zero bytes of audio decodes to a file no player will open, and a caller that writes it to disk
    // discovers that only when someone presses play.
    if (result.audio.isEmpty()) {
        throw NoSpeechGeneratedError("The speech model returned no audio.")
    }
    return result
}

/**
 * Reorders documents by relevance to a query.
 *
 * [RerankingResult.ranking] carries indices into what was submitted, so [rerankedDocuments] is the way
 * to get the documents themselves back in order — resolving an index list by hand is where an
 * off-by-one silently reorders a retrieval pipeline's results without failing anything.
 *
 * ```kotlin
 * val model = CohereProvider(client, apiKey).rerankingModel("rerank-v3.5")
 * val result = rerank(
 *     model,
 *     RerankingCallOptions(
 *         documents = RerankingCallOptions.Documents.Text(passages),
 *         query = "When was the warranty extended?",
 *         topN = 3,
 *     ),
 * )
 * val ordered = result.rerankedDocuments(passages)
 * ```
 *
 * A ranking that names an index outside the submitted documents is rejected as
 * [InvalidResponseDataError] rather than passed on: a caller resolving it would read past the end of
 * its own list, or — worse — a vendor that numbers from one would rank every document as its neighbour
 * and nothing would fail.
 *
 * @param model the reranking model to call.
 * @param options the query, the documents to rank against it, and how many to keep.
 * @param retry retry policy; [RetryPolicy.None] because providers already retry inside their transport.
 */
public suspend fun rerank(
    model: RerankingModel,
    options: RerankingCallOptions,
    retry: RetryPolicy = RetryPolicy.None,
): RerankingResult {
    val result = withRetry(retry) { model.doRerank(options) }
    checkRankingIndices(result.ranking, options.documents.count)
    return result
}

/**
 * The submitted documents in ranked order.
 *
 * [rerank] refuses a result whose ranking names an index it did not send, so a result it returned never
 * reaches the `getOrNull` here. One assembled elsewhere — a stored ranking, a hand-built fixture — may;
 * an unknown index is then dropped rather than thrown, because losing one row beats losing the ranking.
 */
public fun <T> RerankingResult.rerankedDocuments(documents: List<T>): List<T> =
    ranking.mapNotNull { documents.getOrNull(it.index) }

private val RerankingCallOptions.Documents.count: Int
    get() = when (this) {
        is RerankingCallOptions.Documents.Text -> values.size
        is RerankingCallOptions.Documents.Objects -> values.size
    }

/** The reference's rule and message; `data` is the ranking as returned. */
private fun checkRankingIndices(ranking: List<RerankingResult.Rank>, documentCount: Int) {
    for (rank in ranking) {
        if (rank.index < 0 || rank.index >= documentCount) {
            throw InvalidResponseDataError(
                "Invalid ranking index ${rank.index}. Expected an integer between 0 and ${documentCount - 1}.",
                data = JsonArray(
                    ranking.map { buildJsonObject { put("index", it.index); put("relevanceScore", it.relevanceScore) } },
                ),
            )
        }
    }
}

private fun BinaryData.isEmpty(): Boolean = when (this) {
    is BinaryData.Base64 -> value.isEmpty()
    is BinaryData.Bytes -> value.isEmpty()
}
