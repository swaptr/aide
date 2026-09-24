package com.sabreware.aide.aisdk.providers.cartesia

import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.RealtimeClientEvent
import com.sabreware.aide.aisdk.RealtimeClientSecret
import com.sabreware.aide.aisdk.RealtimeClientSecretOptions
import com.sabreware.aide.aisdk.RealtimeConnection
import com.sabreware.aide.aisdk.RealtimeModel
import com.sabreware.aide.aisdk.RealtimeServerEvent
import com.sabreware.aide.aisdk.RealtimeSessionConfig
import com.sabreware.aide.aisdk.RealtimeTurnDetection
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optDouble
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderHttp
import io.ktor.http.encodeURLParameter
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The longest-lived token `/access-token` will mint, in seconds; more is rejected, not clamped. */
private const val MAX_TOKEN_LIFETIME_SECONDS = 3600

private const val MILLIS_PER_SECOND = 1000L

/** What a frame is correlated under when Cartesia sent no `request_id` of its own. */
private const val FALLBACK_REQUEST_ID = "cartesia-realtime"

/** The media type assumed when a caller minted a token without describing the audio. */
private const val DEFAULT_INPUT_TYPE = "audio/pcm"

/** The auto-finalize endpoint's path, which decides what a commit means — see [CartesiaRealtimeModel]. */
private const val TURNS_PATH = "/stt/turns/websocket"

/**
 * What actually goes on Cartesia's socket for one client event.
 *
 * Ink 2's client side is not a JSON protocol: audio travels as a BINARY frame, and the manual-finalize
 * command is the bare word `finalize` as a text frame — a text frame that parses as JSON is read as a
 * control message and rejected. [RealtimeModel.serializeClientEvent] returns a [JsonElement], which can
 * carry neither faithfully, so this is the mapping's source of truth and the JSON rendering is derived
 * from it. See [CartesiaRealtimeModel.clientFrame].
 */
internal sealed interface CartesiaClientFrame {

    /** Nothing to send: the event has no meaning on a socket that only listens. */
    data object None : CartesiaClientFrame

    /** One binary frame — audio, in the encoding the session URL declared. */
    class Audio(val bytes: ByteArray) : CartesiaClientFrame {
        override fun equals(other: Any?): Boolean = other is Audio && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
        override fun toString(): String = "Audio(${bytes.size} bytes)"
    }

    /** One text frame, sent exactly as [text] — never JSON-encoded. */
    data class Text(val text: String) : CartesiaClientFrame
}

/**
 * Cartesia Ink 2 over the realtime contract: live speech-to-text, and nothing spoken back.
 *
 * **The token goes in the query string.** [doCreateClientSecret] spends the API key once, on
 * `/access-token`, and the socket is then opened — by a browser, a phone, a voice pipeline — carrying
 * only the short-lived token as `access_token` on the URL, which is the one field a browser's
 * WebSocket can populate. Cartesia also honours an `X-API-Key` header from a trusted server, but that
 * is the arrangement this contract exists to avoid: the key never reaches the process holding the
 * microphone.
 *
 * **The session is configured entirely by the URL minted with the token.** Model, encoding, sample
 * rate, the pinned API version and — on the plain endpoint — the language are query parameters, and
 * there is no session frame to correct any of them afterwards, so [buildSessionConfig] is null and a
 * `SessionUpdate` serializes to nothing. Turn detection is not a knob but an ENDPOINT:
 * `/stt/turns/websocket` segments the audio into turns and announces each, `/stt/websocket`
 * transcribes one stream that the caller finalizes by hand. The two speak different vocabularies.
 *
 * **It carries session state, which the contract says a model should not.** Three pieces, each forced
 * by the protocol rather than chosen, and each held by the reference for the same reason. Which
 * endpoint the session is on decides whether a commit is the `finalize` word or nothing at all, and
 * only [webSocketConfig] ever sees the URL that says. The plain endpoint emits its final `transcript`
 * fragments and then a `flush_done` that names none of them, so the settled transcript has to be
 * assembled somewhere, and this is the only place that sees every fragment. And only the turns
 * endpoint sends a `connected` frame, so on the plain one the first frame of any kind is what stands
 * in for "the session exists". The consequence is one instance per session —
 * [CartesiaProvider.realtimeModel] mints a fresh one on every call, which is the natural usage.
 */
@OptIn(ExperimentalEncodingApi::class)
internal class CartesiaRealtimeModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val apiVersion: String,
    private val headers: Map<String, String>,
    /** Epoch millis, injected so a test can pin `expiresAt` to a value rather than a range. */
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : RealtimeModel {

    override val provider: String = CARTESIA_PROVIDER_ID

    /** Whether the socket this instance is driving is the turns endpoint — decided by [webSocketConfig]. */
    private var useTurnDetection = true

    private var sessionCreated = false

    private val manual = ManualTranscript()

    override suspend fun doCreateClientSecret(options: RealtimeClientSecretOptions): RealtimeClientSecret {
        val expiresIn = options.expiresAfterSeconds
        if (expiresIn != null && (expiresIn <= 0 || expiresIn > MAX_TOKEN_LIFETIME_SECONDS)) {
            throw InvalidArgumentError(
                message = "Cartesia realtime client secrets must expire between 1 and 3600 seconds.",
                argument = "expiresAfterSeconds",
            )
        }
        val config = options.sessionConfig
        val language = config?.inputAudioTranscription?.language
        // English-only, and a mismatch never surfaces as an error — only as a wrong transcript.
        if (language != null && language != "en") {
            throw InvalidArgumentError(
                message = "Cartesia Ink 2 currently supports English only.",
                argument = "sessionConfig.inputAudioTranscription.language",
            )
        }
        // Resolved BEFORE the token is minted. The reference resolves it after, so an unsupported
        // format leaves a live credential behind for a session that will never open.
        val format = config?.inputAudioFormat ?: AudioFormat(type = DEFAULT_INPUT_TYPE)
        val encoding = cartesiaStreamingEncoding(format.type)
        // Null takes the vendor's default, which is detection; only an explicit Disabled is push-to-talk.
        val useTurnDetection = config?.turnDetection !is RealtimeTurnDetection.Disabled

        val body = buildJsonObject {
            putJsonObject("grants") { put("stt", true) }
            expiresIn?.let { put("expires_in", it) }
        }
        val response = http.postJson("$baseUrl/access-token", body, headers).value as? JsonObject
        val token = response?.optString("token")?.takeIf { it.isNotEmpty() }
            ?: throw NoContentGeneratedError("Cartesia realtime client secret response did not include a token.")

        return RealtimeClientSecret(
            token = token,
            // The token is deliberately NOT on this URL: it is handed over separately, in
            // webSocketConfig, so the URL can be logged and stored as a session description.
            url = cartesiaStreamingUrl(
                baseUrl = baseUrl,
                apiVersion = apiVersion,
                modelId = modelId,
                encoding = encoding,
                format = format,
                language = language,
                token = null,
                useTurnDetection = useTurnDetection,
            ),
            // Computed here rather than read back: `/access-token` does not report when the token dies.
            expiresAt = expiresIn?.let { now() / MILLIS_PER_SECOND + it },
        )
    }

    override fun webSocketConfig(token: String, url: String): RealtimeConnection {
        // Remembered because this is the only call that sees the URL, and the commit frame depends
        // on which endpoint it names.
        useTurnDetection = url.substringBefore('?').contains(TURNS_PATH)
        // A token already on the URL is replaced rather than repeated: a server reading the first of
        // two would authenticate with the stale one.
        val bare = cartesiaRedactedUrl(url)
        val separator = if ('?' in bare) "&" else "?"
        return RealtimeConnection(url = bare + separator + "access_token=" + token.encodeURLParameter())
    }

    override fun parseServerEvent(raw: JsonElement): List<RealtimeServerEvent> {
        val event = raw as? JsonObject ?: JsonObject(emptyMap())
        val type = event.optString("type").orEmpty()
        val requestId = event.optString("request_id") ?: FALLBACK_REQUEST_ID
        val custom = RealtimeServerEvent.Custom(raw, rawType = type)

        val mapped: List<RealtimeServerEvent> = when (type) {
            // The turns endpoint's own announcement, and the one frame that never gets a synthetic
            // session-created in front of it — it IS the session-created.
            "connected" -> {
                sessionCreated = true
                return listOf(RealtimeServerEvent.SessionCreated(raw, sessionId = event.optString("request_id")))
            }

            "turn.start" -> listOf(RealtimeServerEvent.SpeechStarted(raw, itemId = requestId))

            // The user talked through an eager end: speech again, plus the frame itself, so a caller
            // that acted on the eager end can undo it.
            "turn.resume" -> listOf(RealtimeServerEvent.SpeechStarted(raw, itemId = requestId), custom)

            "turn.end" -> listOf(
                RealtimeServerEvent.SpeechStopped(raw, itemId = requestId),
                RealtimeServerEvent.InputTranscriptionCompleted(
                    raw = raw,
                    itemId = requestId,
                    transcript = event.optString("transcript").orEmpty(),
                ),
            )

            // The plain endpoint's fragments. A final one is kept for the flush that settles it; a
            // partial is forwarded and forgotten, because the final that follows restates it.
            "transcript" -> {
                if (event.optBoolean("is_final") == true) manual.append(event)
                listOf(custom)
            }

            "flush_done" -> finishManualTranscript(event, raw)

            // A close with text still pending settles it first; the socket is about to go away.
            "done" -> if (manual.hasText) finishManualTranscript(event, raw) + custom else listOf(custom)

            "error" -> listOf(
                RealtimeServerEvent.Error(
                    raw = raw,
                    message = event.optString("message") ?: "Unknown Cartesia realtime error",
                    code = event.optString("error_code"),
                ),
            )

            // `turn.update` and `turn.eager_end` land here too: the neutral union has no event for a
            // transcript that is still being revised, and a caller reads the text off `raw`.
            else -> listOf(custom)
        }
        return withSessionCreated(mapped, requestId, raw)
    }

    /**
     * The typed form of [serializeClientEvent] — what a session layer that knows it is driving Cartesia
     * should use, because the neutral rendering cannot say whether a frame is binary or text.
     */
    fun clientFrame(event: RealtimeClientEvent): CartesiaClientFrame = when (event) {
        // Nothing to configure after the URL, nothing to clear on a socket that transcribes as it
        // goes, and nothing to ask for: the transcript arrives unprompted. Nothing, not a refusal.
        is RealtimeClientEvent.SessionUpdate,
        RealtimeClientEvent.InputAudioClear,
        is RealtimeClientEvent.ResponseCreate,
        -> CartesiaClientFrame.None

        is RealtimeClientEvent.InputAudioAppend -> CartesiaClientFrame.Audio(Base64.decode(event.audio))

        // The turns endpoint finds the boundary itself and has no word for the caller's; the plain
        // one needs `finalize`, or it holds the tail of the audio until the socket closes.
        RealtimeClientEvent.InputAudioCommit ->
            if (useTurnDetection) CartesiaClientFrame.None else CartesiaClientFrame.Text(CARTESIA_FINALIZE_FRAME)

        // A conversation is something Ink 2 has no side of. Refused rather than dropped, so a session
        // layer built for a conversational vendor fails at its first item instead of talking to a
        // model that only listens.
        is RealtimeClientEvent.ConversationItemCreate -> unsupported("conversation-item-create")
        is RealtimeClientEvent.ConversationItemTruncate -> unsupported("conversation-item-truncate")
        RealtimeClientEvent.ResponseCancel -> unsupported("response-cancel")
        is RealtimeClientEvent.SessionStart -> unsupported("session-start")
        is RealtimeClientEvent.SessionClose -> unsupported("session-close")
        is RealtimeClientEvent.InputAudioMute -> unsupported("input-audio-mute")
        is RealtimeClientEvent.InputAudioUnmute -> unsupported("input-audio-unmute")
        is RealtimeClientEvent.ContextAppend -> unsupported("context-append")
    }

    /**
     * [clientFrame], rendered as the contract requires.
     *
     * A [JsonElement] cannot carry bytes, so audio comes back as the base64 it arrived as and the
     * `finalize` word as a string primitive: in both cases the frame on the wire is the primitive's
     * CONTENT — decoded to a binary frame for audio, sent bare for the word — never its JSON rendering.
     * [JsonNull] is "nothing to send", which is distinct from a refusal, which throws.
     */
    override fun serializeClientEvent(event: RealtimeClientEvent): JsonElement = when (event) {
        // The event already holds the base64 the neutral rendering wants; decoding it into a frame and
        // encoding it back would cost two copies of every audio chunk for nothing.
        is RealtimeClientEvent.InputAudioAppend -> JsonPrimitive(event.audio)
        else -> serializeClientFrame(clientFrame(event))
    }

    private fun serializeClientFrame(frame: CartesiaClientFrame): JsonElement = when (frame) {
        CartesiaClientFrame.None -> JsonNull
        is CartesiaClientFrame.Audio -> JsonPrimitive(Base64.encode(frame.bytes))
        is CartesiaClientFrame.Text -> JsonPrimitive(frame.text)
    }

    /** Null: the session is the URL, and Ink 2 has no frame that reconfigures one. */
    override fun buildSessionConfig(config: RealtimeSessionConfig): JsonElement = JsonNull

    private fun finishManualTranscript(event: JsonObject, raw: JsonElement): List<RealtimeServerEvent> {
        val settled = manual.take()
        if (settled.text.isEmpty()) {
            return listOf(RealtimeServerEvent.Custom(raw, rawType = event.optString("type").orEmpty()))
        }
        return listOf(
            RealtimeServerEvent.AudioCommitted(raw, itemId = event.optString("request_id")),
            RealtimeServerEvent.InputTranscriptionCompleted(
                // Assembled from several frames, so no single one is "the" raw. The composite names the
                // flush and every fragment that went into the text, which is what the reference reports
                // and what a caller needs to reconcile the transcript with the frames it already saw.
                raw = buildJsonObject {
                    put("event", raw)
                    putJsonArray("transcriptEvents") { settled.events.forEach { add(it) } }
                    put("duration", settled.duration)
                },
                itemId = event.optString("request_id") ?: FALLBACK_REQUEST_ID,
                transcript = settled.text,
            ),
        )
    }

    /**
     * The first frame of any kind announces the session, once.
     *
     * Only the turns endpoint sends `connected`; a caller on the plain endpoint would otherwise never
     * see a [RealtimeServerEvent.SessionCreated] and a generic session layer would wait for one forever.
     */
    private fun withSessionCreated(
        events: List<RealtimeServerEvent>,
        sessionId: String,
        raw: JsonElement,
    ): List<RealtimeServerEvent> {
        if (sessionCreated) return events
        sessionCreated = true
        return listOf(RealtimeServerEvent.SessionCreated(raw, sessionId = sessionId)) + events
    }

    private fun unsupported(eventType: String): Nothing = throw UnsupportedFunctionalityError(
        functionality = "realtime client event \"$eventType\"",
        message = "Cartesia Ink 2 does not support realtime client event \"$eventType\".",
    )
}

/**
 * The plain endpoint's final fragments, held until the flush that settles them.
 *
 * Text is concatenated with NO separator: the fragments are pieces of one utterance and already carry
 * their own spacing, so a joiner would put a space mid-word — the same rule the transcription mapper
 * follows for this endpoint.
 */
private class ManualTranscript {

    private val text = StringBuilder()
    private var duration = 0.0
    private val events = mutableListOf<JsonObject>()

    val hasText: Boolean get() = text.isNotEmpty()

    fun append(event: JsonObject) {
        text.append(event.optString("text").orEmpty())
        duration += event.optDouble("duration") ?: 0.0
        events += event
    }

    /** Everything accumulated, and a clean slate for the next flush. */
    fun take(): Settled {
        val settled = Settled(text.toString(), duration, events.toList())
        text.clear()
        duration = 0.0
        events.clear()
        return settled
    }

    class Settled(val text: String, val duration: Double, val events: List<JsonObject>)
}
