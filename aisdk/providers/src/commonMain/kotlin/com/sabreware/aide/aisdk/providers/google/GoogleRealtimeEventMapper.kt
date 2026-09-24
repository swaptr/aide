package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.RealtimeClientEvent
import com.sabreware.aide.aisdk.RealtimeConversationItem
import com.sabreware.aide.aisdk.RealtimeServerEvent
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.parseJsonElementOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The Live API's default capture rate, and the one it assumes when a blob's `mimeType` names none. */
private const val DEFAULT_INPUT_AUDIO_RATE: Int = 16_000

private const val COMPLETED: String = "completed"

/**
 * Gemini Live's frames, mapped onto the neutral events in both directions.
 *
 * **Stateful, by necessity.** Google's frames carry no response or item ids, and one `serverContent`
 * frame can hold audio, a transcript delta and a turn boundary at once — so the ids the contract requires
 * are synthesized from a turn counter, and the `*Done` events on `turnComplete` are emitted only for the
 * kinds of content the turn actually produced. The reference makes the same choice.
 *
 * The turn rolls over LAZILY: `turnComplete` marks the turn closed, and the counter advances only when the
 * next model content arrives. Google delivers transcription independently of the content it transcribes,
 * with no ordering guarantee against `turnComplete`, so a transcript that lands just after the boundary
 * has to stay attached to the turn it belongs to — an eager rollover would file it under the next one.
 *
 * The capture rate is remembered from the session update for the same reason a blob must label itself
 * truthfully: Google accepts any rate as long as the `mimeType` matches, and a mislabelled rate corrupts
 * custom-rate audio rather than rejecting it.
 */
internal class GoogleRealtimeEventMapper {

    private var turnCounter = 0
    private var hasAudio = false
    private var hasText = false
    private var hasTranscript = false
    private var turnClosed = false
    private var inputAudioRate = DEFAULT_INPUT_AUDIO_RATE

    private val responseId: String get() = "google-resp-$turnCounter"
    private val itemId: String get() = "google-item-$turnCounter"
    private val inputItemId: String get() = "google-input-$turnCounter"

    private fun beginTurnIfClosed() {
        if (!turnClosed) return
        turnCounter++
        hasAudio = false
        hasText = false
        hasTranscript = false
        turnClosed = false
    }

    fun parseServerEvent(raw: JsonElement): List<RealtimeServerEvent> {
        val frame = raw as? JsonObject ?: return listOf(RealtimeServerEvent.Custom(raw, rawType = ""))
        fun custom(type: String) = listOf(RealtimeServerEvent.Custom(raw, rawType = type))

        return when {
            frame.has("setupComplete") -> listOf(RealtimeServerEvent.SessionCreated(raw))
            frame.has("toolCall") -> toolCalls(frame["toolCall"], raw)
            frame.has("toolCallCancellation") -> custom("toolCallCancellation")
            frame.has("goAway") -> custom("goAway")
            frame.has("sessionResumptionUpdate") -> custom("sessionResumptionUpdate")
            frame.has("serverContent") -> serverContent(frame["serverContent"] as? JsonObject ?: JsonObject(emptyMap()), raw)
            else -> {
                // Early sessions put the input transcription at the top level; both spellings are live.
                val transcript = frame.optObject("inputTranscription")?.optString("text")
                if (transcript != null) {
                    listOf(RealtimeServerEvent.InputTranscriptionCompleted(raw, inputItemId, transcript))
                } else {
                    custom(frame.keys.firstOrNull() ?: "")
                }
            }
        }
    }

    /**
     * A tool call arrives complete, so each becomes the delta-then-done pair a streaming consumer expects.
     * A call missing its id or name cannot be answered, and a frame carrying one is reported whole rather
     * than half-mapped.
     */
    private fun toolCalls(toolCall: JsonElement?, raw: JsonElement): List<RealtimeServerEvent> {
        beginTurnIfClosed()
        val calls = (toolCall as? JsonObject)?.optArray("functionCalls").orEmpty().map { it as? JsonObject }
        if (calls.any { it == null || it.optString("id") == null || it.optString("name") == null }) {
            return listOf(RealtimeServerEvent.Custom(raw, rawType = "toolCall"))
        }
        return calls.filterNotNull().flatMap { call ->
            val callId = checkNotNull(call.optString("id"))
            val name = checkNotNull(call.optString("name"))
            val args = call["args"]?.takeIf { it !is JsonNull }?.toString() ?: "{}"
            listOf(
                RealtimeServerEvent.FunctionCallArgumentsDelta(raw, responseId, itemId, callId, delta = args),
                RealtimeServerEvent.FunctionCallArgumentsDone(raw, responseId, itemId, callId, name, arguments = args),
            )
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun serverContent(content: JsonObject, raw: JsonElement): List<RealtimeServerEvent> {
        val events = mutableListOf<RealtimeServerEvent>()

        if (content.optBoolean("interrupted") == true) events += RealtimeServerEvent.SpeechStarted(raw)

        content.optObject("modelTurn")?.optArray("parts")?.let { parts ->
            // New model content is what opens the next turn.
            beginTurnIfClosed()
            parts.mapNotNull { it as? JsonObject }.forEach { part ->
                part.optObject("inlineData")?.optString("data")?.takeIf { it.isNotEmpty() }?.let { audio ->
                    hasAudio = true
                    events += RealtimeServerEvent.AudioDelta(raw, responseId, itemId, delta = audio)
                }
                part.optString("text")?.takeIf { it.isNotEmpty() }?.let { text ->
                    hasText = true
                    events += RealtimeServerEvent.TextDelta(raw, responseId, itemId, delta = text)
                }
            }
        }

        content.optObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { text ->
            hasTranscript = true
            events += RealtimeServerEvent.AudioTranscriptDelta(raw, responseId, itemId, delta = text)
        }

        content.optObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { text ->
            events += RealtimeServerEvent.InputTranscriptionCompleted(raw, inputItemId, transcript = text)
        }

        // Generation stopping is not the turn ending: playback and the turn stay open, so this is kept
        // distinct from ResponseDone, which only `turnComplete` produces.
        if (content.optBoolean("generationComplete") == true) {
            events += RealtimeServerEvent.Custom(raw, rawType = "generationComplete")
        }

        // `interactionStatus` (IN_PROGRESS | IDLE | WAITING_FOR_INPUT) is the definitive activity signal for
        // a background-reasoning model: `turnComplete` no longer implies idle, since asynchronous tool calls
        // and audio may still follow. `waitingForInput` is Proactive Audio's turn-taking signal — the model
        // has yielded the floor and is not generating because it expects the user to continue.
        if (content.has("interactionStatus")) events += RealtimeServerEvent.Custom(raw, rawType = "interactionStatus")
        if (content.optBoolean("waitingForInput") == true) {
            events += RealtimeServerEvent.Custom(raw, rawType = "waitingForInput")
        }

        if (content.optBoolean("turnComplete") == true) {
            if (hasAudio) events += RealtimeServerEvent.AudioDone(raw, responseId, itemId)
            if (hasText) events += RealtimeServerEvent.TextDone(raw, responseId, itemId)
            if (hasTranscript) events += RealtimeServerEvent.AudioTranscriptDone(raw, responseId, itemId)
            events += RealtimeServerEvent.ResponseDone(raw, responseId, status = COMPLETED)
            // Closed, not advanced — see the class doc for why the counter waits.
            turnClosed = true
        }

        return events.ifEmpty { listOf(RealtimeServerEvent.Custom(raw, rawType = "serverContent")) }
    }

    /**
     * A client event in the Live API's frame shape, or [JsonNull] for the ones Google has no frame for.
     *
     * Null rather than a silent drop lets a session layer tell "nothing to send" from "the vendor
     * rejected it". Gemini Live has no buffer clear, no explicit response trigger or cancel, no truncate
     * and no whole-utterance audio item: turns are driven by its own detection and by `audioStreamEnd`.
     */
    fun serializeClientEvent(event: RealtimeClientEvent, modelId: String): JsonElement = when (event) {
        is RealtimeClientEvent.SessionUpdate -> {
            event.config.inputAudioFormat?.rate?.let { inputAudioRate = it }
            buildJsonObject { put("setup", buildGoogleSessionConfig(event.config, modelId)) }
        }
        is RealtimeClientEvent.InputAudioAppend -> buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("audio") {
                    put("data", event.audio)
                    put("mimeType", "audio/pcm;rate=$inputAudioRate")
                }
            }
        }
        RealtimeClientEvent.InputAudioCommit -> buildJsonObject {
            putJsonObject("realtimeInput") { put("audioStreamEnd", true) }
        }
        is RealtimeClientEvent.ConversationItemCreate -> event.item.toFrame()
        RealtimeClientEvent.InputAudioClear,
        is RealtimeClientEvent.ResponseCreate,
        RealtimeClientEvent.ResponseCancel,
        is RealtimeClientEvent.ConversationItemTruncate,
        // The continuous-session commands are OpenAI Live's; Gemini Live has no frame for them either.
        is RealtimeClientEvent.SessionStart,
        is RealtimeClientEvent.SessionClose,
        is RealtimeClientEvent.InputAudioMute,
        is RealtimeClientEvent.InputAudioUnmute,
        is RealtimeClientEvent.ContextAppend,
        -> JsonNull
    }
}

private fun JsonObject.has(key: String): Boolean = this[key]?.let { it !is JsonNull } == true

private fun RealtimeConversationItem.toFrame(): JsonElement = when (this) {
    is RealtimeConversationItem.TextMessage -> buildJsonObject {
        putJsonObject("realtimeInput") { put("text", text) }
    }
    is RealtimeConversationItem.FunctionCallOutput -> buildJsonObject {
        putJsonObject("toolResponse") {
            putJsonArray("functionResponses") {
                add(
                    buildJsonObject {
                        put("id", callId)
                        // Google routes the result by name as well as id; a result with neither is a
                        // turn the model waits on forever, so the name is sent whenever the caller has it.
                        name?.let { put("name", it) }
                        put("response", output.toFunctionResponse())
                    },
                )
            }
        }
    }
    is RealtimeConversationItem.AudioMessage -> JsonNull
}

/**
 * The tool's output as the `Struct` Google requires.
 *
 * `functionResponse.response` is typed `google.protobuf.Struct` — an object and nothing else — and a
 * caller's tool can return a string, a number, an array or `null` as perfectly valid JSON. Sent bare,
 * Gemini closed the socket with 1007 and the close code was dropped on the way back, so the application
 * saw a plain disconnect. The field's own docstring names the wrapper: "use `output` key to specify
 * function output". An object passes through unchanged; everything else goes under `output` — including
 * text that is not JSON at all, which used to become `{}` and tell the model the tool had returned an
 * empty object, with nothing thrown and the socket still up.
 */
private fun String.toFunctionResponse(): JsonObject = when (val parsed = parseJsonElementOrNull(this)) {
    is JsonObject -> parsed
    null -> buildJsonObject { put("output", this@toFunctionResponse) }
    else -> buildJsonObject { put("output", parsed) }
}
