package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.AiSdkError
import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.ResponseInfo
import com.sabreware.aide.aisdk.ResponseMetadata
import com.sabreware.aide.aisdk.SpeechTranslationModel
import com.sabreware.aide.aisdk.SpeechTranslationStreamOptions
import com.sabreware.aide.aisdk.SpeechTranslationStreamPart
import com.sabreware.aide.aisdk.SpeechTranslationStreamResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.ProviderSocket
import com.sabreware.aide.aisdk.util.SocketClosed
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.webSocketUrl
import io.ktor.http.encodeURLParameter
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The only input shape the realtime translation endpoint accepts. Anything else is refused up front. */
private const val REALTIME_INPUT_RATE = 24_000

/**
 * The transcription model the session is configured with. Hard-coded by the reference and by this port:
 * the endpoint offers one, and a caller who wants the source transcript — the contract's second text
 * channel — gets it from nowhere else.
 */
private const val REALTIME_TRANSCRIPTION_MODEL = "gpt-realtime-whisper"

/** `wss://{base}/realtime/translations?model=…` — the model rides the query, not the session frame. */
internal fun openAIRealtimeTranslationUrl(baseUrl: String, modelId: String): String =
    webSocketUrl(baseUrl.trimEnd('/')) + "/realtime/translations?model=" + modelId.encodeURLParameter()

/**
 * The `session.update` frame, sent first.
 *
 * `noise_reduction` is an EXPLICIT null, matching the reference byte for byte: the endpoint's default is
 * a reduction profile, and null is how a session says "none". Key order is the reference's too, because
 * the frame is also what [SpeechTranslationStreamResult.request] records and a recorded body is only
 * comparable if it is spelled the same way.
 */
internal fun openAIRealtimeTranslationSession(targetLanguage: String): JsonObject = buildJsonObject {
    put("type", "session.update")
    putJsonObject("session") {
        putJsonObject("audio") {
            putJsonObject("input") {
                putJsonObject("transcription") { put("model", REALTIME_TRANSCRIPTION_MODEL) }
                put("noise_reduction", JsonNull)
            }
            putJsonObject("output") { put("language", targetLanguage) }
        }
    }
}

/** One captured chunk, base64 in a text frame — the realtime wire carries no binary frames inbound. */
@OptIn(ExperimentalEncodingApi::class)
internal fun openAIRealtimeAudioFrame(audio: ByteArray): JsonObject = buildJsonObject {
    put("type", "session.input_audio_buffer.append")
    put("audio", Base64.encode(audio))
}

/**
 * End of input. The server answers with whatever translation is still in flight and then
 * `session.closed`, which is the finish signal; closing the socket instead abandons the tail.
 */
internal val OPENAI_REALTIME_SESSION_CLOSE: JsonObject = buildJsonObject { put("type", "session.close") }

/**
 * Live audio is naked PCM with nothing to sniff, so an unsupported shape has to fail here. Sending it
 * anyway yields a handshake rejection that names neither the rate nor the format.
 */
internal fun validateOpenAIRealtimeInputFormat(format: AudioFormat) {
    if (format.type != "audio/pcm" || (format.rate != null && format.rate != REALTIME_INPUT_RATE)) {
        throw InvalidArgumentError(
            message = "The OpenAI Realtime translation API only supports 24kHz 16-bit PCM input audio.",
            argument = "inputAudioFormat",
        )
    }
}

/**
 * What the caller asked for that the endpoint cannot honour — warned about rather than sent, because
 * the session frame has no field for either and a request carrying one would be rejected as unknown.
 */
internal fun openAIRealtimeTranslationWarnings(
    options: SpeechTranslationStreamOptions,
): List<Warning> = buildList {
    if (options.sourceLanguage != null) {
        add(
            Warning.Unsupported(
                feature = "sourceLanguage",
                details = "The OpenAI Realtime translation API auto-detects the source language and " +
                    "does not accept a source language.",
            ),
        )
    }
    if (options.outputAudioFormat != null) {
        add(
            Warning.Unsupported(
                feature = "outputAudioFormat",
                details = "The OpenAI Realtime translation API always outputs 24kHz 16-bit PCM audio " +
                    "and does not accept an output audio format.",
            ),
        )
    }
}

/** How the socket authenticates — see [openAIRealtimeConnection]. */
internal data class OpenAIRealtimeConnection(
    val protocols: List<String>,
    val headers: Map<String, String>,
)

private val BEARER_TOKEN = Regex("^bearer\\s+(.+)$", RegexOption.IGNORE_CASE)

/**
 * The bearer token moves from the `Authorization` header to the `openai-insecure-api-key` subprotocol.
 *
 * The realtime endpoint reads its credential from the subprotocol list because a browser's WebSocket
 * cannot set a request header, and it REJECTS a handshake that carries both channels — so the header is
 * stripped rather than duplicated. Every other header (organization, project, a gateway's own) rides
 * through untouched. A configuration with no bearer token at all offers only `realtime`, which is what
 * a proxy that authenticates by other means expects.
 */
internal fun openAIRealtimeConnection(headers: Map<String, String>): OpenAIRealtimeConnection {
    val authorization = headers.entries.lastOrNull { it.key.equals("authorization", ignoreCase = true) }?.value
    val token = authorization?.let { BEARER_TOKEN.find(it)?.groupValues?.get(1) }
        ?: return OpenAIRealtimeConnection(protocols = listOf("realtime"), headers = headers)
    return OpenAIRealtimeConnection(
        protocols = listOf("realtime", "openai-insecure-api-key.$token"),
        headers = headers.filterKeys { !it.equals("authorization", ignoreCase = true) },
    )
}

/**
 * The frame sequence, as parts.
 *
 * Split from the socket for the reason the other live providers are: a socket is the one thing a test
 * cannot open, and every branch below is reachable when the mapper is driven by a recorded frame list.
 *
 * Simpler than the Gemini mapper on purpose. This session has a real terminator — `session.closed`
 * answers `session.close` — so there is no trailing-silence heuristic and no grace period; the finish
 * is an event, not an inference. Neither text channel is segmented by the server, so the deltas
 * accumulate into ONE final per channel at the end, with no ids: there is nothing to correlate them by.
 */
internal class OpenAIRealtimeTranslationMapper(
    private val warnings: List<Warning>,
    private val includeRawChunks: Boolean,
) {

    var finished: Boolean = false
        private set

    private var started = false
    private val sourceText = StringBuilder()
    private val outputText = StringBuilder()

    /** The socket opened. Idempotent, and also implied by the first frame — see [on]. */
    fun start(): List<SpeechTranslationStreamPart> {
        if (started) return emptyList()
        started = true
        return listOf(SpeechTranslationStreamPart.StreamStart(warnings))
    }

    /**
     * One server frame.
     *
     * A frame that is not JSON is skipped rather than fatal, as the reference does: a keep-alive or a
     * truncated frame must not discard a translation that is otherwise fine. An `error` frame is a
     * PART, not a throw — the reference keeps streaming past it, and a session that reported a bad
     * language code and then translated anyway has still translated.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun on(text: String): List<SpeechTranslationStreamPart> {
        // Late frames — the server talking past `session.closed` — go nowhere: the caller has already
        // read its final texts, and a part after them would be a translation nobody is reading.
        if (finished) return emptyList()
        val raw = runCatching { ProviderJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return emptyList()

        val parts = mutableListOf<SpeechTranslationStreamPart>()
        parts += start()
        if (includeRawChunks) parts += SpeechTranslationStreamPart.Raw(raw)

        when (raw.optString("type")) {
            "session.output_audio.delta" -> {
                // An empty delta carries no audio; the reference skips it and so does this. A payload
                // that will not decode is dropped the same way — a caller expecting bytes cannot be
                // handed a string that was not base64.
                val encoded = raw.optString("delta")
                if (!encoded.isNullOrEmpty()) {
                    runCatching { Base64.decode(encoded) }.getOrNull()?.let {
                        parts += SpeechTranslationStreamPart.Audio(audio = it)
                    }
                }
            }

            "session.output_transcript.delta" -> {
                val delta = raw.optString("delta").orEmpty()
                outputText.append(delta)
                parts += SpeechTranslationStreamPart.OutputTextDelta(delta = delta)
            }

            "session.input_transcript.delta" -> {
                val delta = raw.optString("delta").orEmpty()
                sourceText.append(delta)
                parts += SpeechTranslationStreamPart.SourceTranscriptDelta(delta = delta)
            }

            "session.closed" -> parts += finish()

            "error" -> parts += SpeechTranslationStreamPart.Error(
                AiSdkError(
                    errorName = "AI_APICallError",
                    message = raw.optObject("error")?.optString("message") ?: "OpenAI realtime error",
                ),
            )

            else -> Unit
        }
        return parts
    }

    /**
     * The socket closed. After `session.closed` that is the expected goodbye; before it, audio was
     * translated and then thrown away, which has to be an error rather than a short translation the
     * caller cannot tell from a complete one — and the close code and reason are the only diagnostics
     * the server left.
     */
    fun onClose(closed: SocketClosed?): List<SpeechTranslationStreamPart> {
        if (finished) return emptyList()
        val reason = closed?.reason?.takeIf { it.isNotEmpty() }?.let { ", reason: $it" }.orEmpty()
        throw AiSdkError(
            errorName = "AI_APICallError",
            message = "OpenAI realtime translation WebSocket closed unexpectedly before finishing " +
                "(code ${closed?.code ?: "unknown"}$reason).",
        )
    }

    private fun finish(): List<SpeechTranslationStreamPart> {
        finished = true
        val parts = mutableListOf<SpeechTranslationStreamPart>()
        if (sourceText.isNotEmpty()) {
            parts += SpeechTranslationStreamPart.SourceTranscriptFinal(text = sourceText.toString())
        }
        if (outputText.isNotEmpty()) {
            parts += SpeechTranslationStreamPart.OutputTextFinal(text = outputText.toString())
        }
        // Usage stays null: the translation session reports none, and a guessed number is
        // indistinguishable from a measured one at the call site.
        parts += SpeechTranslationStreamPart.Finish(
            sourceText = sourceText.toString(),
            outputText = outputText.toString(),
        )
        return parts
    }
}

/** One thing the session saw, in the order it saw them. */
private sealed interface OpenAIRealtimeEvent {

    /** The handshake completed; the session frame is about to go out. */
    data object Opened : OpenAIRealtimeEvent

    data class Frame(val text: String) : OpenAIRealtimeEvent
}

/**
 * The session, as a flow.
 *
 * The open signal and every frame are funnelled into ONE channel so the mapper sees them in order; the
 * audio pump runs beside the read loop because the wire is duplex, and a pump that waited for the
 * microphone to stop before reading would miss every frame sent in the meantime.
 *
 * `session.close` goes out when the caller's audio ends. The server then finishes its translation and
 * answers `session.closed`, at which point the read loop stops and cancels the reader — which closes the
 * socket with a normal closure and stops the pump, so a caller whose audio is a live microphone stops
 * being read the moment the translation is done.
 */
internal fun openAIRealtimeTranslationStream(
    socket: ProviderSocket,
    url: String,
    connection: OpenAIRealtimeConnection,
    audio: Flow<ByteArray>,
    session: JsonObject,
    mapper: OpenAIRealtimeTranslationMapper,
): Flow<SpeechTranslationStreamPart> = flow {
    coroutineScope {
        val events = Channel<OpenAIRealtimeEvent>(Channel.UNLIMITED)
        var closed: SocketClosed? = null

        val reader = launch {
            try {
                socket.textFrames(
                    url = url,
                    headers = connection.headers,
                    protocols = connection.protocols,
                    onClose = { closed = it },
                ) { sender ->
                    events.send(OpenAIRealtimeEvent.Opened)
                    sender.sendJson(session)
                    audio.collect { chunk -> sender.sendJson(openAIRealtimeAudioFrame(chunk)) }
                    sender.sendJson(OPENAI_REALTIME_SESSION_CLOSE)
                }.collect { events.send(OpenAIRealtimeEvent.Frame(it)) }
                events.close()
            } catch (e: CancellationException) {
                events.close(e)
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                // Closing WITH the cause is what makes a failed handshake surface as itself rather than
                // as the "closed before finishing" the read loop would otherwise report for it.
                events.close(e)
                throw e
            }
        }

        var socketClosed = false
        while (true) {
            val received = events.receiveCatching()
            received.exceptionOrNull()?.let { throw it }
            val event = received.getOrNull() ?: run { socketClosed = true; null } ?: break
            val parts = when (event) {
                OpenAIRealtimeEvent.Opened -> mapper.start()
                is OpenAIRealtimeEvent.Frame -> mapper.on(event.text)
            }
            parts.forEach { emit(it) }
            if (mapper.finished) break
        }
        if (socketClosed) mapper.onClose(closed).forEach { emit(it) }
        reader.cancel()
    }
}

/**
 * OpenAI's realtime translation: speech in one language in, speech and both texts out, over the
 * `/realtime/translations` WebSocket.
 *
 * The service auto-detects the source language and always answers in 24 kHz PCM, so `sourceLanguage`
 * and `outputAudioFormat` are warned about rather than sent — a caller who set them would otherwise
 * believe they took effect. The API key travels as a subprotocol, not a header; see
 * [openAIRealtimeConnection] for why that is the only place the endpoint reads it from.
 */
internal class OpenAISpeechTranslationModel(
    override val modelId: String,
    private val socket: ProviderSocket,
    private val baseUrl: String,
    private val headers: Map<String, String>,
    /** Epoch millis, injected so a test can pin the response timestamp. */
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : SpeechTranslationModel {

    override val provider: String = OPENAI_PROVIDER_ID

    override suspend fun doStream(
        options: SpeechTranslationStreamOptions,
    ): SpeechTranslationStreamResult {
        if (options.targetLanguage.isBlank()) {
            throw InvalidArgumentError(
                message = "targetLanguage is required for translation model '$modelId'.",
                argument = "targetLanguage",
            )
        }
        validateOpenAIRealtimeInputFormat(options.inputAudioFormat)

        val session = openAIRealtimeTranslationSession(options.targetLanguage)
        return SpeechTranslationStreamResult(
            stream = openAIRealtimeTranslationStream(
                socket = socket,
                url = openAIRealtimeTranslationUrl(baseUrl, modelId),
                connection = openAIRealtimeConnection(combineHeaders(headers, options.headers)),
                audio = options.audio,
                session = session,
                mapper = OpenAIRealtimeTranslationMapper(
                    warnings = openAIRealtimeTranslationWarnings(options),
                    includeRawChunks = options.includeRawChunks,
                ),
            ),
            request = RequestInfo(body = ProviderJson.encodeToString(JsonObject.serializer(), session)),
            response = ResponseInfo(metadata = ResponseMetadata(timestamp = now(), modelId = modelId)),
        )
    }
}
