package com.sabreware.aide.aisdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A realtime conversation model: audio and text in both directions, at once, over one socket.
 *
 * **This contract has a shape the other modalities do not, and the reason is worth stating.** Every
 * other model here owns its transport: [LanguageModel] issues the request, [TranscriptionModel] opens
 * the session, and a caller never sees a URL. A realtime model does not, because its session is usually
 * opened by something this library is not running in — a browser, a phone's audio layer, a native voice
 * pipeline — holding the microphone and the speaker. So the provider's job splits in two: mint the
 * credential and say where to connect ([doCreateClientSecret], [webSocketConfig]), then translate
 * between the vendor's frames and the neutral events ([parseServerEvent], [serializeClientEvent],
 * [buildSessionConfig]).
 *
 * That split is what makes the credential story safe. The long-lived API key stays wherever
 * [doCreateClientSecret] runs, and only a short-lived token reaches the process holding the microphone.
 * A contract that opened the socket itself would have to be handed the real key by whatever owns the
 * audio, which is the arrangement this shape exists to avoid.
 *
 * Implementations are pure translation with no session state, like every other model in this
 * specification: the session lives in the caller, and two calls to [parseServerEvent] must not depend on
 * each other.
 */
public interface RealtimeModel {

    /** Always [SPECIFICATION_VERSION]; lets a consumer reject a model built against an older spec. */
    public val specificationVersion: String get() = SPECIFICATION_VERSION

    /** The provider's id, e.g. `openai`. This is the key its [ProviderMetadata] is filed under. */
    public val provider: String

    /** The vendor's own model identifier. */
    public val modelId: String

    /**
     * Conversation semantics and the transports this model serves, when it declares them.
     *
     * Null — the default — preserves the legacy shape every existing provider has: a turn-based session
     * over a client-secret WebSocket, started by a session update and finalized by closing the
     * transport. A model with a server-owned socket or a continuous conversation says so here, and a
     * session layer reads it BEFORE connecting rather than discovering the difference from the frames.
     */
    public val capabilities: RealtimeCapabilities? get() = null

    /**
     * Server-only connection settings: where to connect and the headers that authenticate it.
     *
     * Optional; null means this model offers no server-side socket. The headers can carry the
     * long-lived API key — this is the half that runs where the key is, and its answer must never be
     * forwarded to a client, which is what [doCreateClientSecret] exists for.
     */
    public suspend fun serverWebSocketConfig(): RealtimeServerConnection? = null

    /**
     * A raw-event parser for ONE connection, discarded when that connection closes.
     *
     * Optional; null means [parseServerEvent] is stateless and serves every connection. A protocol
     * whose frames carry no ids — where the mapper has to number responses itself — needs state that
     * belongs to one socket and must not leak into the next, and this is where it lives.
     */
    public fun createServerEventParser(): RealtimeServerEventParser? = null

    /**
     * Mints a short-lived credential for a client that will open the socket itself.
     *
     * Runs where the real API key is — a server, or the app process — and returns something safe to
     * hand to the part of the system holding the microphone. The `do` prefix, as on
     * [LanguageModel.doGenerate], signals that callers are expected to go through a runtime.
     */
    public suspend fun doCreateClientSecret(
        options: RealtimeClientSecretOptions = RealtimeClientSecretOptions(),
    ): RealtimeClientSecret

    /**
     * Where to connect, and what to say in the handshake.
     *
     * Separate from [doCreateClientSecret] because the two run in different places and often in
     * different processes: the secret is minted where the key is, and this is answered where the socket
     * is opened. Vendors differ on where the token goes — OpenAI puts it in a **subprotocol**
     * (`RealtimeConnection.protocols`) because a browser cannot set a request header, while others put
     * it in the query string — which is exactly the difference a caller must not have to know.
     */
    public fun webSocketConfig(token: String, url: String): RealtimeConnection

    /**
     * Maps one raw vendor frame onto the neutral events.
     *
     * Returns a LIST because one vendor message can carry several things at once: Gemini's
     * `serverContent` may hold audio, a transcript delta and a turn boundary in a single frame, and a
     * one-to-one signature would force a provider to drop two of the three. An empty list is the honest
     * answer for a frame this specification does not model — a keep-alive, a billing ping — and is what
     * a caller should get instead of a synthetic event.
     */
    public fun parseServerEvent(raw: JsonElement): List<RealtimeServerEvent>

    /** Renders one neutral client event in the vendor's own frame shape. */
    public fun serializeClientEvent(event: RealtimeClientEvent): JsonElement

    /** Renders a neutral session configuration as the vendor's own session payload. */
    public fun buildSessionConfig(config: RealtimeSessionConfig): JsonElement

    /**
     * The frame to send straight back in answer to [raw], or null when none is needed.
     *
     * For vendor keep-alive protocols — a ping that must be ponged, on a socket the session layer owns.
     * A caller consults this BEFORE [parseServerEvent], because a health check is plumbing rather than
     * conversation and has no neutral event to become. Null by default: most vendors need nothing.
     */
    public fun healthCheckResponse(raw: JsonElement): JsonElement? = null
}

/** What to ask for when minting a client credential — see [RealtimeModel.doCreateClientSecret]. */
public data class RealtimeClientSecretOptions(
    /** How long the token should live. Null takes the vendor's own default. */
    val expiresAfterSeconds: Int? = null,
    /**
     * The session this token is for.
     *
     * Some vendors bake the configuration into the token rather than accepting a `session.update`
     * afterwards, so a token minted without it opens a session that cannot be configured at all.
     */
    val sessionConfig: RealtimeSessionConfig? = null,
)

/** A short-lived credential plus where to spend it. */
public data class RealtimeClientSecret(
    /** The token itself — a bearer value, or the subprotocol entry, depending on the vendor. */
    val token: String,
    /** The socket URL to connect to, vendor query parameters included. */
    val url: String,
    /** Unix seconds at which [token] stops working. Null where the vendor does not say. */
    val expiresAt: Long? = null,
)

/**
 * How to open the socket.
 *
 * [protocols] is the load-bearing half for OpenAI, whose realtime endpoint authenticates by
 * subprotocol because the browser WebSocket API cannot set a request header.
 */
public data class RealtimeConnection(
    val url: String,
    val protocols: List<String> = emptyList(),
)

/** How to open a SERVER-owned socket — see [RealtimeModel.serverWebSocketConfig]. */
public data class RealtimeServerConnection(
    val url: String,
    /** Request headers, credentials included; never forwarded to a client. */
    val headers: Map<String, String> = emptyMap(),
)

/** A raw-event parser bound to one connection — see [RealtimeModel.createServerEventParser]. */
public fun interface RealtimeServerEventParser {

    /** Maps one raw vendor frame onto the neutral events, as [RealtimeModel.parseServerEvent] does. */
    public fun parse(raw: JsonElement): List<RealtimeServerEvent>
}

/**
 * What a realtime model declares about its sessions — see [RealtimeModel.capabilities].
 *
 * Every omission preserves the legacy behaviour, so a declaration only ever adds: [connections] null
 * means the client-secret WebSocket, [startup] null means a session update opens the session, and
 * [finalization] null means closing the transport ends it.
 */
public data class RealtimeCapabilities(
    val conversation: Conversation,
    /** The transports the model serves, in preference order. */
    val transports: List<Transport>,
    /** How a session may be opened. Null preserves the client-secret WebSocket. */
    val connections: List<Connection>? = null,
    /** What starts the session. Null preserves session-update startup. */
    val startup: Startup? = null,
    /** What ends the session. Null preserves transport-close finalization. */
    val finalization: Finalization? = null,
) {

    /** Whether the model speaks and listens at once, or waits for its turn. */
    public enum class Conversation { Continuous, TurnBased }

    /** A transport the model serves. */
    public enum class Transport { WebSocket, WebRtc }

    /** A way a session may be opened. */
    public enum class Connection { ClientSecretWebSocket, ServerWebSocket, WebRtc }

    /** What starts a session. */
    public enum class Startup { SessionStart, SessionUpdate }

    /** What ends a session. */
    public enum class Finalization { SessionClose, TransportClose }
}

/** A tool a realtime session may call. */
public data class RealtimeToolDefinition(
    val name: String,
    /** JSON Schema for the arguments, passed to the vendor verbatim — see [JsonSchema]. */
    val parameters: JsonSchema,
    /** What the tool does; the model reads this to decide whether to call it. */
    val description: String? = null,
)

/**
 * Neutral session configuration.
 *
 * As on [CallOptions], null means OMIT — a vendor's default is chosen by not asking. The one field that
 * distinguishes null from a value is [turnDetection], where null leaves the vendor's default and
 * [RealtimeTurnDetection.Disabled] actively asks for push-to-talk.
 */
public data class RealtimeSessionConfig(
    /** System instructions for the session. */
    val instructions: String? = null,
    /** The voice the model speaks in. */
    val voice: String? = null,
    /** Which modalities the model should produce. Null takes the vendor's default. */
    val outputModalities: List<RealtimeModality>? = null,
    /** The shape of the audio the caller will push — see [AudioFormat]. */
    val inputAudioFormat: AudioFormat? = null,
    /** The shape of the audio the model should return. */
    val outputAudioFormat: AudioFormat? = null,
    /**
     * Transcribe what the USER said, so it can be rendered as a message.
     *
     * Requested rather than assumed: a vendor that transcribes input only when asked otherwise emits
     * audio a transcript UI has nothing to show for.
     */
    val inputAudioTranscription: RealtimeTranscriptionConfig? = null,
    /**
     * Transcribe what the MODEL said. Some vendors do it by default; setting it makes that explicit
     * rather than a behaviour a caller is relying on without saying so.
     */
    val outputAudioTranscription: RealtimeTranscriptionConfig? = null,
    /** When the vendor should decide the user stopped talking. Null takes its default. */
    val turnDetection: RealtimeTurnDetection? = null,
    /** Tools the model may call during the session. */
    val tools: List<RealtimeToolDefinition>? = null,
    /** Provider-namespaced options, passed through verbatim — see [ProviderOptions]. */
    val providerOptions: ProviderOptions? = null,
)

/** What a realtime model may produce. */
public enum class RealtimeModality { Text, Audio }

/** How a side of the conversation should be transcribed. */
public data class RealtimeTranscriptionConfig(
    /** The vendor's transcription model. Null takes its default. */
    val model: String? = null,
    /** A language hint, for audio the vendor would otherwise have to detect. */
    val language: String? = null,
    /** Priming text — names and jargon the transcriber would otherwise mishear. */
    val prompt: String? = null,
)

/**
 * When the vendor decides a turn ended.
 *
 * [Disabled] is push-to-talk and is a real choice rather than an absence: a caller driving the turn
 * boundary itself must be able to say so, and leaving the field unset would take the vendor's automatic
 * detection instead.
 */
public sealed interface RealtimeTurnDetection {

    /** Amplitude-based detection: the turn ends when the user goes quiet. */
    public data class ServerVad(
        /** Activation threshold, 0.0–1.0. Higher needs louder audio to count as speech. */
        val threshold: Double? = null,
        /** How long the user must be silent before the turn ends. */
        val silenceDurationMs: Int? = null,
        /** How much audio before the detected speech onset to keep — the leading consonant. */
        val prefixPaddingMs: Int? = null,
    ) : RealtimeTurnDetection

    /** Meaning-based detection: the turn ends when the sentence sounds finished, not merely quiet. */
    public data class SemanticVad(
        val threshold: Double? = null,
        val silenceDurationMs: Int? = null,
        val prefixPaddingMs: Int? = null,
    ) : RealtimeTurnDetection

    /** Push-to-talk: the caller says when the turn ends, with [RealtimeClientEvent.InputAudioCommit]. */
    public data object Disabled : RealtimeTurnDetection
}

/** Something the client sends into a realtime session. */
public sealed interface RealtimeClientEvent {

    /**
     * Reconfigure the live session.
     *
     * [eventId] is the client's own id for the frame, where a vendor echoes one back: a
     * [RealtimeServerEvent.Error] naming it says WHICH update was rejected, which on a session that
     * updates often is the difference between a fixable error and a mystery.
     */
    public data class SessionUpdate(
        val config: RealtimeSessionConfig,
        val eventId: String? = null,
    ) : RealtimeClientEvent

    /**
     * More captured audio.
     *
     * Base64 rather than [ByteArray] because this event is usually serialized straight into a JSON
     * frame; handing over bytes would mean encoding them again at every provider.
     */
    public data class InputAudioAppend(
        val audio: String,
        /** The client's own id for the frame — see [SessionUpdate.eventId]. */
        val eventId: String? = null,
    ) : RealtimeClientEvent

    /** End of the current turn's audio — the push-to-talk release. */
    public data object InputAudioCommit : RealtimeClientEvent

    /** Discard whatever audio has been appended and not committed. */
    public data object InputAudioClear : RealtimeClientEvent

    /** Insert a message or a tool result into the conversation. */
    public data class ConversationItemCreate(val item: RealtimeConversationItem) : RealtimeClientEvent

    /**
     * Cut an assistant message short at [audioEndMs].
     *
     * The interruption primitive: the user talked over the model, and the model's own record of what it
     * said has to be trimmed to what was actually heard — otherwise it continues a sentence its listener
     * never got.
     */
    public data class ConversationItemTruncate(
        val itemId: String,
        val contentIndex: Int,
        val audioEndMs: Int,
    ) : RealtimeClientEvent

    /** Ask the model to answer now. */
    public data class ResponseCreate(
        val modalities: List<RealtimeModality>? = null,
        val instructions: String? = null,
        val metadata: JsonObject? = null,
    ) : RealtimeClientEvent

    /** Stop the answer in progress. */
    public data object ResponseCancel : RealtimeClientEvent

    // ── Continuous sessions ─────────────────────────────────────────────────────────────────────
    // The commands a continuous conversation takes in place of the turn-based ones above — see
    // [RealtimeCapabilities.Startup.SessionStart] and [RealtimeCapabilities.Finalization.SessionClose].
    // A model that does not serve them throws [UnsupportedFunctionalityError] from
    // [RealtimeModel.serializeClientEvent], as the reference's does; none is ever silently dropped.

    /** Open the session with its startup settings, which a continuous session then holds immutable. */
    public data class SessionStart(
        val config: RealtimeSessionConfig,
        /** The client's own id for the frame — see [SessionUpdate.eventId]. */
        val eventId: String? = null,
    ) : RealtimeClientEvent

    /** Close the session; the vendor answers with [RealtimeServerEvent.SessionClosed]. */
    public data class SessionClose(val eventId: String? = null) : RealtimeClientEvent

    /** Stop listening to the microphone without closing the session. */
    public data class InputAudioMute(val eventId: String? = null) : RealtimeClientEvent

    /** Resume listening after an [InputAudioMute]. */
    public data class InputAudioUnmute(val eventId: String? = null) : RealtimeClientEvent

    /**
     * Hand the model context mid-conversation — an instruction, a thought, commentary; which one is the
     * vendor's channel, filed under [providerOptions]. [delegationId] names the
     * [RealtimeServerEvent.DelegationCreated] this answers, or null for context nobody asked for.
     */
    public data class ContextAppend(
        val content: String,
        val delegationId: String? = null,
        val providerOptions: ProviderOptions? = null,
        val eventId: String? = null,
    ) : RealtimeClientEvent
}

/** Something a client adds to the conversation directly, rather than by speaking. */
public sealed interface RealtimeConversationItem {

    /** A typed user message. */
    public data class TextMessage(val text: String) : RealtimeConversationItem

    /** A complete user utterance, base64 — audio that was recorded rather than streamed. */
    public data class AudioMessage(val audio: String) : RealtimeConversationItem

    /**
     * The result of a tool the model called.
     *
     * [name] is carried as well as [callId] because some vendors route the result by tool name rather
     * than by id, and a result that reaches neither is a turn the model waits on forever.
     */
    public data class FunctionCallOutput(
        val callId: String,
        val output: String,
        val name: String? = null,
    ) : RealtimeConversationItem
}

/**
 * Something the model sends back.
 *
 * Every event carries [raw]. A realtime protocol changes faster than any port of it, and the neutral
 * union is a floor rather than a ceiling: a caller that needs a field this specification has not modelled
 * reads it off [raw] instead of waiting for a release here — which is the same reason
 * [ProviderMetadata] exists on the language side.
 */
public sealed interface RealtimeServerEvent {

    /** The untouched vendor frame this event was mapped from. */
    public val raw: JsonElement

    /** The session is open and configured. */
    public data class SessionCreated(
        override val raw: JsonElement,
        val sessionId: String? = null,
    ) : RealtimeServerEvent

    /** A [RealtimeClientEvent.SessionUpdate] took effect. */
    public data class SessionUpdated(override val raw: JsonElement) : RealtimeServerEvent

    /** The vendor's turn detection heard speech start. */
    public data class SpeechStarted(
        override val raw: JsonElement,
        val itemId: String? = null,
    ) : RealtimeServerEvent

    /** The vendor's turn detection heard speech stop. */
    public data class SpeechStopped(
        override val raw: JsonElement,
        val itemId: String? = null,
    ) : RealtimeServerEvent

    /** The input buffer became a conversation item. */
    public data class AudioCommitted(
        override val raw: JsonElement,
        val itemId: String? = null,
        val previousItemId: String? = null,
    ) : RealtimeServerEvent

    /** An item joined the conversation. */
    public data class ConversationItemAdded(
        override val raw: JsonElement,
        val itemId: String,
        val item: JsonElement,
    ) : RealtimeServerEvent

    /** What the vendor heard the USER say — the transcript a chat UI renders as their message. */
    public data class InputTranscriptionCompleted(
        override val raw: JsonElement,
        val itemId: String,
        val transcript: String,
    ) : RealtimeServerEvent

    /** The model began an answer. */
    public data class ResponseCreated(
        override val raw: JsonElement,
        val responseId: String,
    ) : RealtimeServerEvent

    /** The model finished an answer. [status] is the vendor's own word for how it ended. */
    public data class ResponseDone(
        override val raw: JsonElement,
        val responseId: String,
        val status: String,
    ) : RealtimeServerEvent

    /** An output item opened. */
    public data class OutputItemAdded(
        override val raw: JsonElement,
        val responseId: String,
        val itemId: String,
    ) : RealtimeServerEvent

    /** An output item closed. */
    public data class OutputItemDone(
        override val raw: JsonElement,
        val responseId: String,
        val itemId: String,
    ) : RealtimeServerEvent

    /** A content part opened inside an output item. */
    public data class ContentPartAdded(
        override val raw: JsonElement,
        val responseId: String,
        val itemId: String,
    ) : RealtimeServerEvent

    /** A content part closed. */
    public data class ContentPartDone(
        override val raw: JsonElement,
        val responseId: String,
        val itemId: String,
    ) : RealtimeServerEvent

    /** Spoken audio, base64 — kept encoded, as the wire sent it; a player decodes once. */
    public data class AudioDelta(
        override val raw: JsonElement,
        val responseId: String,
        val itemId: String,
        val delta: String,
    ) : RealtimeServerEvent

    /** The audio for this item is complete. */
    public data class AudioDone(
        override val raw: JsonElement,
        val responseId: String,
        val itemId: String,
    ) : RealtimeServerEvent

    /** What the model is saying, as text, while it says it. */
    public data class AudioTranscriptDelta(
        override val raw: JsonElement,
        val responseId: String,
        val itemId: String,
        val delta: String,
    ) : RealtimeServerEvent

    /** The settled transcript of what the model said. */
    public data class AudioTranscriptDone(
        override val raw: JsonElement,
        val responseId: String,
        val itemId: String,
        val transcript: String? = null,
    ) : RealtimeServerEvent

    /** Text the model produced without speaking it. */
    public data class TextDelta(
        override val raw: JsonElement,
        val responseId: String,
        val itemId: String,
        val delta: String,
    ) : RealtimeServerEvent

    /** The settled text. */
    public data class TextDone(
        override val raw: JsonElement,
        val responseId: String,
        val itemId: String,
        val text: String? = null,
    ) : RealtimeServerEvent

    /** Partial JSON of a tool call's arguments, as the model writes them. */
    public data class FunctionCallArgumentsDelta(
        override val raw: JsonElement,
        val responseId: String,
        val itemId: String,
        val callId: String,
        val delta: String,
    ) : RealtimeServerEvent

    /** A complete tool call. [arguments] is a JSON string, never a parsed object. */
    public data class FunctionCallArgumentsDone(
        override val raw: JsonElement,
        val responseId: String,
        val itemId: String,
        val callId: String,
        val name: String,
        val arguments: String,
    ) : RealtimeServerEvent

    /**
     * The vendor reported a failure.
     *
     * An event rather than a thrown exception, for the reason [StreamPart.Error] exists: one rejected
     * turn should cost the caller that turn, not the whole session and everything said in it.
     */
    public data class Error(
        override val raw: JsonElement,
        val message: String,
        val code: String? = null,
        /** The [RealtimeClientEvent] id this error answers, where the vendor correlates them. */
        val clientEventId: String? = null,
    ) : RealtimeServerEvent

    // ── Continuous sessions ─────────────────────────────────────────────────────────────────────
    // The events a continuous conversation ([RealtimeCapabilities.Conversation.Continuous]) carries
    // in place of the turn-based response lifecycle above: one session, one running transcript, and
    // usage that accrues by the second rather than by the turn.

    /** The session opened — the continuous-conversation counterpart of [SessionCreated]. */
    public data class SessionStarted(
        override val raw: JsonElement,
        val sessionId: String,
        /** Who runs the tools this session calls, where the vendor says. */
        val delegationMode: RealtimeDelegationTarget? = null,
    ) : RealtimeServerEvent

    /** The session ended; [reason] is the vendor's own word for why. */
    public data class SessionClosed(
        override val raw: JsonElement,
        /** Cumulative usage at close — the final [SessionUsage]. */
        val usage: RealtimeUsage,
        val reason: String,
        val sessionId: String? = null,
    ) : RealtimeServerEvent

    /** A usage snapshot. [usage] is CUMULATIVE, not an increment — the vendor reports a running total. */
    public data class SessionUsage(
        override val raw: JsonElement,
        val usage: RealtimeUsage,
        /** How much of the context window is spent, 0.0–1.0, where the vendor says. */
        val contextWindowUsageRatio: Double? = null,
    ) : RealtimeServerEvent

    /** Spoken audio outside any response — a continuous session has no [AudioDelta] to hang it on. */
    public data class AudioChunk(
        override val raw: JsonElement,
        val delta: String,
    ) : RealtimeServerEvent

    /** A slice of the running transcript, timed against the session's audio. */
    public data class TranscriptFragment(
        override val raw: JsonElement,
        val speaker: RealtimeSpeaker,
        val delta: String,
        val startMs: Long,
        val endMs: Long,
    ) : RealtimeServerEvent

    /**
     * The model handed a turn off — to the client's own agent, or to the vendor's.
     *
     * A continuous session delegates rather than calling tools inline: the application answers with a
     * context append naming [delegationId].
     */
    public data class DelegationCreated(
        override val raw: JsonElement,
        val delegationId: String,
        val target: RealtimeDelegationTarget? = null,
        /** Where in the session's audio the hand-off happened. */
        val offsetMs: Long? = null,
        val responseId: String? = null,
    ) : RealtimeServerEvent

    /** The vendor acknowledged a client command — [command] is its native name for it. */
    public data class CommandAcknowledged(
        override val raw: JsonElement,
        val command: String,
        /** The [RealtimeClientEvent] id this acknowledges, where the vendor echoes one. */
        val clientEventId: String? = null,
    ) : RealtimeServerEvent

    /**
     * A frame this specification does not model, forwarded whole.
     *
     * [rawType] is the vendor's own type string. Emitted rather than swallowed so a caller can act on a
     * vendor feature that shipped after this port did, which on a protocol that changes this fast is the
     * difference between a usable provider and one that is a release behind.
     */
    public data class Custom(
        override val raw: JsonElement,
        val rawType: String,
    ) : RealtimeServerEvent
}

/** Session usage, in seconds of conversation — the unit a continuous session bills by. */
public data class RealtimeUsage(val seconds: Double)

/** Who is speaking in a [RealtimeServerEvent.TranscriptFragment]. */
public enum class RealtimeSpeaker { User, Assistant }

/** Who a continuous session hands a turn to — see [RealtimeServerEvent.DelegationCreated]. */
public enum class RealtimeDelegationTarget { Client, Provider }
