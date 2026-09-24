package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.ResponseInfo
import com.sabreware.aide.aisdk.SpeechTranslationModel
import com.sabreware.aide.aisdk.SpeechTranslationStreamOptions
import com.sabreware.aide.aisdk.SpeechTranslationStreamPart
import com.sabreware.aide.aisdk.SpeechTranslationStreamResult
import com.sabreware.aide.aisdk.SpeechTranslationUsage
import com.sabreware.aide.aisdk.Warning
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A translation session produced nothing.
 *
 * A SUBTYPE of [NoContentGeneratedError] rather than a sibling, matching the modality errors. A
 * translation is silence in and silence out often enough that "the session ran and said nothing" is a
 * real outcome a caller must be able to catch on its own — hence the distinct type — while a caller
 * that already catches the base keeps matching, which a sibling would have silently broken.
 * [response] names the model that stayed quiet.
 */
public class NoTranslationGeneratedError(
    public val response: ResponseInfo? = null,
) : NoContentGeneratedError(
    message = "No translation generated.",
    errorName = "AI_NoTranslationGeneratedError",
)

/** Everything a finished translation reported, once its stream has been drained. */
public data class TranslationResult(
    /** What was said, in the source language. */
    val sourceText: String,
    /** What it means, in the target language. */
    val translationText: String,
    /** Length of the session's audio, when the vendor reports it. */
    val durationInSeconds: Double? = null,
    /** What the session cost, in whichever units the vendor bills. */
    val usage: SpeechTranslationUsage? = null,
    /** What the model ignored or changed about the call. */
    val warnings: List<Warning> = emptyList(),
    /** The vendor's own response payload, keyed by provider id. */
    val providerMetadata: ProviderMetadata? = null,
    /** Response identity, naming the model that answered. */
    val response: ResponseInfo? = null,
)

/**
 * A live translation, as a cold flow of parts.
 *
 * Collection issues the call, and cancelling the collector ends the session — which is why nothing here
 * takes an abort parameter, and why "the user stopped talking" and "the user closed the app" are one
 * code path.
 *
 * Every part is forwarded, where the reference consumes `stream-start` and `finish` internally and
 * surfaces them as promises on its result object. There is nothing to hide them behind here: a Kotlin
 * caller that wants the summary rather than the parts calls [translate], and one that wants the parts
 * would lose the warnings and the usage if this swallowed them.
 *
 * What this adds over a bare `doStream` is the emptiness check — see [checked].
 *
 * ```kotlin
 * val model = GoogleProvider(client, apiKey).speechTranslationModel("gemini-3.5-live-translate-preview")
 * streamTranslate(
 *     model,
 *     SpeechTranslationStreamOptions(
 *         audio = microphoneFrames,
 *         inputAudioFormat = AudioFormat("audio/pcm", rate = 16_000),
 *         targetLanguage = "es",
 *     ),
 * ).collect { part -> if (part is SpeechTranslationStreamPart.OutputTextDelta) render(part.delta) }
 * ```
 *
 * @param model the translation model to open a session against.
 * @param options the live audio flow, its format, and the languages; collection starts the session.
 */
public fun streamTranslate(
    model: SpeechTranslationModel,
    options: SpeechTranslationStreamOptions,
): Flow<SpeechTranslationStreamPart> = flow {
    model.doStream(options).checked().collect { emit(it) }
}

/**
 * Runs a translation to completion and returns what it said.
 *
 * For a caller that renders subtitles as they arrive, collect [streamTranslate] instead: this one holds
 * nothing but the totals, so the intermediate parts are gone by the time it returns.
 *
 * ```kotlin
 * val model = GoogleProvider(client, apiKey).speechTranslationModel("gemini-3.5-live-translate-preview")
 * val result = translate(
 *     model,
 *     SpeechTranslationStreamOptions(
 *         audio = recordedFrames,
 *         inputAudioFormat = AudioFormat("audio/pcm", rate = 16_000),
 *         targetLanguage = "es",
 *     ),
 * )
 * println(result.translationText)
 * ```
 *
 * @param model the translation model to open a session against.
 * @param options the audio flow, its format, and the languages; the session runs until the flow ends.
 */
public suspend fun translate(
    model: SpeechTranslationModel,
    options: SpeechTranslationStreamOptions,
): TranslationResult {
    val result = model.doStream(options)
    var warnings = emptyList<Warning>()
    var response: ResponseInfo? = null
    var finish: SpeechTranslationStreamPart.Finish? = null

    result.checked().collect { part ->
        when (part) {
            is SpeechTranslationStreamPart.StreamStart -> warnings = part.warnings
            is SpeechTranslationStreamPart.ResponseMetadataPart ->
                response = ResponseInfo(part.metadata, part.headers, part.body)
            is SpeechTranslationStreamPart.Finish -> finish = part
            else -> Unit
        }
    }

    val finished = finish ?: throw NoTranslationGeneratedError(result.response)
    return TranslationResult(
        sourceText = finished.sourceText,
        translationText = finished.outputText,
        durationInSeconds = finished.durationInSeconds,
        usage = finished.usage,
        warnings = warnings,
        providerMetadata = finished.providerMetadata,
        // A provider that reports its metadata mid-stream refines what the envelope said; one that
        // never does — every socket protocol here — would otherwise lose the model id entirely.
        response = response ?: result.response,
    )
}

/**
 * The stream, with the check the reference makes at the same point.
 *
 * A session that finished having produced neither audio nor output text failed, and returning it as an
 * empty success is how a caller ends up saving a subtitle file with nothing in it.
 */
private fun SpeechTranslationStreamResult.checked(): Flow<SpeechTranslationStreamPart> = flow {
    var sawAudio = false
    var sawFinish = false
    stream.collect { part ->
        when (part) {
            is SpeechTranslationStreamPart.Audio -> sawAudio = true
            is SpeechTranslationStreamPart.Finish -> {
                // An audio-only provider legitimately reports no output text, so emptiness is a failure
                // only when nothing came back on either channel.
                if (!sawAudio && part.outputText.isEmpty()) {
                    throw NoTranslationGeneratedError(response)
                }
                sawFinish = true
            }
            else -> Unit
        }
        emit(part)
    }
    // A stream that ended without a finish part was cut off: the socket dropped, or the provider
    // stopped mid-session. Its texts are a fragment, and a caller cannot tell one from a whole
    // translation unless this says so.
    if (!sawFinish) throw NoTranslationGeneratedError(response)
}
