package com.sabreware.aide.aisdk.providers.cartesia

import com.sabreware.aide.aisdk.AiSdkError
import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.TranscriptionStreamPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.ProviderSocket
import com.sabreware.aide.aisdk.util.webSocketUrl
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The raw encodings Ink 2 accepts. A name outside this set is rejected at the handshake, from a socket
 * that never carried a frame — so an unknown one is refused here instead.
 */
private val STREAMING_ENCODINGS = setOf(
    "pcm_alaw",
    "pcm_f16le",
    "pcm_f32le",
    "pcm_mulaw",
    "pcm_s16le",
    "pcm_s32le",
)

/**
 * G.711's two companding laws — the only non-linear members of the set.
 *
 * They matter because widening generic `audio/pcm` to another LINEAR encoding is a legitimate override
 * (the media type does not say how many bits), while turning it into a companded one contradicts the
 * declared format outright. The linear half is derived rather than listed so a future linear encoding
 * is treated as widening-compatible by default; the companded half is closed by the standard.
 */
private val G711_ENCODINGS = setOf("pcm_mulaw", "pcm_alaw")

private val LINEAR_PCM_ENCODINGS = STREAMING_ENCODINGS - G711_ENCODINGS

/** Cartesia's default when the caller's format declares no rate. */
private const val DEFAULT_STREAMING_RATE = 24_000

/**
 * The encoding implied by the caller's declared media type.
 *
 * Live audio is naked frames with no container to sniff, so the type is the only thing that says what
 * the bytes are. An unsupported one fails here rather than at the handshake, where the rejection names
 * neither the format nor the parameter.
 */
internal fun cartesiaStreamingEncoding(type: String): String = when (type.lowercase()) {
    "audio/pcm" -> "pcm_s16le"
    "audio/pcmu" -> "pcm_mulaw"
    "audio/pcma" -> "pcm_alaw"
    else -> throw InvalidArgumentError(
        message = "Unsupported Cartesia streaming audio format: $type",
        argument = "inputAudioFormat",
    )
}

/**
 * The encoding actually sent, with the caller's override honoured and contradictions warned about.
 *
 * An override that merely widens generic PCM is the intended use. One that redefines a G.711 media type
 * — or turns generic PCM into G.711 — describes different bytes than the caller declared, which
 * produces audio that transcribes as noise rather than an error. The override is still sent, because the
 * caller may know something the media type does not; the warning is what makes it visible.
 */
internal fun cartesiaResolvedEncoding(
    format: AudioFormat,
    streaming: JsonObject?,
    warnings: MutableList<Warning>,
): String {
    val inferred = cartesiaStreamingEncoding(format.type)
    val override = streaming?.optString("encoding") ?: return inferred
    if (override !in STREAMING_ENCODINGS) {
        throw InvalidArgumentError(
            message = "Unsupported Cartesia streaming encoding: $override",
            argument = "providerOptions.cartesia.streaming.encoding",
        )
    }
    val widensGenericPcm = inferred == "pcm_s16le" && override in LINEAR_PCM_ENCODINGS
    if (override != inferred && !widensGenericPcm) {
        warnings += Warning.Other(
            "providerOptions.cartesia.streaming.encoding '$override' contradicts " +
                "inputAudioFormat.type '${format.type}' (inferred '$inferred'); sending '$override'.",
        )
    }
    return override
}

/**
 * The session URL, which is where every streaming option lives — Ink 2 is configured entirely by query
 * string and has no `session.update` frame to correct it afterwards.
 *
 * Turn detection picks a different ENDPOINT rather than a flag: `/stt/turns/websocket` segments the
 * audio into turns and `/stt/websocket` transcribes it as one stream, and the two speak different
 * message vocabularies. Pointing at the wrong one produces frames the mapper never matches, so the
 * session runs to completion and reports an empty transcript.
 */
@Suppress("LongParameterList")
internal fun cartesiaStreamingUrl(
    baseUrl: String,
    apiVersion: String,
    modelId: String,
    encoding: String,
    format: AudioFormat,
    language: String?,
    /** Absent while a session is being DESCRIBED rather than opened — the realtime contract hands it over separately. */
    token: String?,
    useTurnDetection: Boolean,
): String {
    val path = if (useTurnDetection) "/stt/turns/websocket" else "/stt/websocket"
    val query = buildList {
        add("model" to modelId)
        add("encoding" to encoding)
        add("sample_rate" to (format.rate ?: DEFAULT_STREAMING_RATE).toString())
        add("cartesia_version" to apiVersion)
        // The turn endpoint infers the language and rejects the parameter; only the plain one takes it.
        if (!useTurnDetection && language != null) add("language" to language)
        if (token != null) add("access_token" to token)
    }
    return webSocketUrl(baseUrl.trimEnd('/')) + path + "?" +
        query.joinToString("&") { (key, value) -> "$key=${value.encodeURLParameter()}" }
}

/**
 * The same URL with the credential removed, for [com.sabreware.aide.aisdk.TranscriptionStreamResult.request].
 *
 * A live session has no request body, so the URL is the only thing a bug report has to go on — and the
 * access token is in it. Reporting it verbatim would copy a working credential into every log that
 * records a request.
 */
internal fun cartesiaRedactedUrl(url: String): String =
    url.split('?').let { parts ->
        if (parts.size < 2) {
            url
        } else {
            val kept = parts[1].split('&').filterNot { it.startsWith("access_token=") }
            if (kept.isEmpty()) parts[0] else parts[0] + "?" + kept.joinToString("&")
        }
    }

/**
 * The plain endpoint's "transcribe what you have" command: a bare word as a text frame, not JSON. It is
 * also what the realtime contract's commit becomes on that endpoint — see [CartesiaRealtimeModel].
 */
internal const val CARTESIA_FINALIZE_FRAME: String = "finalize"

/** End of the caller's audio, which the two endpoints spell differently. */
internal fun cartesiaFinalizeFrame(useTurnDetection: Boolean): String =
    if (useTurnDetection) """{"type":"close"}""" else CARTESIA_FINALIZE_FRAME

/** One frame, or the end of the caller's audio, in the order the session saw them. */
internal sealed interface CartesiaRealtimeEvent {

    data class Frame(val text: String) : CartesiaRealtimeEvent

    /** The caller's audio flow ended and the finalize frame has been sent. */
    data object InputFinalized : CartesiaRealtimeEvent
}

/** What the mapper wants sent back, which is how `flush_done` gets its answering `close`. */
internal data class CartesiaMapped(
    val parts: List<TranscriptionStreamPart>,
    val reply: String? = null,
)

/**
 * The frame sequence, as parts.
 *
 * Split from the socket for the reason the other two live providers are: a socket is the one thing a
 * test cannot open, and every branch below is reachable when the mapper is driven by a recorded frame
 * list. The partial/final distinction is the whole point of the streaming contract — a live transcriber
 * revises what it said a moment ago, and a consumer that treats every emission as settled renders text
 * that jumps.
 */
internal class CartesiaRealtimeMapper(
    private val warnings: List<Warning>,
    private val language: String,
    private val useTurnDetection: Boolean,
    private val includeRawChunks: Boolean,
) {

    var finished: Boolean = false
        private set

    private var started = false
    private val finalTexts = mutableListOf<String>()
    private var durationInSeconds = 0.0

    fun start(): List<TranscriptionStreamPart> {
        if (started) return emptyList()
        started = true
        return listOf(TranscriptionStreamPart.StreamStart(warnings))
    }

    fun on(event: CartesiaRealtimeEvent): CartesiaMapped = when (event) {
        is CartesiaRealtimeEvent.Frame -> onFrame(event.text)
        CartesiaRealtimeEvent.InputFinalized -> CartesiaMapped(emptyList())
    }

    /**
     * The socket closed. Cartesia closes after `done`, and also closes without one when the session ends
     * on its own terms — so a close is a finish rather than a failure, which is the opposite of the
     * ElevenLabs and Gemini rule and follows the reference here.
     */
    fun onClose(): List<TranscriptionStreamPart> = finish()

    private fun onFrame(text: String): CartesiaMapped {
        if (finished) return CartesiaMapped(emptyList())
        // A frame that is not JSON is skipped rather than fatal: a keep-alive or a truncated frame must
        // not discard a transcript that is otherwise fine.
        val raw = runCatching { ProviderJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return CartesiaMapped(emptyList())

        val parts = mutableListOf<TranscriptionStreamPart>()
        if (includeRawChunks) parts += TranscriptionStreamPart.Raw(raw)
        val id = raw.optString("request_id")

        return when (raw.optString("type")) {
            // The turn endpoint's two revisable shapes: an update mid-turn, and the eager guess it
            // emits before the turn is confirmed over.
            "turn.update", "turn.eager_end" -> CartesiaMapped(
                parts + TranscriptionStreamPart.TranscriptPartial(
                    text = raw.optString("transcript").orEmpty(),
                    id = id,
                ),
            )

            "turn.end" -> {
                val settled = raw.optString("transcript").orEmpty()
                finalTexts += settled
                CartesiaMapped(parts + TranscriptionStreamPart.TranscriptFinal(text = settled, id = id))
            }

            // The plain endpoint reports both shapes under one type, distinguished by a flag.
            "transcript" -> {
                val transcript = raw.optString("text").orEmpty()
                val duration = raw["duration"]?.jsonPrimitive?.content?.toDoubleOrNull()
                if (raw.optBoolean("is_final") == true) {
                    finalTexts += transcript
                    durationInSeconds += duration ?: 0.0
                    CartesiaMapped(
                        parts + TranscriptionStreamPart.TranscriptFinal(text = transcript, id = id),
                    )
                } else {
                    CartesiaMapped(
                        parts + TranscriptionStreamPart.TranscriptPartial(
                            text = transcript,
                            id = id,
                            durationInSeconds = duration,
                        ),
                    )
                }
            }

            // Cartesia has flushed what it had and is waiting to be told the session is over. Without
            // the answering `close` it holds the socket open and the stream never ends.
            "flush_done" -> CartesiaMapped(parts, reply = "close")

            "done" -> CartesiaMapped(parts + finish())

            "error" -> throw AiSdkError(
                errorName = "AI_APICallError",
                message = raw.optString("message")
                    ?: raw.optString("error_code")
                    ?: "Cartesia streaming transcription error",
            )

            else -> CartesiaMapped(parts)
        }
    }

    private fun finish(): List<TranscriptionStreamPart> {
        if (finished) return emptyList()
        finished = true
        return listOf(
            TranscriptionStreamPart.Finish(
                // The turn endpoint's finals are whole turns and need a space between them; the plain
                // endpoint's are fragments of one utterance and are already spaced where they should be,
                // so joining those with a space inserts one mid-word.
                text = finalTexts.joinToString(if (useTurnDetection) " " else ""),
                language = language,
                durationInSeconds = durationInSeconds.takeIf { it > 0 },
            ),
        )
    }
}

/**
 * The session, as a flow.
 *
 * Frames and the end of the caller's audio are funnelled into ONE channel so the mapper sees them in the
 * order they happened — the same arrangement the other two live providers use, and for the same reason:
 * a shared flag written by the audio pump and read by the frame loop makes every ordering decision a
 * race.
 *
 * Audio goes out as BINARY frames. Cartesia reads a text frame as a control message, so audio sent as
 * text is parsed as a command, rejected, and never transcribed.
 */
@Suppress("LongParameterList")
internal fun cartesiaRealtimeStream(
    socket: ProviderSocket,
    url: String,
    headers: Map<String, String>,
    audio: Flow<ByteArray>,
    useTurnDetection: Boolean,
    mapper: CartesiaRealtimeMapper,
): Flow<TranscriptionStreamPart> = flow {
    coroutineScope {
        val events = Channel<CartesiaRealtimeEvent>(Channel.UNLIMITED)
        val replies = Channel<String>(Channel.UNLIMITED)

        val reader = launch {
            try {
                socket.textFrames(url, headers) { sender ->
                    // Ink 2 accepts audio the moment the socket opens — there is no `session_started`
                    // to wait for, unlike ElevenLabs and Gemini Live.
                    launch {
                        for (reply in replies) sender.sendText(reply)
                    }
                    audio.collect { chunk -> sender.sendBytes(chunk) }
                    sender.sendText(cartesiaFinalizeFrame(useTurnDetection))
                    events.send(CartesiaRealtimeEvent.InputFinalized)
                }.collect { events.send(CartesiaRealtimeEvent.Frame(it)) }
            } finally {
                events.close()
                replies.close()
            }
        }

        mapper.start().forEach { emit(it) }
        var socketClosed = false
        while (true) {
            val event = events.receiveCatching().getOrNull() ?: run { socketClosed = true; null } ?: break
            val mapped = mapper.on(event)
            mapped.parts.forEach { emit(it) }
            mapped.reply?.let { replies.trySend(it) }
            if (mapper.finished) break
        }
        if (socketClosed) mapper.onClose().forEach { emit(it) }
        reader.cancel()
    }
}

/** Cartesia's short-lived socket credential: the API key is never put in a URL. */
internal val CARTESIA_ACCESS_TOKEN_BODY: JsonObject = buildJsonObject {
    put("grants", buildJsonObject { put("stt", true) })
}
