package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.RealtimeSessionConfig
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The neutral session configuration as OpenAI Live's `session.start` payload — the reference's
 * `buildOpenAILiveSessionConfig`, WebSocket transport.
 *
 * Live is not Realtime with a new name, and the validation here is where that shows. The startup
 * settings are immutable for the session's life, so everything is checked BEFORE the frame goes out —
 * a rejected setting costs nothing, where a rejected `session.start` costs the socket:
 *
 * - The turn-based settings (`outputModalities`, transcription, `turnDetection`, `tools`) have no Live
 *   counterpart and are refused as unsupported, not dropped: a caller that set push-to-talk would
 *   otherwise get a session that listens continuously and never says why.
 * - Audio is ONE format for both directions (`audio.format`): PCM at 16 or 24 kHz, or G.711 μ-law /
 *   A-law at 8 kHz. Two different formats are an error, and none defaults to PCM 24 kHz.
 * - The `openai` provider options are a closed set — `delegation`, `input`, `store`, `voice`, and the
 *   WebRTC-only `client` — validated to the reference's schema, because an unknown key is a typo the
 *   server would silently ignore. `delegation: {type: "responses"}` is refused: only client delegation
 *   is served. A voice named both ways is ambiguous and refused; the default is `marin`.
 */
@Suppress("ThrowsCount")
internal fun buildOpenAILiveSessionConfig(config: RealtimeSessionConfig, modelId: String): JsonObject {
    listOf(
        "outputModalities" to config.outputModalities,
        "inputAudioTranscription" to config.inputAudioTranscription,
        "outputAudioTranscription" to config.outputAudioTranscription,
        "turnDetection" to config.turnDetection,
        "tools" to config.tools,
    ).firstOrNull { it.second != null }?.let { (key, _) ->
        throw UnsupportedFunctionalityError("OpenAI Live session setting: $key")
    }

    val options = OpenAILiveStartupOptions.parse(config.providerOptions?.get(OPENAI_PROVIDER_ID))
    if (options.voice != null && config.voice != null) {
        throw InvalidArgumentError(message = "Choose either voice or providerOptions.openai.voice.", argument = "voice")
    }

    val inputFormat = config.inputAudioFormat?.toLiveFormat("inputAudioFormat")
    val outputFormat = config.outputAudioFormat?.toLiveFormat("outputAudioFormat")
    if (inputFormat != null && outputFormat != null && inputFormat != outputFormat) {
        throw InvalidArgumentError(
            message = "OpenAI Live requires the same input and output audio format.",
            argument = "outputAudioFormat",
        )
    }

    return buildJsonObject {
        put("model", modelId)
        config.instructions?.let { put("instructions", it) }
        putJsonObject("audio") {
            put("format", inputFormat ?: outputFormat ?: DEFAULT_LIVE_AUDIO_FORMAT)
            putJsonObject("output") {
                put("voice", options.voice ?: config.voice?.let(::JsonPrimitive) ?: JsonPrimitive(DEFAULT_LIVE_VOICE))
            }
        }
        // `delegation: null` is a statement (no delegation), distinct from the key being absent.
        options.delegation?.let { put("delegation", it) }
        options.input?.let { put("input", it) }
        options.store?.let { put("store", it) }
    }
}

private const val DEFAULT_LIVE_VOICE = "marin"

private val DEFAULT_LIVE_AUDIO_FORMAT: JsonObject = buildJsonObject {
    put("type", "audio/pcm")
    put("rate", 24_000)
}

private val LIVE_AUDIO_FORMATS: Map<String, Set<Int>> = mapOf(
    "audio/pcm" to setOf(16_000, 24_000),
    "audio/pcma" to setOf(8_000),
    "audio/pcmu" to setOf(8_000),
)

/** The reference's `audioFormatSchema`: one of the three families at one of its rates, nothing else. */
private fun AudioFormat.toLiveFormat(argument: String): JsonObject {
    val rates = LIVE_AUDIO_FORMATS[type]
    if (rates == null || rate !in rates) {
        throw InvalidArgumentError(
            message = "OpenAI Live takes audio/pcm at 16000 or 24000 Hz, or audio/pcma / audio/pcmu at 8000 Hz; " +
                "got $type at $rate.",
            argument = argument,
        )
    }
    return buildJsonObject {
        put("type", type)
        put("rate", rate)
    }
}

/** The reference's `openaiRealtimeModelLiveOptionsSchema`, parsed strictly. */
private class OpenAILiveStartupOptions(
    /** Absent (null here) or the `delegation` value as given — `JsonNull` for an explicit null. */
    val delegation: JsonElement?,
    val input: JsonArray?,
    val store: Boolean?,
    /** `{id}` — the custom-voice object, kept as an object because that is how the wire spells it. */
    val voice: JsonObject?,
) {

    companion object {

        private val ALLOWED_KEYS = setOf("client", "delegation", "input", "store", "voice")
        private const val MAX_INPUT_ITEMS = 128

        @Suppress("ThrowsCount", "CyclomaticComplexMethod")
        fun parse(raw: JsonElement?): OpenAILiveStartupOptions {
            val options = raw as? JsonObject ?: JsonObject(emptyMap())
            options.keys.firstOrNull { it !in ALLOWED_KEYS }?.let { invalid("providerOptions.openai.$it is not a Live startup option") }

            // Responses delegation is a hosted-agent mode this port does not serve; a request for it is
            // refused before a socket send, as the reference does.
            val delegation = options["delegation"]
            if (delegation != null && delegation != JsonNull) {
                val type = (delegation as? JsonObject)?.stringOrNull("type")
                if (type == "responses") {
                    throw UnsupportedFunctionalityError("OpenAI Live Responses delegation; only client delegation is supported")
                }
                if (type != "client" || delegation.keys != setOf("type")) {
                    invalid("providerOptions.openai.delegation must be null or {type: \"client\"}")
                }
            }
            if (options.containsKey("client")) {
                // Data-channel permissions configure a WebRTC session, which this contract cannot open.
                throw UnsupportedFunctionalityError("OpenAI Live client permissions outside WebRTC startup")
            }
            val input = options["input"]?.let { value ->
                val items = value as? JsonArray ?: invalid("providerOptions.openai.input must be an array")
                if (items.size > MAX_INPUT_ITEMS) invalid("providerOptions.openai.input holds at most $MAX_INPUT_ITEMS items")
                items.forEach { it.validateStartupMessage() }
                items
            }
            val store = options["store"]?.let { value ->
                (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                    ?: invalid("providerOptions.openai.store must be a boolean")
            }
            val voice = options["voice"]?.let { value ->
                val voice = value as? JsonObject ?: invalid("providerOptions.openai.voice must be {id}")
                if (voice.keys != setOf("id") || voice.stringOrNull("id").isNullOrEmpty()) {
                    invalid("providerOptions.openai.voice must be {id} with a non-empty id")
                }
                voice
            }
            return OpenAILiveStartupOptions(delegation, input, store, voice)
        }

        /** One startup history item: a developer/user `input_text` message, or an assistant text one. */
        @Suppress("ThrowsCount")
        private fun JsonElement.validateStartupMessage() {
            val message = this as? JsonObject ?: invalid("a Live startup input item must be a message object")
            if (message.stringOrNull("type") != "message" || message.keys != setOf("type", "role", "content")) {
                invalid("a Live startup input item must be {type: \"message\", role, content}")
            }
            val part = (message["content"] as? JsonArray)?.singleOrNull() as? JsonObject
                ?: invalid("a Live startup message carries exactly one content part")
            val partType = part.stringOrNull("type")
            val allowedTypes = when (message.stringOrNull("role")) {
                "developer", "user" -> setOf("input_text")
                "assistant" -> setOf("text", "output_text")
                else -> invalid("a Live startup message's role is developer, user or assistant")
            }
            if (partType !in allowedTypes || part.keys != setOf("type", "text") || part.stringOrNull("text") == null) {
                invalid("a Live startup message's content part must be {type: ${allowedTypes.joinToString("|")}, text}")
            }
        }

        private fun invalid(message: String): Nothing =
            throw InvalidArgumentError(message = message, argument = "providerOptions")
    }
}
