package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.RealtimeClientEvent
import com.sabreware.aide.aisdk.RealtimeDelegationTarget
import com.sabreware.aide.aisdk.RealtimeServerEvent
import com.sabreware.aide.aisdk.RealtimeSpeaker
import com.sabreware.aide.aisdk.RealtimeUsage
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

// ---------------------------------------------------------------------------------------------------
// Server frames → neutral events
// ---------------------------------------------------------------------------------------------------

/**
 * The Live frame types this mapper understands. Anything else — `response.event` with its opaque
 * delegated-response payload above all — is [RealtimeServerEvent.Custom], payload intact.
 */
private val KNOWN_LIVE_EVENT_TYPES: Set<String> = setOf(
    "session.started",
    "session.closed",
    "session.usage.updated",
    "session.output_audio.delta",
    "session.input_transcript.delta",
    "session.output_transcript.delta",
    "session.delegation.created",
    "session.updated",
    "session.input_audio.muted",
    "session.input_audio.unmuted",
    "session.instructions.appended",
    "session.thinking.appended",
    "session.commentary.appended",
    "error",
)

private const val INVALID_SERVER_EVENT = "invalid_server_event"

/**
 * One OpenAI Live server frame as a neutral event — the reference's `parseOpenAILiveServerEvent`.
 *
 * A KNOWN type whose payload does not validate is reported as an [RealtimeServerEvent.Error] with code
 * `invalid_server_event` rather than as a best-effort event: a `session.started` with no id, or a
 * transcript whose interval runs backwards, must not signal readiness or finalization to a session
 * layer that acts on those. An UNKNOWN type is [RealtimeServerEvent.Custom] and never an error, because
 * the Live wire's delegated-response envelopes are exactly the frames this port has no model for.
 *
 * Usage is CUMULATIVE — `session.usage.updated` reports a running total, never an increment — and is
 * passed through as such.
 */
internal fun parseOpenAILiveServerEvent(raw: JsonElement): List<RealtimeServerEvent> = listOf(parseLiveFrame(raw))

private fun parseLiveFrame(raw: JsonElement): RealtimeServerEvent {
    val event = raw as? JsonObject
    val type = event?.stringOrNull("type")
    if (event != null && type != null && type !in KNOWN_LIVE_EVENT_TYPES) {
        return RealtimeServerEvent.Custom(raw, rawType = type)
    }
    return try {
        LiveFrame(raw, event ?: throw InvalidLiveFrame(), type ?: throw InvalidLiveFrame()).parse()
    } catch (e: InvalidLiveFrame) {
        RealtimeServerEvent.Error(raw = raw, message = e.message, code = INVALID_SERVER_EVENT)
    }
}

/** A frame that is a known type but not the documented shape — see `parseOpenAILiveServerEvent`. */
private class InvalidLiveFrame(override val message: String = "Invalid OpenAI Live server event.") : Exception(message)

/** One frame with the strict field reads every branch shares; a read that fails throws [InvalidLiveFrame]. */
private class LiveFrame(val raw: JsonElement, val event: JsonObject, val type: String) {

    @Suppress("CyclomaticComplexMethod")
    fun parse(): RealtimeServerEvent = when (type) {
        "session.started" -> {
            val session = obj(event, "session")
            RealtimeServerEvent.SessionStarted(
                raw = raw,
                sessionId = nonEmptyString(session, "id"),
                delegationMode = when (optionalObj(session, "delegation")?.let { optionalEnum(it, "type", "client", "responses") }) {
                    "responses" -> RealtimeDelegationTarget.Provider
                    else -> RealtimeDelegationTarget.Client
                },
            )
        }
        "session.closed" -> RealtimeServerEvent.SessionClosed(
            raw = raw,
            sessionId = optionalObj(event, "session")?.let { nonEmptyString(it, "id") },
            usage = usage(obj(event, "usage")),
            reason = string(event, "reason"),
        )
        "session.usage.updated" -> RealtimeServerEvent.SessionUsage(
            raw = raw,
            usage = usage(obj(event, "usage")),
            contextWindowUsageRatio = optionalObj(event, "context_window")?.let { window ->
                optionalNumber(window, "usage_ratio")?.also { if (it < 0.0 || it > 1.0) throw InvalidLiveFrame() }
            },
        )
        "session.output_audio.delta" -> RealtimeServerEvent.AudioChunk(raw, delta = string(event, "delta"))
        "session.input_transcript.delta", "session.output_transcript.delta" -> {
            val (startMs, endMs) = interval()
            RealtimeServerEvent.TranscriptFragment(
                raw = raw,
                speaker = if (type == "session.input_transcript.delta") RealtimeSpeaker.User else RealtimeSpeaker.Assistant,
                delta = string(event, "delta"),
                startMs = startMs,
                endMs = endMs,
            )
        }
        "session.delegation.created" -> {
            val delegation = obj(event, "delegation")
            RealtimeServerEvent.DelegationCreated(
                raw = raw,
                delegationId = nonEmptyString(delegation, "id"),
                target = when (optionalEnum(delegation, "target", "client", "responses")) {
                    "responses" -> RealtimeDelegationTarget.Provider
                    "client" -> RealtimeDelegationTarget.Client
                    else -> null
                },
                offsetMs = optionalNonNegative(event, "offset_ms"),
                responseId = optionalString(delegation, "response_id")?.also { if (it.isEmpty()) throw InvalidLiveFrame() },
            )
        }
        "session.updated" -> {
            nonEmptyString(obj(event, "session"), "id")
            acknowledged("session.update")
        }
        "session.input_audio.muted", "session.input_audio.unmuted" -> acknowledged(type.dropLast(1))
        "session.instructions.appended", "session.thinking.appended", "session.commentary.appended" -> {
            interval()
            acknowledged(type.dropLast(2))
        }
        "error" -> {
            val error = obj(event, "error")
            RealtimeServerEvent.Error(
                raw = raw,
                message = string(error, "message"),
                code = optionalString(error, "code"),
                clientEventId = optionalString(error, "client_event_id"),
            )
        }
        else -> throw InvalidLiveFrame()
    }

    private fun acknowledged(command: String): RealtimeServerEvent = RealtimeServerEvent.CommandAcknowledged(
        raw = raw,
        command = command,
        clientEventId = optionalString(event, "client_event_id"),
    )

    /** `start_ms` / `end_ms`, both present and non-negative, and the end not before the start. */
    private fun interval(): Pair<Long, Long> {
        val startMs = nonNegative(event, "start_ms")
        val endMs = nonNegative(event, "end_ms")
        if (endMs < startMs) throw InvalidLiveFrame("Invalid OpenAI Live event time interval.")
        return startMs to endMs
    }

    private fun usage(usage: JsonObject): RealtimeUsage =
        RealtimeUsage(seconds = number(usage, "seconds").also { if (it < 0.0) throw InvalidLiveFrame() })

    // -- strict reads: absent is one thing, present-and-wrong is another; both are invalid where required.

    private fun obj(parent: JsonObject, key: String): JsonObject = parent[key] as? JsonObject ?: throw InvalidLiveFrame()

    private fun optionalObj(parent: JsonObject, key: String): JsonObject? = when (val value = parent[key]) {
        null, JsonNull -> null
        is JsonObject -> value
        else -> throw InvalidLiveFrame()
    }

    private fun string(parent: JsonObject, key: String): String =
        (parent[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw InvalidLiveFrame()

    private fun nonEmptyString(parent: JsonObject, key: String): String =
        string(parent, key).also { if (it.isEmpty()) throw InvalidLiveFrame() }

    private fun optionalString(parent: JsonObject, key: String): String? = when (val value = parent[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeIf { it.isString }?.content ?: throw InvalidLiveFrame()
        else -> throw InvalidLiveFrame()
    }

    private fun optionalEnum(parent: JsonObject, key: String, vararg allowed: String): String? =
        optionalString(parent, key)?.also { if (it !in allowed) throw InvalidLiveFrame() }

    private fun number(parent: JsonObject, key: String): Double =
        (parent[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: throw InvalidLiveFrame()

    private fun optionalNumber(parent: JsonObject, key: String): Double? = when (val value = parent[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeIf { !it.isString }?.doubleOrNull ?: throw InvalidLiveFrame()
        else -> throw InvalidLiveFrame()
    }

    private fun nonNegative(parent: JsonObject, key: String): Long =
        number(parent, key).also { if (it < 0.0) throw InvalidLiveFrame() }.toLong()

    private fun optionalNonNegative(parent: JsonObject, key: String): Long? =
        optionalNumber(parent, key)?.also { if (it < 0.0) throw InvalidLiveFrame() }?.toLong()
}

// ---------------------------------------------------------------------------------------------------
// Neutral client events → OpenAI Live frames
// ---------------------------------------------------------------------------------------------------

private val CONTEXT_CHANNELS = setOf("instructions", "thinking", "commentary")

/**
 * One neutral client event in OpenAI Live's frame shape — the reference's `serializeOpenAILiveClientEvent`.
 *
 * Only the continuous-conversation commands have a frame. A `session-update` is refused with the
 * reference's sentence — Live's startup settings are immutable, and the way to change what the model
 * knows mid-session is a `context-append` — and every turn-based command (`response-create`, the audio
 * buffer commits, truncation) is refused too, because a continuous session has no turns to drive.
 * A `context-append` lands on the `thinking` channel unless `providerOptions.openai.channel` names
 * `instructions` or `commentary`.
 */
@Suppress("ThrowsCount")
internal fun serializeOpenAILiveClientEvent(event: RealtimeClientEvent, modelId: String): JsonElement = when (event) {
    is RealtimeClientEvent.SessionStart -> buildJsonObject {
        put("type", "session.start")
        put("session", buildOpenAILiveSessionConfig(event.config, modelId))
        event.eventId?.let { put("event_id", it) }
    }
    is RealtimeClientEvent.SessionUpdate -> throw UnsupportedFunctionalityError(
        "OpenAI Live session-update; startup settings are immutable; use context-append or " +
            "input-audio-mute/input-audio-unmute",
    )
    is RealtimeClientEvent.SessionClose -> buildJsonObject {
        put("type", "session.close")
        event.eventId?.let { put("event_id", it) }
    }
    is RealtimeClientEvent.InputAudioAppend -> buildJsonObject {
        put("type", "session.input_audio.append")
        put("audio", event.audio)
        event.eventId?.let { put("event_id", it) }
    }
    is RealtimeClientEvent.InputAudioMute -> buildJsonObject {
        put("type", "session.input_audio.mute")
        event.eventId?.let { put("event_id", it) }
    }
    is RealtimeClientEvent.InputAudioUnmute -> buildJsonObject {
        put("type", "session.input_audio.unmute")
        event.eventId?.let { put("event_id", it) }
    }
    is RealtimeClientEvent.ContextAppend -> {
        val vendor = event.providerOptions?.get(OPENAI_PROVIDER_ID)
        vendor?.keys?.firstOrNull { it != "channel" }?.let {
            throw InvalidArgumentError(message = "providerOptions.openai.$it is not a context-append option", argument = "providerOptions")
        }
        val channel = vendor?.stringOrNull("channel") ?: "thinking"
        if (channel !in CONTEXT_CHANNELS) {
            throw InvalidArgumentError(
                message = "providerOptions.openai.channel must be one of ${CONTEXT_CHANNELS.joinToString(", ")}; got $channel",
                argument = "providerOptions",
            )
        }
        buildJsonObject {
            put("type", "session.$channel.append")
            put("content", event.content)
            put("delegation_id", event.delegationId?.let(::JsonPrimitive) ?: JsonNull)
            event.eventId?.let { put("event_id", it) }
        }
    }
    RealtimeClientEvent.InputAudioCommit -> liveCommandUnsupported("input-audio-commit")
    RealtimeClientEvent.InputAudioClear -> liveCommandUnsupported("input-audio-clear")
    is RealtimeClientEvent.ConversationItemCreate -> liveCommandUnsupported("conversation-item-create")
    is RealtimeClientEvent.ConversationItemTruncate -> liveCommandUnsupported("conversation-item-truncate")
    is RealtimeClientEvent.ResponseCreate -> liveCommandUnsupported("response-create")
    RealtimeClientEvent.ResponseCancel -> liveCommandUnsupported("response-cancel")
}

private fun liveCommandUnsupported(command: String): Nothing = throw UnsupportedFunctionalityError(
    "OpenAI Live command: $command; use continuous audio and context-append instead of voice-turn commands",
)
