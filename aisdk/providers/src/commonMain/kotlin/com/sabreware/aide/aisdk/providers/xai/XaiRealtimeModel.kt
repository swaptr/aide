package com.sabreware.aide.aisdk.providers.xai

import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.RealtimeClientEvent
import com.sabreware.aide.aisdk.RealtimeClientSecret
import com.sabreware.aide.aisdk.RealtimeClientSecretOptions
import com.sabreware.aide.aisdk.RealtimeConnection
import com.sabreware.aide.aisdk.RealtimeConversationItem
import com.sabreware.aide.aisdk.RealtimeModel
import com.sabreware.aide.aisdk.RealtimeServerEvent
import com.sabreware.aide.aisdk.RealtimeSessionConfig
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.RealtimeTurnDetection
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.webSocketUrl
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * xAI's realtime voice API.
 *
 * The credential story is the whole reason [RealtimeModel] has the shape it does, and xAI shows it
 * plainly: [doCreateClientSecret] runs where the real API key lives and mints a short-lived token, and
 * the socket is then opened — usually by something else entirely, holding the microphone — carrying
 * that token in a **subprotocol** rather than a header. A browser cannot set a request header on a
 * WebSocket, so the field xAI reads is the only one such a client can populate.
 *
 * The model id rides in the URL's query string, not the session payload. xAI silently falls back to its
 * default voice model when it is missing, so a caller's model choice would be ignored with nothing to
 * say why.
 */
internal class XaiRealtimeModel(
    override val modelId: String,
    http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : RealtimeModel {

    override val provider: String = XAI_PROVIDER_ID

    private val http = http.withErrorStructure(XaiErrors)

    override suspend fun doCreateClientSecret(
        options: RealtimeClientSecretOptions,
    ): RealtimeClientSecret {
        val body = buildJsonObject {
            options.expiresAfterSeconds?.let { putJsonObject("expires_after") { put("seconds", it) } }
        }
        val result = http.postJson("$baseUrl/realtime/client_secrets", body, headers)
        val response = result.value as? JsonObject
        val token = response?.optString("value")
            ?: throw NoContentGeneratedError("xAI returned no client secret value.")

        return RealtimeClientSecret(
            token = token,
            // The socket lives on the same host as the REST base, at `/v1/realtime` — reconstructed
            // from the base rather than hardcoded so a proxy or a regional host still resolves.
            url = webSocketUrl(realtimeUrl()) + "?model=" + modelId.encodeURLParameter(),
            expiresAt = response.optInt("expires_at")?.toLong(),
        )
    }

    /** `/v1/realtime` on the REST base's host, with any path the base carried preserved. */
    private fun realtimeUrl(): String = baseUrl.trimEnd('/') + "/realtime"

    override fun webSocketConfig(token: String, url: String): RealtimeConnection = RealtimeConnection(
        url = url,
        // Authentication, not content negotiation — see SUBPROTOCOL_HEADER.
        protocols = listOf("xai-client-secret.$token"),
    )

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override fun parseServerEvent(raw: JsonElement): List<RealtimeServerEvent> {
        val event = raw as? JsonObject ?: return listOf(RealtimeServerEvent.Custom(raw, rawType = ""))
        val type = event.optString("type") ?: return listOf(RealtimeServerEvent.Custom(raw, rawType = ""))

        // Ids that the contract requires non-null: a frame missing one is malformed for that event
        // type, and reporting it as Custom keeps the raw payload rather than inventing an empty id.
        val itemId = event.optString("item_id")
        val responseId = event.optString("response_id")
        fun custom() = RealtimeServerEvent.Custom(raw, rawType = type)

        val mapped: RealtimeServerEvent = when (type) {
            "session.created" -> RealtimeServerEvent.SessionCreated(
                raw = raw,
                sessionId = (event["session"] as? JsonObject)?.optString("id"),
            )
            "session.updated" -> RealtimeServerEvent.SessionUpdated(raw)

            "input_audio_buffer.speech_started" ->
                RealtimeServerEvent.SpeechStarted(raw, itemId = itemId)
            "input_audio_buffer.speech_stopped" ->
                RealtimeServerEvent.SpeechStopped(raw, itemId = itemId)
            "input_audio_buffer.committed" -> RealtimeServerEvent.AudioCommitted(
                raw = raw,
                itemId = itemId,
                previousItemId = event.optString("previous_item_id"),
            )

            "conversation.item.added" -> {
                val item = event["item"]
                val id = (item as? JsonObject)?.optString("id") ?: itemId
                if (item == null || id == null) custom() else
                    RealtimeServerEvent.ConversationItemAdded(raw, itemId = id, item = item)
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = event.optString("transcript")
                if (itemId == null || transcript == null) custom() else
                    RealtimeServerEvent.InputTranscriptionCompleted(raw, itemId, transcript)
            }

            "response.created" -> (event["response"] as? JsonObject)?.optString("id")
                ?.let { RealtimeServerEvent.ResponseCreated(raw, responseId = it) } ?: custom()
            "response.done" -> (event["response"] as? JsonObject)?.let { response ->
                val id = response.optString("id")
                val status = response.optString("status")
                if (id == null || status == null) null else RealtimeServerEvent.ResponseDone(raw, id, status)
            } ?: custom()

            "response.output_item.added" -> pair(responseId, itemId)
                ?.let { (r, i) -> RealtimeServerEvent.OutputItemAdded(raw, r, i) } ?: custom()
            "response.output_item.done" -> pair(responseId, itemId)
                ?.let { (r, i) -> RealtimeServerEvent.OutputItemDone(raw, r, i) } ?: custom()
            "response.content_part.added" -> pair(responseId, itemId)
                ?.let { (r, i) -> RealtimeServerEvent.ContentPartAdded(raw, r, i) } ?: custom()
            "response.content_part.done" -> pair(responseId, itemId)
                ?.let { (r, i) -> RealtimeServerEvent.ContentPartDone(raw, r, i) } ?: custom()

            "response.output_audio.delta" -> delta(event)?.let { d ->
                pair(responseId, itemId)?.let { (r, i) -> RealtimeServerEvent.AudioDelta(raw, r, i, d) }
            } ?: custom()
            "response.output_audio.done" -> pair(responseId, itemId)
                ?.let { (r, i) -> RealtimeServerEvent.AudioDone(raw, r, i) } ?: custom()

            "response.output_audio_transcript.delta" -> delta(event)?.let { d ->
                pair(responseId, itemId)
                    ?.let { (r, i) -> RealtimeServerEvent.AudioTranscriptDelta(raw, r, i, d) }
            } ?: custom()
            "response.output_audio_transcript.done" -> pair(responseId, itemId)?.let { (r, i) ->
                RealtimeServerEvent.AudioTranscriptDone(raw, r, i, event.optString("transcript"))
            } ?: custom()

            "response.text.delta" -> delta(event)?.let { d ->
                pair(responseId, itemId)?.let { (r, i) -> RealtimeServerEvent.TextDelta(raw, r, i, d) }
            } ?: custom()
            "response.text.done" -> pair(responseId, itemId)?.let { (r, i) ->
                RealtimeServerEvent.TextDone(raw, r, i, event.optString("text"))
            } ?: custom()

            "response.function_call_arguments.delta" -> {
                val callId = event.optString("call_id")
                val d = delta(event)
                if (responseId == null || itemId == null || callId == null || d == null) custom() else
                    RealtimeServerEvent.FunctionCallArgumentsDelta(raw, responseId, itemId, callId, d)
            }
            "response.function_call_arguments.done" -> {
                val callId = event.optString("call_id")
                val name = event.optString("name")
                val arguments = event.optString("arguments")
                if (responseId == null || itemId == null || callId == null ||
                    name == null || arguments == null
                ) {
                    custom()
                } else {
                    RealtimeServerEvent.FunctionCallArgumentsDone(
                        raw, responseId, itemId, callId, name, arguments,
                    )
                }
            }

            "error" -> RealtimeServerEvent.Error(
                raw = raw,
                // xAI nests the failure under `error` on some frames and flattens it on others; a
                // reader that knows only one shape reports "Unknown error" for half of them.
                message = (event["error"] as? JsonObject)?.optString("message")
                    ?: event.optString("message")
                    ?: "Unknown error",
                code = (event["error"] as? JsonObject)?.optString("code") ?: event.optString("code"),
            )

            else -> custom()
        }
        return listOf(mapped)
    }

    override fun serializeClientEvent(event: RealtimeClientEvent): JsonElement = when (event) {
        is RealtimeClientEvent.SessionUpdate -> buildJsonObject {
            put("type", "session.update")
            put("session", buildSessionConfig(event.config))
        }
        is RealtimeClientEvent.InputAudioAppend -> buildJsonObject {
            put("type", "input_audio_buffer.append")
            put("audio", event.audio)
        }
        RealtimeClientEvent.InputAudioCommit -> typeOnly("input_audio_buffer.commit")
        RealtimeClientEvent.InputAudioClear -> typeOnly("input_audio_buffer.clear")
        is RealtimeClientEvent.ConversationItemCreate -> buildJsonObject {
            put("type", "conversation.item.create")
            put("item", event.item.toXaiItem())
        }
        // xAI has no truncate frame. Serializing it as `null` rather than dropping it silently is what
        // lets a session layer tell "nothing to send" from "the vendor rejected it".
        is RealtimeClientEvent.ConversationItemTruncate -> JsonNull
        is RealtimeClientEvent.ResponseCreate -> buildJsonObject {
            put("type", "response.create")
            val modalities = event.modalities
            val instructions = event.instructions
            if (modalities != null || instructions != null) {
                putJsonObject("response") {
                    modalities?.let { list ->
                        putJsonArray("modalities") { list.forEach { add(it.name.lowercase()) } }
                    }
                    instructions?.let { put("instructions", it) }
                }
            }
        }
        RealtimeClientEvent.ResponseCancel -> typeOnly("response.cancel")
        // The continuous-session commands are OpenAI Live's; xAI's turn-based wire has no frame for them.
        is RealtimeClientEvent.SessionStart -> continuousSessionUnsupported("session-start")
        is RealtimeClientEvent.SessionClose -> continuousSessionUnsupported("session-close")
        is RealtimeClientEvent.InputAudioMute -> continuousSessionUnsupported("input-audio-mute")
        is RealtimeClientEvent.InputAudioUnmute -> continuousSessionUnsupported("input-audio-unmute")
        is RealtimeClientEvent.ContextAppend -> continuousSessionUnsupported("context-append")
    }

    private fun continuousSessionUnsupported(eventType: String): Nothing = throw UnsupportedFunctionalityError(
        functionality = "realtime client event \"$eventType\"",
        message = "xAI's realtime API does not support the continuous-session client event \"$eventType\".",
    )

    override fun buildSessionConfig(config: RealtimeSessionConfig): JsonElement = buildJsonObject {
        config.instructions?.let { put("instructions", it) }
        config.voice?.let { put("voice", it) }

        // `audio` is emitted only when a side of it was actually configured: an empty object here
        // overrides xAI's defaults with nothing, which is audible.
        val input = config.inputAudioFormat?.toXaiFormat()
        val output = config.outputAudioFormat?.toXaiFormat()
        if (input != null || output != null) {
            putJsonObject("audio") {
                input?.let { putJsonObject("input") { put("format", it) } }
                output?.let { putJsonObject("output") { put("format", it) } }
            }
        }

        when (val detection = config.turnDetection) {
            null -> Unit
            // Explicit null is how xAI spells push-to-talk; omitting the key takes its automatic VAD.
            RealtimeTurnDetection.Disabled -> put("turn_detection", JsonNull)
            is RealtimeTurnDetection.ServerVad -> put(
                "turn_detection",
                vad(detection.threshold, detection.silenceDurationMs, detection.prefixPaddingMs),
            )
            // xAI serves one detector. A semantic request becomes server VAD with the same knobs
            // rather than an unknown `type` the server would reject outright.
            is RealtimeTurnDetection.SemanticVad -> put(
                "turn_detection",
                vad(detection.threshold, detection.silenceDurationMs, detection.prefixPaddingMs),
            )
        }

        val declared = config.tools.orEmpty().map { tool ->
            buildJsonObject {
                put("type", "function")
                put("name", tool.name)
                tool.description?.let { put("description", it) }
                put("parameters", tool.parameters)
            }
        }
        // Vendor tools are APPENDED to the declared ones rather than replacing them: they are a
        // different kind of tool (xAI's own, server-run), and a caller offering both means both.
        val vendor = config.providerOptions?.get(XAI_PROVIDER_ID)
        val vendorTools = (vendor?.get("tools") as? JsonArray).orEmpty()
        if (declared.isNotEmpty() || vendorTools.isNotEmpty()) {
            put("tools", buildJsonArray { declared.forEach { add(it) }; vendorTools.forEach { add(it) } })
        }
        vendor?.forEach { (key, value) -> if (key != "tools") put(key, value) }
    }

    private fun vad(threshold: Double?, silenceMs: Int?, prefixMs: Int?): JsonObject = buildJsonObject {
        put("type", "server_vad")
        threshold?.let { put("threshold", it) }
        silenceMs?.let { put("silence_duration_ms", it) }
        prefixMs?.let { put("prefix_padding_ms", it) }
    }
}

/** xAI's audio format object: a type, and a rate when the format needs one. */
private fun AudioFormat.toXaiFormat(): JsonObject = buildJsonObject {
    put("type", type)
    rate?.let { put("rate", it) }
}

/**
 * A conversation item in xAI's shape; the content wrapper differs per item kind.
 *
 * The role is always `user`: this contract's conversation items are what a CLIENT adds to the
 * conversation directly rather than by speaking, so there is no role to carry and none to guess. (The
 * reference reads one off its own item type, which models assistant-authored items too.)
 */
private fun RealtimeConversationItem.toXaiItem(): JsonObject = when (this) {
    is RealtimeConversationItem.TextMessage -> buildJsonObject {
        put("type", "message")
        put("role", "user")
        putJsonArray("content") {
            add(buildJsonObject { put("type", "input_text"); put("text", text) })
        }
    }
    is RealtimeConversationItem.AudioMessage -> buildJsonObject {
        put("type", "message")
        put("role", "user")
        putJsonArray("content") {
            add(buildJsonObject { put("type", "input_audio"); put("audio", audio) })
        }
    }
    is RealtimeConversationItem.FunctionCallOutput -> buildJsonObject {
        put("type", "function_call_output")
        put("call_id", callId)
        put("output", output)
    }
}

private fun typeOnly(type: String): JsonObject = buildJsonObject { put("type", type) }

private fun delta(event: JsonObject): String? = event.optString("delta")

private fun pair(first: String?, second: String?): Pair<String, String>? =
    if (first == null || second == null) null else first to second
