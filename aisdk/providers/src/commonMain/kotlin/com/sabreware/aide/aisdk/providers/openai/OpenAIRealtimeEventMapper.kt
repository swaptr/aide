package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.RealtimeClientEvent
import com.sabreware.aide.aisdk.RealtimeConversationItem
import com.sabreware.aide.aisdk.RealtimeModality
import com.sabreware.aide.aisdk.RealtimeServerEvent
import com.sabreware.aide.aisdk.RealtimeSessionConfig
import com.sabreware.aide.aisdk.RealtimeToolDefinition
import com.sabreware.aide.aisdk.RealtimeTranscriptionConfig
import com.sabreware.aide.aisdk.RealtimeTurnDetection
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.providers.options.optString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The transcriber OpenAI is asked for when a caller enables input transcription without naming one.
 *
 * Asked for explicitly rather than left to the server: `audio.input.transcription` with no `model` is
 * a request the endpoint may reject or fill with a default that changes under the caller, and the whole
 * point of `inputAudioTranscription = {}` is "transcribe what the user said" rather than "pick whatever
 * is current". `gpt-realtime-whisper` is OpenAI's streaming transcriber built for this wire.
 */
internal const val OPENAI_REALTIME_DEFAULT_TRANSCRIPTION_MODEL: String = "gpt-realtime-whisper"

// ---------------------------------------------------------------------------------------------------
// Server frames → neutral events
// ---------------------------------------------------------------------------------------------------

/**
 * One OpenAI server frame as a neutral event.
 *
 * The reference types the wire loosely and passes `undefined` through where an id is missing. The
 * contract here requires those ids, so a frame that lacks one for its type is reported as
 * [RealtimeServerEvent.Custom] with the payload intact — a malformed frame keeps its evidence rather
 * than becoming an event with an invented empty id that a session layer would then correlate on.
 */
internal fun parseOpenAIRealtimeServerEvent(raw: JsonElement): RealtimeServerEvent {
    val event = raw as? JsonObject ?: return RealtimeServerEvent.Custom(raw, rawType = "")
    val type = event.optString("type") ?: return RealtimeServerEvent.Custom(raw, rawType = "")
    val frame = OpenAIRealtimeFrame(raw, event, type)

    return when (type) {
        "session.created" -> RealtimeServerEvent.SessionCreated(
            raw = raw,
            sessionId = (event["session"] as? JsonObject)?.optString("id"),
        )
        "session.updated" -> RealtimeServerEvent.SessionUpdated(raw)

        "input_audio_buffer.speech_started" -> RealtimeServerEvent.SpeechStarted(raw, itemId = frame.itemId)
        "input_audio_buffer.speech_stopped" -> RealtimeServerEvent.SpeechStopped(raw, itemId = frame.itemId)
        "input_audio_buffer.committed" -> RealtimeServerEvent.AudioCommitted(
            raw = raw,
            itemId = frame.itemId,
            previousItemId = event.optString("previous_item_id"),
        )

        "conversation.item.added" -> frame.conversationItemAdded()
        "conversation.item.input_audio_transcription.completed" -> frame.inputTranscriptionCompleted()

        "response.created" -> frame.responseCreated()
        "response.done" -> frame.responseDone()

        // Output items name themselves inside `item`; the flat `item_id` is the fallback, as upstream.
        "response.output_item.added" -> frame.ids(frame.nestedItemId) { r, i ->
            RealtimeServerEvent.OutputItemAdded(raw, r, i)
        }
        "response.output_item.done" -> frame.ids(frame.nestedItemId) { r, i ->
            RealtimeServerEvent.OutputItemDone(raw, r, i)
        }
        "response.content_part.added" -> frame.ids { r, i -> RealtimeServerEvent.ContentPartAdded(raw, r, i) }
        "response.content_part.done" -> frame.ids { r, i -> RealtimeServerEvent.ContentPartDone(raw, r, i) }

        "response.output_audio.delta" -> frame.withDelta { r, i, d -> RealtimeServerEvent.AudioDelta(raw, r, i, d) }
        "response.output_audio.done" -> frame.ids { r, i -> RealtimeServerEvent.AudioDone(raw, r, i) }

        "response.output_audio_transcript.delta" -> frame.withDelta { r, i, d ->
            RealtimeServerEvent.AudioTranscriptDelta(raw, r, i, d)
        }
        "response.output_audio_transcript.done" -> frame.ids { r, i ->
            RealtimeServerEvent.AudioTranscriptDone(raw, r, i, transcript = event.optString("transcript"))
        }

        // GA names. The beta's `response.text.*` is not aliased: a socket opened against this wire never
        // receives it, and a silent alias would hide a client that connected to the wrong endpoint.
        "response.output_text.delta" -> frame.withDelta { r, i, d -> RealtimeServerEvent.TextDelta(raw, r, i, d) }
        "response.output_text.done" -> frame.ids { r, i ->
            RealtimeServerEvent.TextDone(raw, r, i, text = event.optString("text"))
        }

        "response.function_call_arguments.delta" -> frame.functionCallArgumentsDelta()
        "response.function_call_arguments.done" -> frame.functionCallArgumentsDone()

        "error" -> frame.error()

        else -> frame.custom()
    }
}

/** One frame with the field reads every branch of the mapper shares. */
private class OpenAIRealtimeFrame(
    val raw: JsonElement,
    val event: JsonObject,
    val type: String,
) {

    val itemId: String? get() = event.optString("item_id")

    val responseId: String? get() = event.optString("response_id")

    val item: JsonElement? get() = event["item"]

    val response: JsonObject? get() = event["response"] as? JsonObject

    /** `item.id` first, then the flat `item_id` — the order the reference reads them in. */
    val nestedItemId: String? get() = (item as? JsonObject)?.optString("id") ?: itemId

    /** `response.id` first, then the flat `response_id`. */
    val nestedResponseId: String? get() = response?.optString("id") ?: responseId

    fun custom(): RealtimeServerEvent = RealtimeServerEvent.Custom(raw, rawType = type)

    /** Builds an event that needs both correlation ids, or reports the frame as [custom] without them. */
    fun ids(
        itemId: String? = this.itemId,
        build: (responseId: String, itemId: String) -> RealtimeServerEvent,
    ): RealtimeServerEvent {
        val r = responseId ?: return custom()
        val i = itemId ?: return custom()
        return build(r, i)
    }

    fun withDelta(
        build: (responseId: String, itemId: String, delta: String) -> RealtimeServerEvent,
    ): RealtimeServerEvent = event.optString("delta")?.let { d -> ids { r, i -> build(r, i, d) } } ?: custom()

    fun conversationItemAdded(): RealtimeServerEvent {
        val item = item ?: return custom()
        val id = nestedItemId ?: return custom()
        return RealtimeServerEvent.ConversationItemAdded(raw, itemId = id, item = item)
    }

    // An empty transcript rather than a dropped event: the reference defaults it, and a chat UI that
    // opened a user bubble on `speech_started` needs the completion to close it even when nothing was
    // heard.
    fun inputTranscriptionCompleted(): RealtimeServerEvent = itemId?.let { id ->
        RealtimeServerEvent.InputTranscriptionCompleted(raw, id, transcript = event.optString("transcript") ?: "")
    } ?: custom()

    fun responseCreated(): RealtimeServerEvent = nestedResponseId
        ?.let { RealtimeServerEvent.ResponseCreated(raw, responseId = it) } ?: custom()

    // `completed` when the status is missing, as upstream: a `response.done` is the end of an answer
    // whatever else it says, and a session layer needs a word to file it under.
    fun responseDone(): RealtimeServerEvent = nestedResponseId?.let { id ->
        RealtimeServerEvent.ResponseDone(raw, id, status = response?.optString("status") ?: "completed")
    } ?: custom()

    fun functionCallArgumentsDelta(): RealtimeServerEvent {
        val callId = event.optString("call_id") ?: return custom()
        return withDelta { r, i, d -> RealtimeServerEvent.FunctionCallArgumentsDelta(raw, r, i, callId, d) }
    }

    fun functionCallArgumentsDone(): RealtimeServerEvent {
        val callId = event.optString("call_id") ?: return custom()
        val name = event.optString("name") ?: return custom()
        val arguments = event.optString("arguments") ?: return custom()
        return ids { r, i -> RealtimeServerEvent.FunctionCallArgumentsDone(raw, r, i, callId, name, arguments) }
    }

    // The failure is nested under `error` on the documented frame; the flat shape is read too, as the
    // reference does, so a proxy that flattens it does not turn every failure into "Unknown error".
    // `error.event_id` names the CLIENT frame that was rejected — the correlation a session that
    // updates often needs — and the frame's own top-level `event_id` is the server's, not read here.
    fun error(): RealtimeServerEvent {
        val nested = event["error"] as? JsonObject
        return RealtimeServerEvent.Error(
            raw = raw,
            message = nested?.optString("message") ?: event.optString("message") ?: "Unknown error",
            code = nested?.optString("code") ?: event.optString("code"),
            clientEventId = nested?.optString("event_id"),
        )
    }
}

// ---------------------------------------------------------------------------------------------------
// Neutral client events → OpenAI frames
// ---------------------------------------------------------------------------------------------------

/** One neutral client event in OpenAI's frame shape. */
internal fun serializeOpenAIRealtimeClientEvent(
    event: RealtimeClientEvent,
    modelId: String,
): JsonElement = when (event) {
    // `event_id` is sent whenever the caller set one — an empty string included, as upstream — so a
    // rejection can be matched back to the frame it answers.
    is RealtimeClientEvent.SessionUpdate -> buildJsonObject {
        put("type", "session.update")
        put("session", buildOpenAISessionConfig(event.config, modelId))
        event.eventId?.let { put("event_id", it) }
    }
    is RealtimeClientEvent.InputAudioAppend -> buildJsonObject {
        put("type", "input_audio_buffer.append")
        put("audio", event.audio)
        event.eventId?.let { put("event_id", it) }
    }
    RealtimeClientEvent.InputAudioCommit -> typeOnly("input_audio_buffer.commit")
    RealtimeClientEvent.InputAudioClear -> typeOnly("input_audio_buffer.clear")
    is RealtimeClientEvent.ConversationItemCreate -> buildJsonObject {
        put("type", "conversation.item.create")
        put("item", event.item.toOpenAIItem())
    }
    is RealtimeClientEvent.ConversationItemTruncate -> buildJsonObject {
        put("type", "conversation.item.truncate")
        put("item_id", event.itemId)
        put("content_index", event.contentIndex)
        put("audio_end_ms", event.audioEndMs)
    }
    is RealtimeClientEvent.ResponseCreate -> buildJsonObject {
        put("type", "response.create")
        // `response` only when there is something to say in it. The reference emits an empty object
        // for an options bag with nothing set; the contract here has no bag, so nothing set is no key.
        val modalities = event.modalities
        val instructions = event.instructions
        val metadata = event.metadata
        if (modalities != null || instructions != null || metadata != null) {
            putJsonObject("response") {
                modalities?.let { list -> putJsonArray("output_modalities") { list.forEach { add(it.wireName) } } }
                instructions?.let { put("instructions", it) }
                metadata?.let { put("metadata", it) }
            }
        }
    }
    RealtimeClientEvent.ResponseCancel -> typeOnly("response.cancel")
    // The continuous-session commands belong to the Live API — see OpenAILiveModel; the Realtime wire
    // has no frame for them, and a session layer that sends one here has picked the wrong model.
    is RealtimeClientEvent.SessionStart -> realtimeCommandUnsupported("session-start")
    is RealtimeClientEvent.SessionClose -> realtimeCommandUnsupported("session-close")
    is RealtimeClientEvent.InputAudioMute -> realtimeCommandUnsupported("input-audio-mute")
    is RealtimeClientEvent.InputAudioUnmute -> realtimeCommandUnsupported("input-audio-unmute")
    is RealtimeClientEvent.ContextAppend -> realtimeCommandUnsupported("context-append")
}

private fun realtimeCommandUnsupported(command: String): Nothing = throw UnsupportedFunctionalityError(
    "OpenAI Realtime command: $command; the continuous-session commands are served by the Live API (gpt-live-1)",
)

/**
 * A conversation item in OpenAI's shape.
 *
 * The role is always `user`: this contract's items are what a CLIENT adds to the conversation rather
 * than something the model said, so there is no role to carry. A tool result is routed by `call_id`
 * alone — OpenAI has no field for the name, so [RealtimeConversationItem.FunctionCallOutput.name] is
 * not sent.
 */
private fun RealtimeConversationItem.toOpenAIItem(): JsonObject = when (this) {
    is RealtimeConversationItem.TextMessage -> userMessage(
        buildJsonObject {
            put("type", "input_text")
            put("text", text)
        },
    )
    is RealtimeConversationItem.AudioMessage -> userMessage(
        buildJsonObject {
            put("type", "input_audio")
            put("audio", audio)
        },
    )
    is RealtimeConversationItem.FunctionCallOutput -> buildJsonObject {
        put("type", "function_call_output")
        put("call_id", callId)
        put("output", output)
    }
}

private fun userMessage(part: JsonObject): JsonObject = buildJsonObject {
    put("type", "message")
    put("role", "user")
    putJsonArray("content") { add(part) }
}

private fun typeOnly(type: String): JsonObject = buildJsonObject { put("type", type) }

// ---------------------------------------------------------------------------------------------------
// Session configuration
// ---------------------------------------------------------------------------------------------------

/**
 * The neutral session configuration as OpenAI's GA session object.
 *
 * `type` and `model` are always present — see `OpenAIRealtimeModel`. `audio` is emitted only when a
 * side of it was configured, and each side only when something in it was: an empty `audio.input`
 * overrides the server's defaults with nothing, which is audible.
 *
 * [RealtimeSessionConfig.outputAudioTranscription] is deliberately not mapped, as upstream: OpenAI
 * transcribes its own speech unconditionally (`response.output_audio_transcript.*`) and has no switch
 * for it, so there is nothing to send and a caller setting it is merely stating the default.
 *
 * Provider options are read from the `openai` namespace and merged onto the session last, so a caller
 * can reach a field this port has not modelled (`max_output_tokens`, `prompt`, `tracing`) and can
 * override one it has.
 */
internal fun buildOpenAISessionConfig(config: RealtimeSessionConfig, modelId: String): JsonObject =
    buildJsonObject {
        put("type", "realtime")
        put("model", modelId)
        config.instructions?.let { put("instructions", it) }
        config.outputModalities?.let { list ->
            putJsonArray("output_modalities") { list.forEach { add(it.wireName) } }
        }

        val audio = buildAudio(config)
        if (audio.isNotEmpty()) put("audio", audio)

        val tools = config.tools
        if (!tools.isNullOrEmpty()) {
            putJsonArray("tools") { tools.forEach { add(it.toOpenAITool()) } }
            // Declaring tools without a choice leaves the server's default, which is also `auto`; the
            // reference states it and so does this, so the frame reads the same as the one it pins.
            put("tool_choice", "auto")
        }

        config.providerOptions?.get(OPENAI_PROVIDER_ID)?.forEach { (key, value) -> put(key, value) }
    }

private fun buildAudio(config: RealtimeSessionConfig): JsonObject = buildJsonObject {
    val detection = config.turnDetection
    val transcription = config.inputAudioTranscription
    if (config.inputAudioFormat != null || transcription != null || detection != null) {
        putJsonObject("input") {
            config.inputAudioFormat?.let { put("format", it.toOpenAIFormat()) }
            when (detection) {
                null -> Unit
                // Explicit null is how OpenAI spells push-to-talk; omitting the key keeps server VAD.
                RealtimeTurnDetection.Disabled -> put("turn_detection", JsonNull)
                is RealtimeTurnDetection.ServerVad -> put(
                    "turn_detection",
                    turnDetection(
                        "server_vad", detection.threshold, detection.silenceDurationMs, detection.prefixPaddingMs,
                    ),
                )
                is RealtimeTurnDetection.SemanticVad -> put(
                    "turn_detection",
                    turnDetection(
                        "semantic_vad", detection.threshold, detection.silenceDurationMs, detection.prefixPaddingMs,
                    ),
                )
            }
            transcription?.let { put("transcription", it.toOpenAITranscription()) }
        }
    }
    if (config.outputAudioFormat != null || config.voice != null) {
        putJsonObject("output") {
            config.outputAudioFormat?.let { put("format", it.toOpenAIFormat()) }
            config.voice?.let { put("voice", it) }
        }
    }
}

private fun turnDetection(type: String, threshold: Double?, silenceMs: Int?, prefixMs: Int?): JsonObject =
    buildJsonObject {
        put("type", type)
        threshold?.let { put("threshold", it) }
        silenceMs?.let { put("silence_duration_ms", it) }
        prefixMs?.let { put("prefix_padding_ms", it) }
    }

private fun RealtimeTranscriptionConfig.toOpenAITranscription(): JsonObject = buildJsonObject {
    put("model", model ?: OPENAI_REALTIME_DEFAULT_TRANSCRIPTION_MODEL)
    language?.let { put("language", it) }
    prompt?.let { put("prompt", it) }
}

/** OpenAI's audio format object: a media type, and a rate when the format needs one. */
private fun AudioFormat.toOpenAIFormat(): JsonObject = buildJsonObject {
    put("type", type)
    rate?.let { put("rate", it) }
}

private fun RealtimeToolDefinition.toOpenAITool(): JsonObject = buildJsonObject {
    put("type", "function")
    put("name", name)
    description?.let { put("description", it) }
    put("parameters", parameters)
}

/** `Text` → `text`, `Audio` → `audio`: the vocabulary is OpenAI's, lower-cased. */
private val RealtimeModality.wireName: String get() = name.lowercase()
