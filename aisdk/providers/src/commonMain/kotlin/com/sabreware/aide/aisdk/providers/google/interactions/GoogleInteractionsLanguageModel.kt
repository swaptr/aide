package com.sabreware.aide.aisdk.providers.google.interactions

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.JsonParseError
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.ResponseInfo
import com.sabreware.aide.aisdk.ResponseMetadata
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.google.GOOGLE_DEFAULT_BASE_URL
import com.sabreware.aide.aisdk.providers.google.GoogleErrorStructure
import com.sabreware.aide.aisdk.util.IdGenerator
import com.sabreware.aide.aisdk.util.PollPolicy
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.parseJsonElementOrNull
import io.ktor.client.HttpClient
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Gemini's Interactions API: `POST /interactions`, streaming or not, against a model or an agent.
 *
 * What this surface has that `generateContent` does not, and the reason it exists here at all, is
 * **stateful mode**: the server keeps the conversation, the response carries an `interactionId`, and
 * the next call names it as `previous_interaction_id` and sends only what is new. The id reaches the
 * caller on the result's `providerMetadata.google.interactionId` and on every output part, and the
 * converter reads it back off the replayed parts to drop the turns the server already holds — see
 * `GoogleInteractionsPrompt.kt`. A `thought` or `function_call` step also carries a `signature`, which
 * the API demands back verbatim; it rides the same metadata.
 *
 * An **agent** call ([GoogleInteractionsTarget.Agent]) addresses one of Google's server-side agents
 * instead of a model. The request then says `agent` rather than `model`, takes `agent_config` and an
 * `environment`, and refuses every sampler knob — those are warned about and dropped, not sent. Agents
 * run in the **background**: the POST returns at once with a non-terminal status and the answer is
 * polled from `GET /interactions/{id}` or streamed from `GET /interactions/{id}?stream=true`, which is
 * what [GoogleInteractionsBackground] does. Both paths cancel the run on the server when the caller
 * gives up on it.
 *
 * Every flow here is cold. That is the contract, and for a background run it is also billing: the
 * POST that starts an agent is issued when the stream is COLLECTED, never when it is merely built.
 */
public class GoogleInteractionsLanguageModel(
    target: GoogleInteractionsTarget,
    http: ProviderHttp,
    client: HttpClient,
    baseUrl: String = GOOGLE_DEFAULT_BASE_URL,
    /** Resolved per call, not per model: a Vertex bearer expires in an hour. */
    private val headers: suspend () -> Map<String, String> = { emptyMap() },
    override val provider: String = GOOGLE_INTERACTIONS_PROVIDER_ID,
    private val ids: IdGenerator = IdGenerator(prefix = "gemini_"),
    /** Epoch millis, the polling budget's clock; injected so a test can exhaust it without waiting. */
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val reconnect: GoogleInteractionsReconnectPolicy = GoogleInteractionsReconnectPolicy(),
) : LanguageModel {

    /** The model id, or the agent's name where the call is addressed to an agent. */
    override val modelId: String = when (target) {
        is GoogleInteractionsTarget.Model -> target.modelId
        is GoogleInteractionsTarget.Agent -> target.name
        is GoogleInteractionsTarget.ManagedAgent -> target.name
    }

    /** The agent this model addresses, or null for a plain model call. */
    public val agent: String? = when (target) {
        is GoogleInteractionsTarget.Model -> null
        is GoogleInteractionsTarget.Agent -> target.name
        is GoogleInteractionsTarget.ManagedAgent -> target.name
    }

    private val baseUrl = baseUrl.trimEnd('/')
    private val http = http.withErrorStructure(GoogleErrorStructure)
    private val background = GoogleInteractionsBackground(this.http, client, this.baseUrl)
    private val url get() = "$baseUrl/interactions"

    override suspend fun supportedUrls(): Map<String, List<Regex>> = SUPPORTED_URLS

    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        val built = buildInteractionsRequest(options, modelId, agent)
        val requestHeaders = combineHeaders(headers(), options.headers)
        val posted = http.postJson(url, built.body, requestHeaders)
        var raw = posted.value
        var response = decodeInteractionsResponse(raw)
        var responseHeaders = posted.headers

        // A background run answers before it is done; the answer the caller asked for is the terminal one.
        if ((built.isAgent || built.isBackground) && !isTerminalStatus(response.status)) {
            val polled = background.pollUntilTerminal(
                interactionId = response.id,
                headers = requestHeaders,
                policy = pollPolicy(built.pollingTimeoutMs),
                elapsedMillis = now,
            )
            raw = polled.raw
            response = polled.response
            responseHeaders = polled.headers
        }

        val interactionId = response.id?.takeIf { it.isNotEmpty() }
        val parsed = parseGoogleInteractionsOutputs(response.steps, ids, interactionId)
        return GenerateResult(
            content = parsed.content,
            finishReason = interactionsFinishReason(response.status, parsed.hasFunctionCall),
            usage = response.usage.toUsage(),
            warnings = built.warnings,
            providerMetadata = interactionsResponseMetadata(
                interactionId = interactionId,
                // The body is where this surface reports the tier; the header is the classic surface's
                // channel, read as a fallback in case the API grows it.
                serviceTier = response.serviceTier ?: responseHeaders[SERVICE_TIER_HEADER],
                outputTokensByModality = response.usage.outputTokensByModality(),
            ),
            request = RequestInfo(built.bodyText),
            response = ResponseInfo(
                metadata = ResponseMetadata(
                    id = interactionId,
                    timestamp = parseCreatedMillis(response.created),
                    modelId = response.model,
                ),
                headers = responseHeaders,
                body = raw.toString(),
            ),
        )
    }

    override suspend fun doStream(options: CallOptions): StreamResult {
        val built = buildInteractionsRequest(options, modelId, agent)
        // `background: true` and `stream: true` are mutually exclusive on the POST: a background run is
        // started plain and streamed from its own GET.
        if (built.isBackground) {
            return StreamResult(stream = backgroundStream(built, options), request = RequestInfo(built.bodyText))
        }
        val body = JsonObject(built.body + ("stream" to JsonPrimitive(true)))
        return StreamResult(
            stream = postStream(body, built, options),
            request = RequestInfo(ProviderJson.encodeToString(JsonElement.serializer(), body)),
        )
    }

    private fun postStream(body: JsonObject, built: BuiltInteractionsRequest, options: CallOptions): Flow<StreamPart> =
        flow {
            emit(streamStart(built.warnings))
            val state = GoogleInteractionsStreamState(ids, url)
            val events = http.postSse(url, body, combineHeaders(headers(), options.headers)) { opened ->
                state.headerServiceTier = opened.headers[SERVICE_TIER_HEADER]
            }
            emitParts(events.map { InteractionsFrame(it.data, parseJsonElementOrNull(it.data)) }, state, options.includeRawChunks)
            state.finish(this)
        }

    /**
     * POST plain, then stream the run from its GET — or replay it, when the POST was already terminal.
     *
     * A run that finished inside the POST (a cached answer, an immediate failure) has nothing left to
     * stream, so its steps are replayed as parts; everything else is served live by the reconnecting
     * event stream, so text and thinking arrive as they happen rather than all at once at the end.
     */
    private fun backgroundStream(built: BuiltInteractionsRequest, options: CallOptions): Flow<StreamPart> = flow {
        emit(streamStart(built.warnings))
        val requestHeaders = combineHeaders(headers(), options.headers)
        val posted = http.postJson(url, built.body, requestHeaders)
        val response = decodeInteractionsResponse(posted.value)
        val headerServiceTier = posted.headers[SERVICE_TIER_HEADER]

        if (isTerminalStatus(response.status)) {
            emitSynthesizedInteraction(response, posted.value, ids, options.includeRawChunks, headerServiceTier)
            return@flow
        }
        val interactionId = response.id?.takeIf { it.isNotEmpty() } ?: throw InvalidResponseDataError(
            message = "google.interactions: background POST response did not include an interaction id; " +
                "cannot stream the result.",
            data = posted.value,
        )
        val state = GoogleInteractionsStreamState(ids, url).also { it.headerServiceTier = headerServiceTier }
        emitParts(background.streamEvents(interactionId, requestHeaders, reconnect), state, options.includeRawChunks)
        state.finish(this)
    }

    /**
     * One SSE payload at a time through the transform.
     *
     * A frame that is not JSON, or JSON that is not an event, is reported as an error part and fails
     * the finish reason rather than being skipped: a stream that silently drops frames ends looking
     * short but fine, which is the failure the classic surface's error frame taught this port about.
     */
    private suspend fun FlowCollector<StreamPart>.emitParts(
        events: Flow<InteractionsFrame>,
        state: GoogleInteractionsStreamState,
        includeRawChunks: Boolean,
    ) {
        events.collect { frame ->
            val raw = frame.json
            if (raw == null) {
                state.markFailed()
                emit(StreamPart.Error(JsonParseError(frame.data)))
                return@collect
            }
            if (includeRawChunks) emit(StreamPart.Raw(raw))
            val event = runCatching { ProviderJson.decodeFromJsonElement(InteractionsEvent.serializer(), raw) }
                .getOrElse { failure ->
                    state.markFailed()
                    emit(
                        StreamPart.Error(
                            InvalidResponseDataError(
                                message = "google.interactions: stream event did not match any known shape",
                                data = raw,
                                cause = failure,
                            ),
                        ),
                    )
                    return@collect
                }
            state.handle(this, event, raw)
        }
    }

    /**
     * Polling for a background run: a second, doubling to ten, for thirty minutes unless the caller says
     * otherwise. Deep research takes tens of minutes server-side, so the budget errs long rather than
     * truncate a real run.
     */
    private fun pollPolicy(timeoutMillis: Long?): PollPolicy = PollPolicy(
        initialDelayMillis = POLL_INITIAL_DELAY_MS,
        maxDelayMillis = POLL_MAX_DELAY_MS,
        backoffFactor = POLL_BACKOFF,
        timeoutMillis = timeoutMillis ?: POLL_TIMEOUT_MS,
    )

    public companion object {
        private const val SERVICE_TIER_HEADER = "x-gemini-service-tier"
        private const val POLL_INITIAL_DELAY_MS = 1_000L
        private const val POLL_MAX_DELAY_MS = 10_000L
        private const val POLL_BACKOFF = 2.0
        private const val POLL_TIMEOUT_MS = 30L * 60 * 1_000

        private val HTTP_URL = Regex("^https?://.+")

        /** What the service fetches for itself; YouTube and Cloud Storage for video, any http(s) for the rest. */
        private val SUPPORTED_URLS: Map<String, List<Regex>> = mapOf(
            "image/*" to listOf(HTTP_URL),
            "application/pdf" to listOf(HTTP_URL),
            "audio/*" to listOf(HTTP_URL),
            "video/*" to listOf(
                Regex("^https?://(www\\.)?youtube\\.com/watch\\?v=.+"),
                Regex("^https?://youtu\\.be/.+"),
                Regex("^gs://.+"),
            ),
        )
    }
}

/** The encoded request, its warnings, and the three facts the call paths branch on. */
internal data class BuiltInteractionsRequest(
    val body: JsonObject,
    val warnings: List<Warning>,
    val isAgent: Boolean,
    val isBackground: Boolean,
    val pollingTimeoutMs: Long?,
) {
    val bodyText: String get() = ProviderJson.encodeToString(JsonElement.serializer(), body)
}
