package com.sabreware.aide.aisdk.providers.elevenlabs

import com.sabreware.aide.aisdk.AiSdkError
import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.TranscriptionStreamPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optDouble
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.ProviderSocket
import com.sabreware.aide.aisdk.util.webSocketUrl
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The message types ElevenLabs uses to report a failure. There is no `type: "error"` envelope: the
 * failure IS the message type, so a switch that only knows the transcript types treats a quota refusal
 * as an unrecognized frame and waits forever for a transcript that is never coming.
 */
private val ERROR_MESSAGE_TYPES = setOf(
    "auth_error",
    "chunk_size_exceeded",
    "commit_throttled",
    "error",
    "input_error",
    "insufficient_audio_activity",
    "queue_overflow",
    "quota_exceeded",
    "rate_limited",
    "resource_exhausted",
    "session_time_limit_exceeded",
    "transcriber_error",
    "unaccepted_terms",
)

/**
 * The three that arrive AFTER the caller's audio has been committed and mean "there was nothing more to
 * finalize", not "the transcript is void". Erroring on them discards a complete transcript because the
 * tail of the recording happened to be silence.
 */
private val LATE_FINALIZATION_ERROR_TYPES = setOf(
    "commit_throttled",
    "input_error",
    "insufficient_audio_activity",
)

/** The rates ElevenLabs accepts for linear PCM; anything else is rejected at the handshake. */
private val PCM_SAMPLE_RATES = setOf(8_000, 16_000, 22_050, 24_000, 44_100, 48_000)

private const val DEFAULT_PCM_RATE = 16_000

private const val ULAW_RATE = 8_000

/** The audio format string and the rate that has to be repeated in every audio frame. */
internal data class ElevenLabsRealtimeFormat(val audioFormat: String, val sampleRate: Int)

/**
 * Live audio has no container to sniff, so the rate is declared rather than discovered — and an
 * unsupported one has to fail here. Sending it anyway produces a handshake rejection whose message
 * names neither the rate nor the format, from a socket that never carried a frame.
 */
internal fun elevenLabsRealtimeFormat(format: AudioFormat): ElevenLabsRealtimeFormat {
    if (format.type.lowercase() == "audio/pcmu") {
        if (format.rate != null && format.rate != ULAW_RATE) {
            throw InvalidArgumentError(
                message = "ElevenLabs only supports audio/pcmu at 8000 Hz.",
                argument = "inputAudioFormat",
            )
        }
        return ElevenLabsRealtimeFormat("ulaw_8000", ULAW_RATE)
    }

    val rate = format.rate ?: DEFAULT_PCM_RATE
    if (format.type.lowercase() != "audio/pcm" || rate !in PCM_SAMPLE_RATES) {
        throw InvalidArgumentError(
            message = "ElevenLabs realtime transcription supports audio/pcm at " +
                PCM_SAMPLE_RATES.sorted().joinToString(", ") + " Hz, and audio/pcmu at 8000 Hz.",
            argument = "inputAudioFormat",
        )
    }
    return ElevenLabsRealtimeFormat("pcm_$rate", rate)
}

/**
 * The socket URL, which is where every realtime option lives — the session is configured entirely by
 * query string and there is no `session.update` frame to correct it afterwards.
 *
 * `include_timestamps` is forced on whenever language detection was asked for, because ElevenLabs
 * delivers the detected language on the timestamp-bearing commit event alone. A caller who asked only
 * for the language would otherwise get a stream that never reports one.
 */
@Suppress("CyclomaticComplexMethod")
internal fun elevenLabsRealtimeUrl(
    baseUrl: String,
    modelId: String,
    format: ElevenLabsRealtimeFormat,
    languageCode: String?,
    streaming: JsonObject?,
): String {
    val includeTimestamps = streaming?.optBoolean("includeTimestamps")
    val includeLanguageDetection = streaming?.optBoolean("includeLanguageDetection")

    val query = buildList {
        add("model_id" to modelId)
        add("audio_format" to format.audioFormat)
        streaming?.optString("commitStrategy")?.let { add("commit_strategy" to it) }
        streaming?.optBoolean("enableLogging")?.let { add("enable_logging" to it.toString()) }
        streaming?.optBoolean("filterBackgroundAudio")
            ?.let { add("filter_background_audio" to it.toString()) }
        includeLanguageDetection?.let { add("include_language_detection" to it.toString()) }
        val timestamps = if (includeLanguageDetection == true) true else includeTimestamps
        timestamps?.let { add("include_timestamps" to it.toString()) }
        languageCode?.let { add("language_code" to it) }
        streaming?.optInt("minSilenceDurationMs")?.let { add("min_silence_duration_ms" to it.toString()) }
        streaming?.optInt("minSpeechDurationMs")?.let { add("min_speech_duration_ms" to it.toString()) }
        streaming?.optBoolean("noVerbatim")?.let { add("no_verbatim" to it.toString()) }
        streaming?.optDouble("vadSilenceThresholdSecs")
            ?.let { add("vad_silence_threshold_secs" to it.toString()) }
        streaming?.optDouble("vadThreshold")?.let { add("vad_threshold" to it.toString()) }
        // Repeated under one name rather than comma-joined: ElevenLabs reads a comma as part of the
        // term, so a joined list is one nonsense keyterm instead of several real ones.
        streaming?.optArray("keyterms")?.forEach { term ->
            (term as? JsonPrimitive)?.let { add("keyterms" to it.content) }
        }
        streaming?.optArray("secondaryLanguages")?.forEach { language ->
            (language as? JsonPrimitive)?.let { add("secondary_languages" to it.content) }
        }
    }

    return webSocketUrl("$baseUrl/speech-to-text/realtime") + "?" +
        query.joinToString("&") { (key, value) -> "$key=${value.encodeURLParameter()}" }
}

/**
 * One captured chunk, base64 in a JSON envelope rather than a binary frame — ElevenLabs multiplexes the
 * commit flag onto the same message, so audio and "that is the end of an utterance" share a channel.
 *
 * [previousText] rides the first chunk only. It is context for the first transcript, not a per-chunk
 * setting, and repeating it re-primes the decoder against text the speaker has long since moved past.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun elevenLabsAudioChunk(
    audio: ByteArray,
    sampleRate: Int,
    previousText: String? = null,
): JsonObject = buildJsonObject {
    put("message_type", "input_audio_chunk")
    put("audio_base_64", Base64.encode(audio))
    put("commit", false)
    put("sample_rate", sampleRate)
    previousText?.let { put("previous_text", it) }
}

/**
 * The end-of-input marker: an empty chunk whose only content is `commit: true`.
 *
 * Closing the socket instead loses the tail — ElevenLabs finalizes on the commit, not on the close, so
 * whatever was said after the last automatic commit is never transcribed.
 */
internal fun elevenLabsCommit(sampleRate: Int): JsonObject = buildJsonObject {
    put("message_type", "input_audio_chunk")
    put("audio_base_64", "")
    put("commit", true)
    put("sample_rate", sampleRate)
}

/** What the session is waiting for, which is what makes a socket close either a finish or a failure. */
internal enum class ElevenLabsSessionState {
    /** Still transcribing; a close here lost audio. */
    Running,

    /**
     * The commit that answered our end-of-input arrived. ElevenLabs may still send the timestamped twin
     * of that same commit, so the finish waits out a short grace period rather than firing immediately.
     */
    Finalizing,

    Finished,
}

/**
 * One frame or one boundary of the audio the caller pushed, in the order the session saw them.
 *
 * The end of the caller's audio is an *event on this stream* rather than a flag the sending coroutine
 * sets, because the two run concurrently: a shared boolean would be written by the audio pump and read
 * by the frame loop on another thread, and every "did this commit arrive after our input ended?"
 * decision below turns on reading it at the right moment.
 */
internal sealed interface ElevenLabsRealtimeEvent {

    data class Frame(val text: String) : ElevenLabsRealtimeEvent

    /** The caller's audio flow ended and the commit frame has been sent. */
    data object InputCommitted : ElevenLabsRealtimeEvent

    /** The grace period after a finalizing commit elapsed with no further frame. */
    data object GraceElapsed : ElevenLabsRealtimeEvent
}

/**
 * The frame sequence, as parts.
 *
 * Split from the socket because this is where the whole partial/final distinction lives and a socket is
 * the one thing a test cannot open: driven by a recorded frame list, every branch below is reachable.
 */
@Suppress("TooManyFunctions")
internal class ElevenLabsRealtimeMapper(
    private val warnings: List<Warning>,
    private val includeTimestamps: Boolean,
    private val includeLanguageDetection: Boolean,
    private val includeRawChunks: Boolean,
    private var detectedLanguage: String?,
) {

    var state: ElevenLabsSessionState = ElevenLabsSessionState.Running
        private set

    /** Completed once `session_started` arrives, which is when ElevenLabs will accept audio. */
    var sessionStarted: Boolean = false
        private set

    private var sessionId: String? = null
    private var segmentIndex = 0
    private var inputCommitted = false

    /**
     * ElevenLabs sends a commit event and, when timestamps were requested, its timestamped twin. Both
     * counters exist because the finish must wait for whichever of the pair the caller's options mean —
     * finishing on the first drops the timestamps the caller paid to compute.
     */
    private var committedEvents = 0
    private var committedEventsAtInputEnd = 0
    private var finalCommitEventCount: Int? = null
    private var timestampedCommits = 0

    private val finalTexts = mutableListOf<String>()
    private val finalSegments = mutableListOf<TranscriptionResult.Segment>()

    private val expectsTimestampedCommit: Boolean
        get() = includeTimestamps || includeLanguageDetection

    fun on(event: ElevenLabsRealtimeEvent): List<TranscriptionStreamPart> = when (event) {
        is ElevenLabsRealtimeEvent.Frame -> onFrame(event.text)
        ElevenLabsRealtimeEvent.InputCommitted -> {
            inputCommitted = true
            committedEventsAtInputEnd = committedEvents
            emptyList()
        }
        ElevenLabsRealtimeEvent.GraceElapsed -> finish()
    }

    /**
     * The socket closed. A close is a clean end only when it follows the commit that answered our
     * end-of-input; anywhere else it means audio was transcribed and then thrown away, which has to be
     * an error rather than a short transcript the caller cannot tell from a complete one.
     */
    fun onClose(): List<TranscriptionStreamPart> = when {
        state == ElevenLabsSessionState.Finished -> emptyList()
        state == ElevenLabsSessionState.Finalizing -> finish()
        // The commit that answered our end-of-input already arrived; only its timestamped twin is
        // missing, and the socket will not be sending it now. The transcript is complete, so erroring
        // here discards a finished transcript over the word timings that would have decorated it —
        // which the caller may not even have asked for.
        finalCommitEventCount != null -> finish()
        else -> throw AiSdkError(
            errorName = "AI_APICallError",
            message = "ElevenLabs realtime transcription stream closed before completion.",
        )
    }

    private fun onFrame(text: String): List<TranscriptionStreamPart> {
        val raw = runCatching { ProviderJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
            // A frame that is not JSON is skipped rather than fatal: the reference does the same, and a
            // keep-alive or a truncated frame must not discard a transcript that is otherwise fine.
            ?: return emptyList()

        val parts = mutableListOf<TranscriptionStreamPart>()
        if (includeRawChunks) parts += TranscriptionStreamPart.Raw(raw)

        val messageType = raw.optString("message_type")
        if (messageType != null && messageType in ERROR_MESSAGE_TYPES) {
            return parts + onErrorMessage(messageType, raw)
        }

        when (messageType) {
            "session_started" -> {
                sessionId = raw.optString("session_id")
                sessionStarted = true
                parts += TranscriptionStreamPart.StreamStart(warnings)
            }
            "partial_transcript" -> parts += TranscriptionStreamPart.TranscriptPartial(
                text = raw.optString("text").orEmpty(),
                id = segmentId(),
            )
            "final_transcript", "committed_transcript" ->
                parts += onCommit(raw, legacy = messageType == "final_transcript")
            "final_transcript_with_timestamps", "committed_transcript_with_timestamps" ->
                parts += onTimestampedCommit(raw)
        }
        return parts
    }

    private fun onErrorMessage(
        messageType: String,
        raw: JsonObject,
    ): List<TranscriptionStreamPart> {
        // A late refusal on a session that already produced a commit is the vendor saying there was
        // nothing left to finalize. Treating it as a failure would void a transcript because the
        // recording happened to end in silence.
        if (inputCommitted &&
            (committedEvents > 0 || timestampedCommits > 0) &&
            messageType in LATE_FINALIZATION_ERROR_TYPES
        ) {
            return finish()
        }
        throw AiSdkError(
            errorName = "AI_APICallError",
            message = raw.optString("error") ?: "ElevenLabs realtime transcription error: $messageType",
        )
    }

    private fun onCommit(raw: JsonObject, legacy: Boolean): List<TranscriptionStreamPart> {
        committedEvents++
        val text = raw.optString("text").orEmpty().trim()
        val parts = mutableListOf<TranscriptionStreamPart>()

        // An empty commit still counts towards the finish bookkeeping — it is a real commit event — but
        // it is not a segment, and emitting it would put a blank space into the assembled transcript.
        if (text.isNotEmpty()) {
            val id = segmentId()
            finalTexts += text
            segmentIndex++
            parts += TranscriptionStreamPart.TranscriptFinal(text = text, id = id)
        }

        if (inputCommitted && committedEvents > committedEventsAtInputEnd) {
            finalCommitEventCount = committedEvents
            // `final_transcript` is the legacy spelling and is never followed by a timestamped twin, so
            // waiting for one would hang the stream until the caller cancelled.
            if (!expectsTimestampedCommit || legacy) beginFinalizing()
        }
        return parts
    }

    private fun onTimestampedCommit(raw: JsonObject): List<TranscriptionStreamPart> {
        val text = raw.optString("text").orEmpty().trim()
        val words = raw.optArray("words").orEmpty().map { it.jsonObject }.mapNotNull { word ->
            val start = word["start"]?.jsonPrimitive?.content?.toDoubleOrNull()
            val end = word["end"]?.jsonPrimitive?.content?.toDoubleOrNull()
            if (start == null || end == null) {
                null
            } else {
                TranscriptionResult.Segment(
                    text = word["text"]?.jsonPrimitive?.content.orEmpty(),
                    startSecond = start,
                    endSecond = end,
                )
            }
        }
        detectedLanguage = raw.optString("language_code") ?: detectedLanguage

        val parts = mutableListOf<TranscriptionStreamPart>()
        // Normally this is the twin of the commit just handled, whose text is already final. It is only
        // a segment of its own when the server sent the timestamped form alone, which the reference
        // also handles defensively rather than dropping the only copy of the text.
        if (finalTexts.getOrNull(timestampedCommits) == null && text.isNotEmpty()) {
            val id = segmentId()
            finalTexts += text
            segmentIndex++
            parts += TranscriptionStreamPart.TranscriptFinal(
                text = text,
                id = id,
                startSecond = if (includeTimestamps) words.firstOrNull()?.startSecond else null,
                endSecond = if (includeTimestamps) words.lastOrNull()?.endSecond else null,
            )
        }
        timestampedCommits++
        if (includeTimestamps) finalSegments += words

        val expected = finalCommitEventCount
        if (inputCommitted &&
            timestampedCommits > committedEventsAtInputEnd &&
            (expected == null || timestampedCommits >= expected)
        ) {
            beginFinalizing()
        }
        return parts
    }

    private fun beginFinalizing() {
        if (state == ElevenLabsSessionState.Running) state = ElevenLabsSessionState.Finalizing
    }

    private fun finish(): List<TranscriptionStreamPart> {
        if (state == ElevenLabsSessionState.Finished) return emptyList()
        state = ElevenLabsSessionState.Finished
        return listOf(
            TranscriptionStreamPart.Finish(
                text = finalTexts.joinToString(" ").trim(),
                segments = finalSegments.toList(),
                language = detectedLanguage,
                durationInSeconds = finalSegments.lastOrNull()?.endSecond,
            ),
        )
    }

    private fun segmentId(): String = "${sessionId ?: "session"}:$segmentIndex"
}

/**
 * How long the finish waits after the commit that answered our end-of-input.
 *
 * ElevenLabs follows a commit with its timestamped twin, and the two are separate frames; finishing on
 * the first drops the word timings. The vendor does not close the socket afterwards either, so without a
 * deadline the stream hangs until the caller gives up on a transcript that is already complete.
 */
private const val FINAL_COMMIT_GRACE_MS = 250L

/**
 * The session, as a flow.
 *
 * Frames and the end of the caller's audio are funnelled into ONE channel so the mapper sees them in the
 * order they happened. Two concurrent sources feeding a shared flag instead would make every
 * "did this commit arrive after our input ended?" test a race, and the answer decides whether the
 * transcript finishes or hangs.
 */
internal fun elevenLabsRealtimeStream(
    socket: ProviderSocket,
    url: String,
    headers: Map<String, String>,
    audio: Flow<ByteArray>,
    format: ElevenLabsRealtimeFormat,
    previousText: String?,
    mapper: ElevenLabsRealtimeMapper,
    graceMs: Long = FINAL_COMMIT_GRACE_MS,
): Flow<TranscriptionStreamPart> = flow {
    coroutineScope {
        val events = Channel<ElevenLabsRealtimeEvent>(Channel.UNLIMITED)
        val sessionStarted = CompletableDeferred<Unit>()

        val reader = launch {
            try {
                socket.textFrames(url, headers) { sender ->
                    // Audio pushed before `session_started` is discarded by the vendor, so the first
                    // words of every recording would be missing from a session that started sending
                    // the moment the socket opened.
                    sessionStarted.await()
                    var first = true
                    audio.collect { chunk ->
                        sender.sendJson(
                            elevenLabsAudioChunk(
                                audio = chunk,
                                sampleRate = format.sampleRate,
                                previousText = previousText.takeIf { first },
                            ),
                        )
                        first = false
                    }
                    sender.sendJson(elevenLabsCommit(format.sampleRate))
                    events.send(ElevenLabsRealtimeEvent.InputCommitted)
                }.collect { events.send(ElevenLabsRealtimeEvent.Frame(it)) }
            } finally {
                events.close()
            }
        }

        var socketClosed = false
        while (true) {
            val event = if (mapper.state == ElevenLabsSessionState.Finalizing) {
                withTimeoutOrNull(graceMs) { events.receiveCatching().getOrNull() }
                    ?: ElevenLabsRealtimeEvent.GraceElapsed
            } else {
                events.receiveCatching().getOrNull() ?: run { socketClosed = true; null }
            } ?: break

            mapper.on(event).forEach { emit(it) }
            if (mapper.sessionStarted) sessionStarted.complete(Unit)
            if (mapper.state == ElevenLabsSessionState.Finished) break
        }
        if (socketClosed) mapper.onClose().forEach { emit(it) }
        reader.cancel()
    }
}
