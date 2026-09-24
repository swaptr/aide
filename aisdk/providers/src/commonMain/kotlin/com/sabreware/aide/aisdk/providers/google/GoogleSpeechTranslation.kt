package com.sabreware.aide.aisdk.providers.google

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
import com.sabreware.aide.aisdk.SpeechTranslationUsage
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.ProviderSocket
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.webSocketUrl
import io.ktor.http.encodeURLParameter
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The bidi service the Live API exposes translation on. It carries its own `v1beta` inside the service
 * name, which is why the base URL's version segment has to come off — see [googleLiveTranslationUrl].
 */
private const val LIVE_BIDI_PATH =
    "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

/** The only input shape the Live translation model accepts. Anything else is refused at the handshake. */
private const val LIVE_INPUT_RATE = 16_000

/** The Live API's output is always 24 kHz 16-bit PCM; the rate is what turns silence into a duration. */
private const val LIVE_OUTPUT_RATE = 24_000

/**
 * Below this amplitude a 16-bit sample is silence. Not zero: the vendor's silence is synthesized rather
 * than muted, and a strict test for zero would find speech in every trailing frame and never finish.
 */
private const val PCM16_SILENCE_AMPLITUDE = 128

/**
 * How much trailing output silence ends the session.
 *
 * Live Translation is a continuous pipeline, not a turn-based model: after `audioStreamEnd` it keeps
 * emitting PCM silence forever and never sends `turnComplete`. Without this the stream would hang after
 * the last word, waiting for a terminator the service does not send.
 */
private const val DEFAULT_FINISH_GRACE_MS = 1_000L

/**
 * The Live API socket URL.
 *
 * The key rides the query string. [GoogleProvider] argues the opposite for the REST endpoints — a URL is
 * logged in more places than a header is — and this is the documented exception: the Live handshake
 * authenticates by `?key=`, so there is no header to put it in. It is therefore also the reason
 * [GoogleSpeechTranslationModel] reports the setup body rather than the URL as its [RequestInfo]: a
 * request record that quoted the URL would carry the credential into every log that reads one.
 *
 * The base URL's trailing `/v1beta` or `/v1alpha` comes off because [LIVE_BIDI_PATH] names its own
 * version. Leaving it on produces `/v1beta/ws/…v1beta…`, which 404s at the upgrade — a socket that never
 * opens rather than a legible error.
 */
internal fun googleLiveTranslationUrl(baseUrl: String, apiKey: String): String {
    val trimmed = baseUrl.trimEnd('/')
    val root = listOf("/v1beta", "/v1alpha").firstOrNull { trimmed.endsWith(it) }
        ?.let { trimmed.removeSuffix(it) }
        ?: trimmed
    return webSocketUrl(root.trimEnd('/')) + "/ws/" + LIVE_BIDI_PATH +
        "?key=" + apiKey.encodeURLParameter()
}

/**
 * The session configuration, sent as the first frame.
 *
 * Both transcription blocks are requested unconditionally, because they are the two text channels the
 * contract is built around: `inputAudioTranscription` is what was said and `outputAudioTranscription` is
 * what it means. A session that asks for neither streams audio a subtitle UI cannot show.
 */
internal fun googleLiveTranslationSetup(
    modelId: String,
    targetLanguage: String,
    vendorOptions: JsonObject?,
): JsonObject = buildJsonObject {
    // A caller may name a model by its full resource path; only a bare id needs the prefix.
    put("model", if (modelId.contains('/')) modelId else "models/$modelId")
    putJsonObject("generationConfig") {
        putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
        putJsonObject("translationConfig") {
            put("targetLanguageCode", targetLanguage)
            vendorOptions?.optBoolean("echoTargetLanguage")?.let { put("echoTargetLanguage", it) }
        }
    }
    put("inputAudioTranscription", JsonObject(emptyMap()))
    put("outputAudioTranscription", JsonObject(emptyMap()))
}

/** One captured chunk. The rate is repeated per frame; the Live API has no session-level input rate. */
@OptIn(ExperimentalEncodingApi::class)
internal fun googleLiveAudioChunk(audio: ByteArray, rate: Int): JsonObject = buildJsonObject {
    putJsonObject("realtimeInput") {
        putJsonObject("audio") {
            put("data", Base64.encode(audio))
            put("mimeType", "audio/pcm;rate=$rate")
        }
    }
}

/**
 * End of input. Closing the socket instead abandons whatever the pipeline had not yet translated, and
 * the vendor keeps the session open until it is told the microphone stopped.
 */
internal val GOOGLE_LIVE_AUDIO_STREAM_END: JsonObject = buildJsonObject {
    putJsonObject("realtimeInput") { put("audioStreamEnd", true) }
}

/**
 * Live audio is naked PCM with nothing to sniff, so an unsupported shape has to fail here. Sending it
 * anyway yields a handshake rejection that names neither the rate nor the format.
 */
internal fun validateGoogleLiveInputFormat(format: AudioFormat) {
    if (format.type.lowercase() != "audio/pcm" ||
        (format.rate != null && format.rate != LIVE_INPUT_RATE)
    ) {
        throw InvalidArgumentError(
            message = "The Gemini Live translation API only supports 16kHz 16-bit PCM input audio.",
            argument = "inputAudioFormat",
        )
    }
}

/**
 * What the caller asked for that the Live API cannot honour.
 *
 * Warned about rather than sent: the service auto-detects the source language and always answers in
 * 24 kHz PCM, so a request carrying either field would be accepted and ignored, and a caller who set one
 * would believe it took effect.
 */
internal fun googleLiveTranslationWarnings(
    options: SpeechTranslationStreamOptions,
): List<Warning> = buildList {
    if (options.sourceLanguage != null) {
        add(
            Warning.Unsupported(
                feature = "sourceLanguage",
                details = "The Gemini Live translation API auto-detects the source language and does " +
                    "not accept a source language.",
            ),
        )
    }
    if (options.outputAudioFormat != null) {
        add(
            Warning.Unsupported(
                feature = "outputAudioFormat",
                details = "The Gemini Live API always outputs 24kHz 16-bit PCM audio and does not " +
                    "accept an output audio format.",
            ),
        )
    }
}

/**
 * Whether [bytes] is entirely silence, as milliseconds of it, or null if any sample is speech.
 *
 * Hand-decoded little-endian rather than a buffer type, because `commonMain` may not import `java.nio`
 * and a translation model that only compiles on the JVM is not one this module can offer.
 */
internal fun pcm16SilenceDurationMs(bytes: ByteArray): Double? {
    if (bytes.size < 2) return null
    val sampleCount = bytes.size / 2
    for (index in 0 until sampleCount) {
        val low = bytes[index * 2].toInt() and 0xFF
        // The high byte keeps its sign, so the shift reconstitutes a signed 16-bit sample.
        val sample = (bytes[index * 2 + 1].toInt() shl 8) or low
        if (abs(sample) > PCM16_SILENCE_AMPLITUDE) return null
    }
    return sampleCount.toDouble() / LIVE_OUTPUT_RATE * 1000
}

/** What the session is waiting for, which is what makes a socket close either a finish or a failure. */
internal enum class GoogleTranslationState {
    /** Still translating; a close here lost audio. */
    Running,

    /** The finish condition was met and the grace period is running; a close here is a clean end. */
    Finalizing,

    Finished,
}

/**
 * One frame, or one boundary of the audio the caller pushed, in the order the session saw them.
 *
 * The end of the caller's audio is an event on this stream rather than a flag the sending coroutine
 * sets, because the two run concurrently: every "did this turn complete after our input ended?" decision
 * below turns on reading it at the right moment, and a shared boolean makes each of them a race.
 */
internal sealed interface GoogleTranslationEvent {

    data class Frame(val text: String) : GoogleTranslationEvent

    /** The caller's audio flow ended and `audioStreamEnd` has been sent. */
    data object AudioEnded : GoogleTranslationEvent

    /** The grace period after the finish condition elapsed with no further turn activity. */
    data object GraceElapsed : GoogleTranslationEvent
}

/**
 * The frame sequence, as parts.
 *
 * Split from the socket because this is where both text channels and the partial/final distinction live,
 * and a socket is the one thing a test cannot open: driven by a recorded frame list, every branch below
 * is reachable.
 */
@Suppress("TooManyFunctions")
internal class GoogleLiveTranslationMapper(
    private val warnings: List<Warning>,
    private val includeRawChunks: Boolean,
    private val finishGraceMs: Long,
) {

    var state: GoogleTranslationState = GoogleTranslationState.Running
        private set

    /** Completed once `setupComplete` arrives, which is when the Live API will accept audio. */
    var setupComplete: Boolean = false
        private set

    // Live messages carry no ids of their own, so a turn counter mints them. Both text channels of one
    // turn share an id: a subtitle UI pairs "what was said" with "what it means" by matching them.
    private var turnCounter = 0

    private var sourceText = StringBuilder()
    private var sourceTurnBuffer = StringBuilder()
    private var outputText = StringBuilder()
    private var outputTurnBuffer = StringBuilder()

    private var audioEnded = false
    private var openTurn = false
    private var sawTurnComplete = false
    private var trailingSilenceMs = 0.0

    private var inputAudioTokens: Long? = null
    private var outputAudioTokens: Long? = null

    fun on(event: GoogleTranslationEvent): List<SpeechTranslationStreamPart> = when (event) {
        is GoogleTranslationEvent.Frame -> onFrame(event.text)
        GoogleTranslationEvent.AudioEnded -> {
            audioEnded = true
            // A turn that completed while the microphone was still open already satisfies the finish
            // condition; without this the stream waits for a second `turnComplete` that never comes.
            if (sawTurnComplete && !openTurn) beginFinalizing()
            emptyList()
        }
        GoogleTranslationEvent.GraceElapsed -> finish()
    }

    /**
     * The socket closed. That is a clean end only once the finish condition was met; anywhere else
     * audio was translated and then thrown away, which has to be an error rather than a short
     * transcript the caller cannot tell from a complete one.
     */
    fun onClose(): List<SpeechTranslationStreamPart> = when (state) {
        GoogleTranslationState.Finished -> emptyList()
        GoogleTranslationState.Finalizing -> finish()
        GoogleTranslationState.Running -> throw AiSdkError(
            errorName = "AI_APICallError",
            message = "Google Live translation WebSocket closed unexpectedly before finishing.",
        )
    }

    @Suppress("ReturnCount")
    private fun onFrame(text: String): List<SpeechTranslationStreamPart> {
        // Live Translation keeps talking after the finish condition is met — it has no idea the session
        // is over. A part emitted past the finish would arrive after the caller already read its final
        // texts, so a late frame is dropped rather than appended to a translation nobody is reading.
        if (state == GoogleTranslationState.Finished) return emptyList()

        // A frame that is not JSON is skipped rather than fatal: the reference does the same, and a
        // keep-alive or a truncated frame must not discard a translation that is otherwise fine.
        val raw = runCatching { ProviderJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return emptyList()

        val parts = mutableListOf<SpeechTranslationStreamPart>()
        if (includeRawChunks) parts += SpeechTranslationStreamPart.Raw(raw)

        if (raw["setupComplete"] != null && !setupComplete) {
            setupComplete = true
            parts += SpeechTranslationStreamPart.StreamStart(warnings)
        }
        raw.optObject("usageMetadata")?.let { accumulateUsage(it) }

        raw.optObject("error")?.let { error ->
            throw AiSdkError(
                errorName = "AI_APICallError",
                message = error.optString("message") ?: "Google Live API error",
            )
        }

        val serverContent = raw.optObject("serverContent")
        // Early sessions put the input transcription at the top level; both spellings are live.
        val sourceDelta = serverContent?.optObject("inputTranscription")?.optString("text")
            ?: raw.optObject("inputTranscription")?.optString("text")
        if (!sourceDelta.isNullOrEmpty()) {
            onTurnActivity()
            sourceTurnBuffer.append(sourceDelta)
            parts += SpeechTranslationStreamPart.SourceTranscriptDelta(delta = sourceDelta, id = itemId())
        }
        if (serverContent == null) return parts

        val finished = appendModelTurnAudio(serverContent, parts)
        if (finished) return parts

        val outputDelta = serverContent.optObject("outputTranscription")?.optString("text")
        if (!outputDelta.isNullOrEmpty()) {
            onTurnActivity()
            outputTurnBuffer.append(outputDelta)
            parts += SpeechTranslationStreamPart.OutputTextDelta(delta = outputDelta, id = itemId())
        }

        if (serverContent.optBoolean("turnComplete") == true) {
            parts += completeTurn()
            openTurn = false
            sawTurnComplete = true
            if (audioEnded) beginFinalizing()
        }
        return parts
    }

    /**
     * Emits the turn's audio, and reports whether the trailing silence just ended the session.
     *
     * The silence tally only runs after the caller's audio ended: silence mid-conversation is a pause
     * between sentences, and finishing on it would cut the translation off at the speaker's first
     * breath.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun appendModelTurnAudio(
        serverContent: JsonObject,
        parts: MutableList<SpeechTranslationStreamPart>,
    ): Boolean {
        val modelParts = serverContent.optObject("modelTurn")?.optArray("parts") ?: return false
        for (element in modelParts) {
            val encoded = element.jsonObject.optObject("inlineData")?.optString("data")
            if (encoded.isNullOrEmpty()) continue
            // A payload that will not decode cannot be handed to a caller expecting bytes, so it is
            // dropped — but it still counts as activity, because treating unreadable audio as silence
            // would end a session that is still translating.
            val decoded = runCatching { Base64.decode(encoded) }.getOrNull()
            if (decoded != null) {
                parts += SpeechTranslationStreamPart.Audio(audio = decoded, id = itemId())
            }
            val silenceMs = decoded?.let { pcm16SilenceDurationMs(it) }
            if (audioEnded && silenceMs != null) {
                trailingSilenceMs += silenceMs
                if (trailingSilenceMs >= finishGraceMs) {
                    parts += finish()
                    return true
                }
            } else {
                onTurnActivity()
            }
        }
        return false
    }

    /**
     * Live Translation bills in periodic usage deltas, so the counts accumulate across messages rather
     * than replacing one another. Only the audio modalities are aggregated: the TEXT prompt detail is
     * the model's internal translation context, and the public input is audio-only, so adding it in
     * would bill the caller for tokens they never sent.
     */
    private fun accumulateUsage(usageMetadata: JsonObject) {
        inputAudioTokens = addAudioTokens(inputAudioTokens, usageMetadata.optArray("promptTokensDetails"))
        outputAudioTokens =
            addAudioTokens(outputAudioTokens, usageMetadata.optArray("responseTokensDetails"))
    }

    private fun addAudioTokens(current: Long?, details: JsonArray?): Long? {
        var total = current
        details?.forEach { entry ->
            val detail = entry.jsonObject
            val count = detail.optInt("tokenCount")
            if (detail.optString("modality") == "AUDIO" && count != null) {
                total = (total ?: 0L) + count
            }
        }
        return total
    }

    private fun onTurnActivity() {
        openTurn = true
        trailingSilenceMs = 0.0
        // Activity after the finish condition means the pipeline was not done after all; the grace
        // period restarts from the next quiet moment rather than truncating this turn.
        if (state == GoogleTranslationState.Finalizing) state = GoogleTranslationState.Running
    }

    private fun beginFinalizing() {
        if (state == GoogleTranslationState.Running) state = GoogleTranslationState.Finalizing
    }

    private fun completeTurn(): List<SpeechTranslationStreamPart> {
        val parts = mutableListOf<SpeechTranslationStreamPart>()
        if (sourceTurnBuffer.isNotEmpty()) {
            parts += SpeechTranslationStreamPart.SourceTranscriptFinal(
                text = sourceTurnBuffer.toString(),
                id = itemId(),
            )
            sourceText.append(sourceTurnBuffer)
            sourceTurnBuffer = StringBuilder()
        }
        if (outputTurnBuffer.isNotEmpty()) {
            parts += SpeechTranslationStreamPart.OutputTextFinal(
                text = outputTurnBuffer.toString(),
                id = itemId(),
            )
            outputText.append(outputTurnBuffer)
            outputTurnBuffer = StringBuilder()
        }
        turnCounter++
        return parts
    }

    private fun finish(): List<SpeechTranslationStreamPart> {
        if (state == GoogleTranslationState.Finished) return emptyList()
        val parts = mutableListOf<SpeechTranslationStreamPart>()
        // A session that ends mid-turn still said something; dropping the buffer would lose the last
        // sentence of every translation that was not terminated by a `turnComplete`.
        if (sourceTurnBuffer.isNotEmpty() || outputTurnBuffer.isNotEmpty()) parts += completeTurn()
        state = GoogleTranslationState.Finished
        parts += SpeechTranslationStreamPart.Finish(
            sourceText = sourceText.toString(),
            outputText = outputText.toString(),
            usage = usage(),
        )
        return parts
    }

    /**
     * Only what Google actually reports. The audio-second and text-token fields stay null rather than
     * being derived: a guessed number is indistinguishable from a measured one at the call site.
     */
    private fun usage(): SpeechTranslationUsage? =
        if (inputAudioTokens == null && outputAudioTokens == null) {
            null
        } else {
            SpeechTranslationUsage(
                inputAudioTokens = inputAudioTokens,
                outputAudioTokens = outputAudioTokens,
            )
        }

    private fun itemId(): String = "google-item-$turnCounter"
}

/**
 * The session, as a flow.
 *
 * Frames and the end of the caller's audio are funnelled into ONE channel so the mapper sees them in the
 * order they happened.
 *
 * The grace period is measured from the moment the finish condition was met, not from the last frame.
 * Live Translation emits periodic `usageMetadata` messages, so a timeout restarted on every arriving
 * frame would be reset by billing traffic and never fire — the stream would hang after the last word.
 */
@Suppress("LongParameterList")
internal fun googleLiveTranslationStream(
    socket: ProviderSocket,
    url: String,
    headers: Map<String, String>,
    audio: Flow<ByteArray>,
    setup: JsonObject,
    inputRate: Int,
    mapper: GoogleLiveTranslationMapper,
    graceMs: Long,
): Flow<SpeechTranslationStreamPart> = flow {
    coroutineScope {
        val events = Channel<GoogleTranslationEvent>(Channel.UNLIMITED)
        val setupAcknowledged = CompletableDeferred<Unit>()

        val reader = launch {
            try {
                socket.textFrames(url, headers) { sender ->
                    sender.sendJson(buildJsonObject { put("setup", setup) })
                    // Audio pushed before `setupComplete` is discarded by the Live API, so a session
                    // that started sending on open would lose the first words of every recording.
                    setupAcknowledged.await()
                    audio.collect { chunk -> sender.sendJson(googleLiveAudioChunk(chunk, inputRate)) }
                    sender.sendJson(GOOGLE_LIVE_AUDIO_STREAM_END)
                    events.send(GoogleTranslationEvent.AudioEnded)
                }.collect { events.send(GoogleTranslationEvent.Frame(it)) }
            } finally {
                events.close()
            }
        }

        var socketClosed = false
        var finalizingSince: TimeMark? = null
        while (true) {
            val event = if (mapper.state == GoogleTranslationState.Finalizing) {
                val since = finalizingSince ?: TimeSource.Monotonic.markNow().also { finalizingSince = it }
                val remaining = graceMs - since.elapsedNow().inWholeMilliseconds
                if (remaining <= 0) {
                    GoogleTranslationEvent.GraceElapsed
                } else {
                    withTimeoutOrNull(remaining) { events.receiveCatching().getOrNull() }
                        // Either the grace ran out or the server closed while the finish was pending.
                        // Both mean no further turn follows, and both finish rather than fail.
                        ?: GoogleTranslationEvent.GraceElapsed
                }
            } else {
                finalizingSince = null
                events.receiveCatching().getOrNull() ?: run { socketClosed = true; null }
            } ?: break

            mapper.on(event).forEach { emit(it) }
            if (mapper.setupComplete) setupAcknowledged.complete(Unit)
            if (mapper.state == GoogleTranslationState.Finished) break
            if (mapper.state != GoogleTranslationState.Finalizing) finalizingSince = null
        }
        if (socketClosed) mapper.onClose().forEach { emit(it) }
        reader.cancel()
    }
}

/**
 * Gemini Live translation: speech in one language in, speech and both texts out.
 *
 * The service auto-detects the source language and always answers in 24 kHz PCM, so `sourceLanguage` and
 * `outputAudioFormat` are warned about rather than sent — a caller who set them would otherwise believe
 * they took effect.
 */
public class GoogleSpeechTranslationModel internal constructor(
    override val modelId: String,
    private val socket: ProviderSocket,
    private val baseUrl: String,
    private val apiKey: String,
    private val extraHeaders: Map<String, String>,
    private val finishGraceMs: Long = DEFAULT_FINISH_GRACE_MS,
) : SpeechTranslationModel {

    override val provider: String = GOOGLE_PROVIDER_ID

    override suspend fun doStream(
        options: SpeechTranslationStreamOptions,
    ): SpeechTranslationStreamResult {
        if (options.targetLanguage.isBlank()) {
            throw InvalidArgumentError(
                message = "targetLanguage is required for translation model '$modelId'.",
                argument = "targetLanguage",
            )
        }
        validateGoogleLiveInputFormat(options.inputAudioFormat)

        val setup = googleLiveTranslationSetup(
            modelId = modelId,
            targetLanguage = options.targetLanguage,
            vendorOptions = options.providerOptions?.forProvider(GOOGLE_PROVIDER_ID),
        )

        return SpeechTranslationStreamResult(
            stream = googleLiveTranslationStream(
                socket = socket,
                url = googleLiveTranslationUrl(baseUrl, apiKey),
                // The key authenticates the URL; a header copy of it would be a second place for the
                // credential to reach a log, and the endpoint ignores it.
                headers = combineHeaders(extraHeaders, options.headers)
                    .filterKeys { !it.equals("x-goog-api-key", ignoreCase = true) },
                audio = options.audio,
                setup = setup,
                inputRate = options.inputAudioFormat.rate ?: LIVE_INPUT_RATE,
                mapper = GoogleLiveTranslationMapper(
                    warnings = googleLiveTranslationWarnings(options),
                    includeRawChunks = options.includeRawChunks,
                    finishGraceMs = finishGraceMs,
                ),
                graceMs = finishGraceMs,
            ),
            // The setup body, not the URL: the URL carries the API key.
            request = RequestInfo(body = ProviderJson.encodeToString(JsonObject.serializer(), setup)),
            response = ResponseInfo(metadata = ResponseMetadata(modelId = modelId)),
        )
    }
}
