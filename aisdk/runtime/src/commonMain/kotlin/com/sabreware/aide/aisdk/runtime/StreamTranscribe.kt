package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.ResponseInfo
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.TranscriptionStreamOptions
import com.sabreware.aide.aisdk.TranscriptionStreamPart
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A live transcription session produced nothing.
 *
 * A SUBTYPE of [NoContentGeneratedError] rather than a sibling, which is the same call the modality
 * errors make. Silence in and silence out is a real outcome a caller must be able to catch on its own —
 * that is what the distinct type buys — but a caller that already catches the base must keep matching,
 * or a refinement here silently stops an existing handler from running. [response] names the model that
 * stayed quiet.
 */
public class NoTranscriptGeneratedError(
    /** Response identity of the session that produced nothing, when the provider revealed one. */
    public val response: ResponseInfo? = null,
) : NoContentGeneratedError(
    message = "No transcript generated.",
    errorName = "AI_NoTranscriptGeneratedError",
)

/**
 * A live transcription, as a cold flow of parts.
 *
 * Collection opens the session, and cancelling the collector ends it — "the user stopped talking" and
 * "the user closed the app" are one code path, which is why nothing here takes an abort parameter.
 *
 * Every part is forwarded, where the reference consumes `stream-start` and `finish` internally and
 * surfaces them as promises on its result object; a Kotlin caller that wants the summary reads the
 * terminal [TranscriptionStreamPart.Finish], and one that wants the parts would lose the warnings and
 * the partial/final revisions if this swallowed them. What the wrapper adds over a bare `doStream` is
 * the two checks every caller would otherwise skip — see [checked].
 *
 * ```kotlin
 * val model = ElevenLabsProvider(client, apiKey).transcriptionModel("scribe_v1")
 * streamTranscribe(
 *     model,
 *     TranscriptionStreamOptions(
 *         audio = microphoneFrames,
 *         inputAudioFormat = AudioFormat("audio/pcm", rate = 16_000),
 *     ),
 * ).collect { part ->
 *     if (part is TranscriptionStreamPart.TranscriptFinal) render(part.text)
 * }
 * ```
 *
 * @param model the transcription model to open a session against; it must offer live transcription, or
 *   [UnsupportedFunctionalityError] is thrown — the null that means "no streaming endpoint" is a
 *   capability answer for a caller choosing a model, not an outcome for one that already chose.
 * @param options the live audio flow, its declared format, and any provider options or headers.
 */
public fun streamTranscribe(
    model: TranscriptionModel,
    options: TranscriptionStreamOptions,
): Flow<TranscriptionStreamPart> = flow {
    val result = model.doStream(options) ?: throw UnsupportedFunctionalityError(
        functionality = "streaming transcription",
        message = "The ${model.provider} model \"${model.modelId}\" does not support streaming " +
            "transcription.",
    )
    result.stream.checked(result.response).collect { emit(it) }
}

/**
 * The stream, with the checks the reference makes at the same point.
 *
 * A session that finished with neither text nor segments failed — silence, or a codec the vendor
 * accepted and could not decode — and returning it as an empty success is how a caller saves an empty
 * subtitle file. Segments without joined text stay legitimate, the same rule [transcribe] applies: a
 * diarizing vendor may return per-speaker spans and no combined transcript.
 *
 * A stream that ended without a [TranscriptionStreamPart.Finish] was cut off — the socket dropped, the
 * provider stopped mid-session — and its partials are a fragment a caller cannot tell from a whole
 * transcript unless this says so.
 */
private fun Flow<TranscriptionStreamPart>.checked(
    response: ResponseInfo?,
): Flow<TranscriptionStreamPart> = flow {
    var sawFinish = false
    collect { part ->
        if (part is TranscriptionStreamPart.Finish) {
            if (part.text.isBlank() && part.segments.isEmpty()) {
                throw NoTranscriptGeneratedError(response)
            }
            sawFinish = true
        }
        emit(part)
    }
    if (!sawFinish) throw NoTranscriptGeneratedError(response)
}
