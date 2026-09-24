package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.AiSdkError
import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.TranscriptionStreamPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.ProviderSocket
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The Live API's only input shape. Anything else is refused at the handshake, not here. */
internal const val LIVE_TRANSCRIPTION_INPUT_RATE: Int = 16_000

/**
 * How long the stream waits, quietly, before deciding the transcript is complete.
 *
 * Gemini Live sends trailing transcription fragments AFTER `audioStreamEnd` and — unless it reports an
 * idle interaction status — no terminal frame at all. Without a quiet window the stream hangs after the
 * last word, waiting for a terminator that never arrives. Any transcript activity restarts it.
 */
internal const val LIVE_TRANSCRIPTION_GRACE_MS: Long = 1_000L

/** Whether [modelId] names the socket-only family; `gemini-3.5-transcribe-live` and its variants. */
internal fun isGoogleLiveTranscriptionModel(modelId: String): Boolean = modelId.contains("-live")

/**
 * Live audio is naked PCM with nothing to sniff, so an unsupported shape has to fail here — the
 * handshake rejection names neither the rate nor the format.
 */
internal fun validateGoogleLiveTranscriptionFormat(format: AudioFormat) {
    if (format.type.lowercase() != "audio/pcm" ||
        (format.rate != null && format.rate != LIVE_TRANSCRIPTION_INPUT_RATE)
    ) {
        throw InvalidArgumentError(
            message = "Gemini Live transcription only supports 16kHz 16-bit PCM input audio.",
            argument = "inputAudioFormat",
        )
    }
}

/**
 * The session configuration, sent as the first frame.
 *
 * **No `generationConfig`.** Google's own announcement shows `responseModalities: ["TEXT"]` here, and
 * sending it suppresses the FINAL `inputTranscription` segments on the live endpoint — only interim
 * partials arrive, so every transcript comes back as revisable text that never settles. The reference
 * carries the same omission for the same reason.
 */
internal fun googleLiveTranscriptionSetup(modelId: String, vendor: JsonObject?): JsonObject =
    buildJsonObject {
        // A caller may name a model by its full resource path; only a bare id needs the prefix.
        put("model", if (modelId.contains('/')) modelId else "models/$modelId")
        put("inputAudioTranscription", googleTranscriptionConfig(vendor) ?: JsonObject(emptyMap()))
    }

/**
 * The transcription knobs, shared with the unary path so a caller's options mean the same thing on both.
 *
 * Returns null when the caller asked for nothing, which is what lets the setup send `{}` — an empty
 * object is what enables transcription at all, so omitting the key entirely turns it off.
 */
internal fun googleTranscriptionConfig(vendor: JsonObject?): JsonObject? {
    val language = vendor?.optString("language")
    if (language == null) return null
    return buildJsonObject { put("language", language) }
}

/** One captured chunk. The rate rides every frame; the Live API has no session-level input rate. */
@OptIn(ExperimentalEncodingApi::class)
internal fun googleLiveTranscriptionChunk(audio: ByteArray, rate: Int): JsonObject = buildJsonObject {
    put(
        "realtimeInput",
        buildJsonObject {
            put(
                "audio",
                buildJsonObject {
                    put("data", Base64.encode(audio))
                    put("mimeType", "audio/pcm;rate=$rate")
                },
            )
        },
    )
}

/** One frame, or the end of the caller's audio, in the order the session saw them. */
internal sealed interface GoogleLiveTranscriptionEvent {

    data class Frame(val text: String) : GoogleLiveTranscriptionEvent

    /** The caller's audio flow ended and `audioStreamEnd` has been sent. */
    data object AudioEnded : GoogleLiveTranscriptionEvent

    /** The quiet window after the last transcript activity elapsed. */
    data object GraceElapsed : GoogleLiveTranscriptionEvent
}

/**
 * The frame sequence, as parts.
 *
 * Split from the socket for the reason the sibling live providers are: a socket is the one thing a test
 * cannot open, and driven by a recorded frame list every branch below is reachable.
 *
 * The subtle rule is [latestInterim]. Gemini emits low-latency revisable text while the user is still
 * speaking and, separately, settled deltas — but on some turns it sends only the revisable kind and then
 * moves on. Dropping that leaves a transcript missing whole sentences the user could see on screen a
 * moment earlier, so the newest interim is kept as the fallback final and cleared the instant a real
 * delta supersedes it.
 */
internal class GoogleLiveTranscriptionMapper(
    private val warnings: List<Warning>,
    private val includeRawChunks: Boolean,
) {

    var finished: Boolean = false
        private set

    /** Completed once `setupComplete` arrives, which is when the Live API will accept audio. */
    var setupComplete: Boolean = false
        private set

    /** True once the caller's audio ended; only then does a quiet window mean the transcript is done. */
    var audioEnded: Boolean = false
        private set

    private var started = false
    private var segmentCounter = 0
    private var segmentBuffer = StringBuilder()
    private var latestInterim = ""
    private var fullText = StringBuilder()
    private var language: String? = null
    private var usageMetadata: JsonObject? = null

    fun start(): List<TranscriptionStreamPart> {
        if (started) return emptyList()
        started = true
        return listOf(TranscriptionStreamPart.StreamStart(warnings))
    }

    fun on(event: GoogleLiveTranscriptionEvent): List<TranscriptionStreamPart> = when (event) {
        is GoogleLiveTranscriptionEvent.Frame -> onFrame(event.text)
        GoogleLiveTranscriptionEvent.AudioEnded -> {
            audioEnded = true
            emptyList()
        }
        GoogleLiveTranscriptionEvent.GraceElapsed -> finish()
    }

    /**
     * The socket closed. After the caller's audio ended that is the server saying it has delivered
     * everything it will; before then, audio was transcribed and thrown away, which has to be an error
     * rather than a short transcript indistinguishable from a complete one.
     */
    fun onClose(): List<TranscriptionStreamPart> = when {
        finished -> emptyList()
        audioEnded -> finish()
        else -> throw AiSdkError(
            errorName = "AI_APICallError",
            message = "Google Live transcription WebSocket closed unexpectedly before finishing.",
        )
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun onFrame(text: String): List<TranscriptionStreamPart> {
        if (finished) return emptyList()
        // A frame that is not JSON is skipped rather than fatal: a keep-alive or a truncated frame must
        // not discard a transcript that is otherwise fine.
        val raw = runCatching { ProviderJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return emptyList()

        val parts = mutableListOf<TranscriptionStreamPart>()
        if (includeRawChunks) parts += TranscriptionStreamPart.Raw(raw)

        if (raw["setupComplete"] != null) setupComplete = true
        raw.optObject("usageMetadata")?.let { usageMetadata = it }
        raw.optObject("error")?.let { error ->
            throw AiSdkError(
                errorName = "AI_APICallError",
                message = error.optString("message") ?: "Google Live API error",
            )
        }

        val serverContent = raw.optObject("serverContent")

        serverContent?.optObject("interimInputTranscription")?.optString("text")
            ?.takeIf { it.isNotEmpty() }
            ?.let { interim ->
                latestInterim = interim
                parts += TranscriptionStreamPart.TranscriptPartial(text = interim, id = segmentId())
            }

        // Early sessions put the transcription at the top level; both spellings are live.
        val transcription = serverContent?.optObject("inputTranscription")
            ?: raw.optObject("inputTranscription")
        if (transcription != null) {
            transcription.optString("languageCode")?.let { language = it }
            transcription.optString("text")?.takeIf { it.isNotEmpty() }?.let { delta ->
                // A settled delta supersedes the interim text it revises; keeping both would emit the
                // same words twice.
                latestInterim = ""
                segmentBuffer.append(delta)
                parts += TranscriptionStreamPart.TranscriptDelta(delta = delta, id = segmentId())
            }
            if (transcription["finished"]?.toString() == "true") parts += completeSegment()
        }

        if (serverContent?.get("turnComplete")?.toString() == "true") parts += completeSegment()

        // `IDLE` — `REQUIRES_ACTION` in the EAP builds — is the definitive all-processing-complete
        // signal, and is the only way this session ends without waiting out the quiet window.
        val status = serverContent?.optString("interactionStatus")
        if (audioEnded && (status == "IDLE" || status == "REQUIRES_ACTION")) parts += finish()
        return parts
    }

    /** True while a frame could still arrive, which is what the quiet window is measured against. */
    fun hasPendingText(): Boolean = segmentBuffer.isNotEmpty() || latestInterim.isNotEmpty()

    private fun completeSegment(): List<TranscriptionStreamPart> {
        if (segmentBuffer.isEmpty()) {
            if (latestInterim.isEmpty()) return emptyList()
            // The server never sent a settled delta for this turn; the newest revisable text is the
            // only record of what was said, so it becomes the final rather than being dropped.
            segmentBuffer.append(latestInterim)
        }
        latestInterim = ""
        val settled = segmentBuffer.toString()
        val part = TranscriptionStreamPart.TranscriptFinal(text = settled, id = segmentId())
        if (fullText.isEmpty()) fullText.append(settled) else fullText.append(' ').append(settled)
        segmentBuffer = StringBuilder()
        segmentCounter++
        return listOf(part)
    }

    private fun finish(): List<TranscriptionStreamPart> {
        if (finished) return emptyList()
        val parts = mutableListOf<TranscriptionStreamPart>()
        parts += completeSegment()
        finished = true
        parts += TranscriptionStreamPart.Finish(
            text = fullText.toString(),
            language = language,
            providerMetadata = usageMetadata?.let { metadata ->
                mapOf(GOOGLE_PROVIDER_ID to buildJsonObject { put("usageMetadata", metadata) })
                    as ProviderMetadata
            },
        )
        return parts
    }

    private fun segmentId(): String = "google-segment-$segmentCounter"
}

/**
 * The session, as a flow.
 *
 * Frames and the end of the caller's audio are funnelled into ONE channel so the mapper sees them in
 * the order they happened — the arrangement both sibling live providers use, because a shared flag
 * written by the audio pump and read by the frame loop makes every ordering decision a race.
 *
 * The quiet window is restarted by transcript activity rather than by any arriving frame: Gemini emits
 * periodic `usageMetadata`, so a timer reset on every frame would be kept alive by billing traffic and
 * never fire.
 */
@Suppress("LongParameterList")
internal fun googleLiveTranscriptionStream(
    socket: ProviderSocket,
    url: String,
    headers: Map<String, String>,
    audio: Flow<ByteArray>,
    setup: JsonObject,
    inputRate: Int,
    mapper: GoogleLiveTranscriptionMapper,
    graceMs: Long,
): Flow<TranscriptionStreamPart> = flow {
    coroutineScope {
        val events = Channel<GoogleLiveTranscriptionEvent>(Channel.UNLIMITED)
        val setupAcknowledged = CompletableDeferred<Unit>()

        val reader = launch {
            try {
                socket.textFrames(url, headers) { sender ->
                    sender.sendJson(buildJsonObject { put("setup", setup) })
                    // Audio pushed before `setupComplete` is discarded by the Live API, so a session
                    // that started sending on open would lose the first words of every recording.
                    setupAcknowledged.await()
                    audio.collect { chunk ->
                        sender.sendJson(googleLiveTranscriptionChunk(chunk, inputRate))
                    }
                    sender.sendJson(GOOGLE_LIVE_AUDIO_STREAM_END)
                    events.send(GoogleLiveTranscriptionEvent.AudioEnded)
                }.collect { events.send(GoogleLiveTranscriptionEvent.Frame(it)) }
            } finally {
                events.close()
            }
        }

        mapper.start().forEach { emit(it) }
        var socketClosed = false
        while (true) {
            // The window only runs once the caller's audio has ended: silence mid-conversation is a
            // pause between sentences, and finishing on it would cut the transcript at the first breath.
            val event = if (mapper.audioEnded) {
                withTimeoutOrNull(graceMs) { events.receiveCatching().getOrNull() }
                    ?: GoogleLiveTranscriptionEvent.GraceElapsed
            } else {
                events.receiveCatching().getOrNull() ?: run { socketClosed = true; null }
            } ?: break

            mapper.on(event).forEach { emit(it) }
            if (mapper.setupComplete) setupAcknowledged.complete(Unit)
            if (mapper.finished) break
        }
        if (socketClosed) mapper.onClose().forEach { emit(it) }
        reader.cancel()
    }
}
