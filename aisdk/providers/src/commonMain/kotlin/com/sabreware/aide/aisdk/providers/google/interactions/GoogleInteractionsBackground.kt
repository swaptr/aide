package com.sabreware.aide.aisdk.providers.google.interactions

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.providers.google.GoogleErrorStructure
import com.sabreware.aide.aisdk.providers.google.stringOrNull
import com.sabreware.aide.aisdk.util.JobStatus
import com.sabreware.aide.aisdk.util.PollPolicy
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.RetryPolicy
import com.sabreware.aide.aisdk.util.SseEvent
import com.sabreware.aide.aisdk.util.apiCallError
import com.sabreware.aide.aisdk.util.asApiCallError
import com.sabreware.aide.aisdk.util.asLines
import com.sabreware.aide.aisdk.util.asSseEvents
import com.sabreware.aide.aisdk.util.lowerCasedHeaders
import com.sabreware.aide.aisdk.util.parseJsonElementOrNull
import com.sabreware.aide.aisdk.util.pollUntilDone
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * How a dropped `GET …?stream=true` connection is retried.
 *
 * A long-running agent idles for minutes between events, long enough for a transport to time the body
 * out, so a disconnect is expected rather than exceptional. [maxRetries] counts CONSECUTIVE failures —
 * an attempt that delivered events resets it — and the wait grows linearly with the count.
 */
public data class GoogleInteractionsReconnectPolicy(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MS,
) {
    public companion object {
        public const val DEFAULT_MAX_RETRIES: Int = 3
        public const val DEFAULT_RETRY_DELAY_MS: Long = 500
    }
}

/** A terminal interaction as polled: the typed response, the body it came from, and its headers. */
internal data class PolledInteraction(
    val response: InteractionsResponse,
    val raw: JsonElement,
    val headers: Map<String, String>,
)

/**
 * The three calls a background interaction needs after the POST: poll it, stream it, cancel it.
 *
 * Together because they share one rule — **an abandoned run is cancelled on the server.** A caller
 * that stops collecting, or whose scope is cancelled, is done with the answer; Google is not, and keeps
 * billing until told. So every cancellation path that leaves a run in flight fires a best-effort
 * `POST /interactions/{id}/cancel` on the way out, and never lets that call's own failure mask the
 * cancellation it is cleaning up after.
 */
internal class GoogleInteractionsBackground(
    private val http: ProviderHttp,
    private val client: HttpClient,
    private val baseUrl: String,
) {

    /**
     * `GET /interactions/{id}` until the status is terminal.
     *
     * Throws [InvalidResponseDataError] when the POST gave nothing to poll — a `store: false` call
     * has no id — and the poller's own timeout error when the budget runs out.
     */
    suspend fun pollUntilTerminal(
        interactionId: String?,
        headers: Map<String, String>,
        policy: PollPolicy,
        elapsedMillis: () -> Long,
    ): PolledInteraction {
        if (interactionId.isNullOrEmpty()) {
            throw InvalidResponseDataError(
                "google.interactions: cannot poll a background interaction without an id. " +
                    "The POST response did not include an interaction id.",
            )
        }
        val url = interactionUrl(interactionId)
        try {
            return pollUntilDone(policy = policy, elapsedMillis = elapsedMillis) {
                val result = http.getJson(url, headers)
                val response = decodeInteractionsResponse(result.value)
                if (isTerminalStatus(response.status)) {
                    JobStatus.Succeeded(PolledInteraction(response, result.value, result.headers))
                } else {
                    JobStatus.InProgress()
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { cancel(interactionId, headers) }
            throw e
        }
    }

    /**
     * `GET /interactions/{id}?stream=true`, reconnecting with `last_event_id` when the connection drops.
     *
     * The flow ends cleanly at `interaction.completed` or `error`. A connection that closes before
     * either is reopened from the last event seen; one that closes having delivered NOTHING counts as a
     * failure, or an empty answer would be retried forever. Cancelling the collector cancels the run
     * on the server unless it had already finished.
     */
    fun streamEvents(
        interactionId: String,
        headers: Map<String, String>,
        policy: GoogleInteractionsReconnectPolicy,
    ): Flow<InteractionsFrame> = flow {
        var lastEventId: String? = null
        var complete = false
        var attempt = 0
        try {
            while (!complete) {
                var received = false
                try {
                    getSse(streamUrl(interactionId, lastEventId), headers).collect { sse ->
                        received = true
                        // Parsed ONCE, here, and handed on parsed: an image delta is megabytes of base64,
                        // and the consumer would otherwise build the same tree a second time.
                        val json = parseJsonElementOrNull(sse.data)
                        val parsed = json as? JsonObject
                        parsed?.stringOrNull("event_id")?.takeIf { it.isNotEmpty() }?.let { lastEventId = it }
                        val type = parsed?.stringOrNull("event_type")
                        val terminal = type == "interaction.completed" || type == "error"
                        emit(InteractionsFrame(sse.data, json))
                        if (terminal) complete = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: APICallError) {
                    // Only a transport failure is reconnected — a 401 or a 404 will not improve with a
                    // retry, and a failure the COLLECTOR raised is not ours to catch at all (getSse
                    // rethrows those untouched, so they never arrive here).
                    if (!e.isRetryable) throw e
                    attempt++
                    if (attempt >= policy.maxRetries) throw e
                    delay(policy.retryDelayMillis * attempt)
                    continue
                }
                if (complete) break
                if (received) {
                    attempt = 0
                    // A server that closes after every replay would otherwise be reopened in a tight loop.
                    delay(policy.retryDelayMillis)
                    continue
                }
                attempt++
                if (attempt >= policy.maxRetries) {
                    throw InvalidResponseDataError("google.interactions: SSE stream closed without producing any events.")
                }
                delay(policy.retryDelayMillis * attempt)
            }
        } catch (e: CancellationException) {
            if (!complete) withContext(NonCancellable) { cancel(interactionId, headers) }
            throw e
        }
    }

    /**
     * Best-effort `POST /interactions/{id}/cancel`.
     *
     * Failures and non-2xx answers are swallowed: this runs while a cancellation or a failure is
     * already propagating, and a cleanup that throws replaces the error the caller needs to see. No
     * retry either, for the same reason. Skipped outright without an id.
     */
    suspend fun cancel(interactionId: String?, headers: Map<String, String>) {
        if (interactionId.isNullOrEmpty()) return
        runCatching {
            http.withRetryPolicy(RetryPolicy.None)
                .postJson("${interactionUrl(interactionId)}/cancel", JsonObject(emptyMap()), headers)
        }
    }

    private fun interactionUrl(interactionId: String): String =
        "$baseUrl/interactions/${interactionId.encodeURLPathPart()}"

    private fun streamUrl(interactionId: String, lastEventId: String?): String {
        val resume = lastEventId?.let { "&last_event_id=${it.encodeURLPathPart()}" }.orEmpty()
        return "${interactionUrl(interactionId)}?stream=true$resume"
    }

    /**
     * A GET that streams Server-Sent Events.
     *
     * The transport's SSE verb is POST-only, so this is the GET half of it, built the same way: the
     * events are framed by the shared line and event parsers, `Accept-Encoding: identity` is pinned
     * because gzip batches an SSE body until the server closes, and every transport failure becomes a
     * retryable [APICallError] so the reconnect loop above can see it.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun getSse(url: String, headers: Map<String, String>): Flow<SseEvent> = flow {
        val statement = client.prepareGet(url) {
            header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
            header(HttpHeaders.AcceptEncoding, "identity")
            headers.forEach { (key, value) -> header(key, value) }
        }
        // True while the collector is running: an exception escaping then is the collector's, not the
        // transport's, and wrapping it as a retryable transport error would reconnect the stream and
        // re-deliver frames to a consumer that has already failed.
        var downstream = false
        try {
            statement.execute { response ->
                if (!response.status.isSuccess()) {
                    throw apiCallError(
                        url = url,
                        statusCode = response.status.value,
                        responseBody = runCatching { response.bodyAsText() }.getOrNull(),
                        responseHeaders = response.lowerCasedHeaders(),
                        requestBody = null,
                        structure = GoogleErrorStructure,
                    )
                }
                response.byteChunks().asLines().asSseEvents().collect { event ->
                    downstream = true
                    emit(event)
                    downstream = false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: APICallError) {
            throw e
        } catch (e: Throwable) {
            if (downstream) throw e
            throw e.asApiCallError(url, null)
        }
    }
}

/** One SSE payload, parsed once: [json] is null when the payload was not JSON. */
internal class InteractionsFrame(val data: String, val json: JsonElement?)

/** The response body as it arrives, in the sizes the socket produced it. */
private fun HttpResponse.byteChunks(): Flow<ByteArray> = flow {
    val channel = bodyAsChannel()
    val buffer = ByteArray(READ_BUFFER)
    while (currentCoroutineContext().isActive) {
        val read = channel.readAvailable(buffer)
        if (read <= 0) break
        emit(buffer.copyOf(read))
    }
}

/** The typed response, or [InvalidResponseDataError] naming the body that did not fit. */
internal fun decodeInteractionsResponse(raw: JsonElement): InteractionsResponse =
    runCatching { ProviderJson.decodeFromJsonElement(InteractionsResponse.serializer(), raw) }
        .getOrElse { failure ->
            throw InvalidResponseDataError(
                message = "google.interactions: response did not match the Interaction shape",
                data = raw,
                cause = failure,
            )
        }

private const val READ_BUFFER = 8 * 1024
