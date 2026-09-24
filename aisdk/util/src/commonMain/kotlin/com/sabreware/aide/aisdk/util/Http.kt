package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.DownloadError
import com.sabreware.aide.aisdk.EmptyResponseBodyError
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * The HTTP surface a provider needs, over an **injected** [HttpClient].
 *
 * The engine is never constructed here. A library that builds its own transport cannot share a
 * connection pool with its host, and on Android that means a second thread pool nobody asked for — which
 * is also why AIDE already funnels every caller through one `KtorClientFactory`.
 *
 * Three properties every verb below shares, each of which was previously missing somewhere:
 *
 * - **Everything returns [HttpResult].** Status, lower-cased headers and the request body come back with
 *   the payload, because a provider that cannot see them cannot populate a `ResponseInfo`, honour a
 *   `Retry-After`, or name a request id in a support ticket.
 * - **Every transport failure becomes a retryable [APICallError].** A dropped connection is the most
 *   retryable thing that can happen to a request; it used to be marked non-retryable by omission, and
 *   escaped three of the verbs as a raw Ktor exception past every `catch (e: APICallError)`.
 * - **Every verb retries** under [retryPolicy], honouring a server's `retry-after` over the computed
 *   backoff. A 429 on the first attempt used to fail the user's whole turn.
 */
public class ProviderHttp(
    private val client: HttpClient,
    private val json: Json = ProviderJson,
    /** How this vendor spells its errors. Default handles the shapes most of them copied from OpenAI. */
    private val errorStructure: ProviderErrorStructure = ProviderErrorStructure.Default,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    /**
     * Epoch millis, injected so a test can pin a timestamp.
     *
     * The default is a real clock rather than a zero. It used to be the latter, which meant every
     * `ResponseInfo` and `ModalityResponse` this library produced carried a null timestamp unless the
     * DI site remembered to pass a clock, and none did — so a field the contract advertises on every
     * result was, in practice, never populated anywhere.
     */
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /**
     * The `User-Agent` sent when the caller's headers carry none.
     *
     * Vendors gate features and read telemetry off it, and a client that sends nothing is invisible
     * in both. A caller's own `User-Agent` header always wins; null here suppresses the default
     * entirely.
     */
    private val userAgent: String? = AISDK_USER_AGENT,
) {

    /** A copy of this transport that speaks [structure]'s error dialect. */
    public fun withErrorStructure(structure: ProviderErrorStructure): ProviderHttp =
        ProviderHttp(client, json, structure, retryPolicy, now, userAgent)

    /** A copy of this transport with a different retry policy — `RetryPolicy.None` to opt out. */
    public fun withRetryPolicy(policy: RetryPolicy): ProviderHttp =
        ProviderHttp(client, json, errorStructure, policy, now, userAgent)

    /** [headers] plus the default `User-Agent`, unless the caller already set one (any casing). */
    private fun Map<String, String>.withUserAgent(): Map<String, String> {
        val agent = userAgent ?: return this
        if (keys.any { it.equals("user-agent", ignoreCase = true) }) return this
        return this + ("User-Agent" to agent)
    }

    /** POSTs [body] and parses the response as JSON. */
    public suspend fun postJson(
        url: String,
        body: JsonElement,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult<JsonElement> {
        val payload = json.encodeToString(JsonElement.serializer(), body)
        return retrying {
            val response = attempt(url, payload) {
                client.post(url) {
                    contentType(ContentType.Application.Json)
                    headers.withUserAgent().applyTo(this)
                    setBody(payload)
                }
            }
            response.readJsonOrThrow(url, payload)
        }
    }

    /** GETs and parses the response as JSON. */
    public suspend fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult<JsonElement> = retrying {
        val response = attempt(url, null) { client.get(url) { headers.withUserAgent().applyTo(this) } }
        response.readJsonOrThrow(url, null)
    }

    /**
     * DELETEs and parses the response as JSON.
     *
     * For URLs built from a configured endpoint only — a vendor's file id under its own API host. There
     * is no untrusted-URL path here; a URL a RESPONSE named goes through [getBytes] and its guard.
     *
     * A successful answer with no body — a 204, or the blank some vendors send — is [JsonNull] rather
     * than an [EmptyResponseBodyError]: the vendor confirmed the deletion, and having nothing to say
     * about it is the normal shape of that confirmation, not a broken response.
     */
    public suspend fun delete(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult<JsonElement> = retrying {
        val response = attempt(url, null) { client.delete(url) { headers.withUserAgent().applyTo(this) } }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw response.toApiCallError(url, null, text)
        response.meta(url, null, if (text.isBlank()) JsonNull else parseJsonElement(text, json))
    }

    /**
     * POSTs [body] and streams the Server-Sent Events back.
     *
     * The flow is cold and single-shot: the request is issued on collection, and cancelling the collector
     * cancels the call. That is why nothing in this module takes an abort parameter.
     *
     * `Accept-Encoding: identity` is pinned because gzip batches an SSE body until the server closes,
     * which defeats streaming entirely and presents as "the model only answers when it's finished".
     *
     * [onResponse] fires once, with the response metadata, BEFORE the first event. A stream has no
     * return value to hang headers off, and the provider needs the request id and rate-limit headers
     * that only exist on the response that opened it.
     *
     * Retry covers only the establishing of the stream. Once an event has been emitted the caller holds
     * partial output, and replaying the request would duplicate it — the retry there belongs to the
     * runtime's step loop, which knows what it has already shown the user.
     */
    public fun postSse(
        url: String,
        body: JsonElement,
        headers: Map<String, String> = emptyMap(),
        onResponse: (HttpResult<Unit>) -> Unit = {},
    ): Flow<SseEvent> = flow {
        val payload = json.encodeToString(JsonElement.serializer(), body)
        var opened = false
        retrying(canRetry = { !opened }) {
            val statement = client.preparePost(url) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
                header(HttpHeaders.AcceptEncoding, "identity")
                headers.withUserAgent().applyTo(this)
                setBody(payload)
            }
            executeStreaming(statement, url, payload) { response ->
                onResponse(response.meta(url, payload, Unit))
                opened = true
                response.byteChunks().asLines().asSseEvents().stopAtDone().collect { emit(it) }
            }
        }
    }

    /**
     * POSTs raw bytes and streams the response body back in chunks.
     *
     * For transports that are not SSE — AWS's binary event stream, principally. Chunks are whatever the
     * socket produced, with no framing assumed: the caller owns the framing, because only it knows what
     * the protocol is.
     */
    public fun postBytes(
        url: String,
        payload: ByteArray,
        headers: Map<String, String> = emptyMap(),
        onResponse: (HttpResult<Unit>) -> Unit = {},
    ): Flow<ByteArray> = flow {
        var opened = false
        retrying(canRetry = { !opened }) {
            val statement = client.preparePost(url) {
                headers.withUserAgent().applyTo(this)
                setBody(payload)
            }
            executeStreaming(statement, url, null) { response ->
                onResponse(response.meta(url, null, Unit))
                opened = true
                response.byteChunks().collect { emit(it) }
            }
        }
    }

    /**
     * POSTs JSON and reads the whole response as bytes.
     *
     * For endpoints that answer with a file rather than a document — text-to-speech, principally. The
     * body is read whole because audio has no useful streaming boundary here: a caller wants a playable
     * file, not a prefix of one.
     */
    public suspend fun postBytesForBytes(
        url: String,
        body: JsonElement,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult<ByteArray> {
        val payload = json.encodeToString(JsonElement.serializer(), body)
        return retrying {
            val response = attempt(url, payload) {
                client.post(url) {
                    contentType(ContentType.Application.Json)
                    headers.withUserAgent().applyTo(this)
                    setBody(payload)
                }
            }
            if (!response.status.isSuccess()) throw response.toApiCallError(url, payload)
            response.meta(url, payload, response.readCappedBytes(url))
        }
    }

    /**
     * POSTs a multipart form and parses the JSON response.
     *
     * Audio endpoints take a file upload rather than a JSON document, so this is the one shape that
     * cannot go through [postJson]. [fileName] matters more than it looks: several servers infer the
     * audio format from the extension and reject an upload named without one, which presents as
     * "unsupported file format" for a file that is perfectly supported.
     *
     * [fields] is a list of pairs rather than a map because several vendors take a repeated field name
     * to mean a list — AssemblyAI's `speech_models` among them — and a map cannot express that at all.
     *
     * [fileLast] exists because part ORDER is load-bearing at some vendors: xAI's `/stt` reads the
     * settings that apply to the upload as it streams the parts, so a `file` that arrives first is
     * transcribed with every option ignored — a success with the wrong result rather than an error.
     *
     * The ergonomic overload of [postMultipartParts], which assembles every multipart body: one file
     * plus string fields is the shape almost every audio endpoint wants, and spelling it as a part list
     * at each of those call sites would be ceremony. Both go out through one assembler, so there is a
     * single body-building path to keep correct.
     */
    public suspend fun postMultipart(
        url: String,
        fileField: String,
        fileName: String,
        fileBytes: ByteArray,
        fileContentType: String,
        fields: List<Pair<String, String>> = emptyList(),
        headers: Map<String, String> = emptyMap(),
        fileLast: Boolean = false,
    ): HttpResult<JsonElement> {
        val file = MultipartPart.File(fileField, fileName, fileBytes, fileContentType)
        val rest = fields.map { (name, value) -> MultipartPart.Field(name, value) }
        return postMultipartParts(
            url = url,
            parts = if (fileLast) rest + file else listOf(file) + rest,
            headers = headers,
        )
    }

    /**
     * POSTs a multipart form assembled from an ORDERED list of parts, and parses the JSON response.
     *
     * The general case, and the one assembler both entry points use. The single-file overload above
     * cannot express two things some vendors require: SEVERAL file parts under one repeated field name
     * — Anthropic's Skills API takes a whole directory as `files[]`, one part per file — and a file
     * whose part carries no content type at all, which is how the reference uploads skill files. A list
     * of [MultipartPart]s says both, and keeps part order in the caller's hands, which
     * [postMultipart]'s `fileLast` flag already established is load-bearing at some vendors.
     */
    public suspend fun postMultipartParts(
        url: String,
        parts: List<MultipartPart>,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult<JsonElement> = withMultipartBody(parts) { body ->
        retrying {
            val response = attempt(url, null) {
                client.post(url) {
                    headers.withUserAgent().applyTo(this)
                    setBody(body())
                }
            }
            response.readJsonOrThrow(url, null)
        }
    }

    /**
     * POSTs a multipart form whose file parts may be STREAMS, and parses the JSON response.
     *
     * The upload verb for a file too large to hold whole: a [MultipartPart.Stream] is written to the
     * socket as its flow produces chunks, so nothing larger than one chunk is ever resident. Parts go
     * out in list order, which vendors depend on (xAI reads an upload's expiry fields only if they
     * precede the file), through the same assembler every other multipart verb uses.
     *
     * Two things differ from the buffered verbs, and they are why this is a verb rather than a part
     * type alone. Retry stops the moment a stream part has begun: a transport failure before that — a
     * refused connection, a DNS miss — is retried as always, but a failure mid-body is not, because the
     * flow may not be collectable twice and a second collection of a one-shot source sends an empty
     * file; the caller retries the whole operation if it can. And teardown is deterministic: the
     * coroutine writing each stream is a child of this call, so a failed or abandoned request cancels
     * it rather than leaving it parked on a channel nobody reads — the reference's `dispose`, which it
     * needs because `fetch` does not reliably cancel a streaming request body.
     */
    public suspend fun postMultipartStream(
        url: String,
        parts: List<MultipartPart>,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult<JsonElement> {
        var bodyStarted = false
        return withMultipartBody(parts, onStreamStart = { bodyStarted = true }) { body ->
            retrying(canRetry = { !bodyStarted }) {
                val response = attempt(url, null) {
                    client.post(url) {
                        headers.withUserAgent().applyTo(this)
                        setBody(body())
                    }
                }
                response.readJsonOrThrow(url, null)
            }
        }
    }

    /**
     * POSTs a multipart form assembled from an ORDERED list of parts, and reads the whole response as
     * bytes.
     *
     * The fourth combination of the two body shapes and the two answer shapes, and the one a job
     * endpoint that takes an upload and answers with the finished file needs: Prodia's image-to-image
     * and image-to-video jobs send the picture as a part and answer with a multipart body the caller
     * decodes with [MultipartResponse]. Before this verb existed those two jobs were refused outright,
     * because the only alternative — running the job from the prompt alone — returns a plausible
     * picture of the wrong thing.
     *
     * The body goes out through the same assembler as [postMultipartParts], and the answer comes back
     * through the same capped read as [postBytesForBytes], so neither half has a second copy to drift.
     * [MultipartResponse.boundaryOf] reads the boundary off `content-type` in the result's headers.
     */
    public suspend fun postMultipartForBytes(
        url: String,
        parts: List<MultipartPart>,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult<ByteArray> = withMultipartBody(parts) { body ->
        retrying {
            val response = attempt(url, null) {
                client.post(url) {
                    headers.withUserAgent().applyTo(this)
                    setBody(body())
                }
            }
            if (!response.status.isSuccess()) throw response.toApiCallError(url, null)
            response.meta(url, null, response.readCappedBytes(url))
        }
    }

    /**
     * Runs [block] with the one multipart assembler, and tears down whatever it started.
     *
     * Every multipart request leaves through here, so a part's headers are spelled once: Ktor renders a
     * file part's `filename` as a second `Content-Disposition` value and joins the two with `; `, which
     * is the `form-data; name="…"; filename="…"` line the vendors read — and the one place to fix if a
     * vendor ever wants it spelled differently. [block] receives a FACTORY because a retry needs a fresh
     * body: Ktor's content is consumed by the attempt that sent it.
     *
     * A [MultipartPart.Stream] is written by a coroutine that is a child of this scope, so it lives
     * exactly as long as the request: a failure anywhere cancels a writer still parked on a full
     * channel, and the source flow's own exception — not a transport wrapper — is what the caller sees.
     * [onStreamStart] fires on the first chunk any stream produces, which is the point past which a
     * retry would re-collect it.
     */
    private suspend fun <T> withMultipartBody(
        parts: List<MultipartPart>,
        onStreamStart: () -> Unit = {},
        block: suspend (body: () -> MultiPartFormDataContent) -> T,
    ): T = coroutineScope {
        val writers = mutableListOf<Job>()
        var sourceFailure: Throwable? = null
        try {
            block { multipartBody(parts, this, writers, onStreamStart) { if (sourceFailure == null) sourceFailure = it } }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // A source that failed took the request down with it: the transport saw a closed channel and
            // reported a retryable failure, but the source's own error is the one the caller can act on.
            throw sourceFailure?.also { it.addSuppressed(e) } ?: e
        } finally {
            writers.forEach { it.cancel() }
        }
    }

    private fun multipartBody(
        parts: List<MultipartPart>,
        scope: CoroutineScope,
        writers: MutableList<Job>,
        onStreamStart: () -> Unit,
        onSourceFailure: (Throwable) -> Unit,
    ): MultiPartFormDataContent = MultiPartFormDataContent(
        formData {
            parts.forEach { part ->
                when (part) {
                    is MultipartPart.Field -> append(part.name, part.value)
                    is MultipartPart.File -> append(
                        part.field,
                        part.bytes,
                        filePartHeaders(part.fileName, part.contentType),
                    )
                    is MultipartPart.Stream -> append(
                        part.field,
                        ChannelProvider(part.byteSize) {
                            scope.writer {
                                part.content
                                    .catch { failure ->
                                        onSourceFailure(failure)
                                        throw failure
                                    }
                                    .collect { chunk ->
                                        onStreamStart()
                                        channel.writeFully(chunk)
                                    }
                            }.also { writer -> writers.add(writer.job) }.channel
                        },
                        filePartHeaders(part.fileName, part.contentType),
                    )
                }
            }
        },
    )

    /**
     * A file part's own headers. The filename is escaped as a quoted-string parameter and CR/LF are
     * stripped from both values — a header parameter must not be able to smuggle a line break, which
     * is the reference's `escapeMultipartHeaderValue`.
     */
    private fun filePartHeaders(fileName: String?, contentType: String?): Headers = Headers.build {
        contentType?.let { append(HttpHeaders.ContentType, it.withoutLineBreaks()) }
        fileName?.let { append(HttpHeaders.ContentDisposition, "filename=\"${it.asQuotedParameter()}\"") }
    }

    /**
     * POSTs raw bytes with an explicit content type and parses the JSON response.
     *
     * Some audio APIs — Deepgram among them — take the audio as the request body itself rather than as a
     * multipart upload, declaring the format in `Content-Type`. Sending multipart to one of those does
     * not fail cleanly: it transcribes the MIME envelope as if it were audio.
     */
    public suspend fun postRawBytes(
        url: String,
        payload: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult<JsonElement> = retrying {
        val response = attempt(url, null) {
            client.post(url) {
                header(HttpHeaders.ContentType, contentType)
                headers.withUserAgent().applyTo(this)
                setBody(payload)
            }
        }
        response.readJsonOrThrow(url, null)
    }

    /**
     * GETs raw bytes from a URL, with credentials attached only where they belong.
     *
     * The async image and transcription vendors hand back a URL rather than the result, and a URL that
     * expires. Fetching it here means a caller receives the picture rather than a link that will be dead
     * by the time anything tries to render it.
     *
     * [trustedOrigin] is the API host. Headers ride along only when the URL is on it: a vendor naming a
     * foreign host in its own response would otherwise be handed our API key, and the URL is validated
     * before any request goes out so it cannot name a loopback or a metadata service either.
     */
    public suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        trustedOrigin: String? = null,
        maxBytes: Long = MAX_DOWNLOAD_BYTES,
    ): HttpResult<ByteArray> = followingRedirects(url, headers, trustedOrigin) { requestUrl, response ->
        response.meta(requestUrl, null, response.readCappedBytes(requestUrl, maxBytes))
    }

    /**
     * GETs a text body and emits it line by line, under the same redirect and origin guard as [getBytes].
     *
     * For JSONL — a batch's result file. Reading it whole costs three copies of a file that can be
     * hundreds of megabytes (the bytes, the string, the split) before the first item is seen; here a line
     * is decoded and handed on as its bytes arrive, and nothing larger than one line is ever resident.
     * Blank lines are dropped and a trailing `\r` tolerated, which is all the JSONL grammar needs.
     *
     * Cold: collection issues the request. Retries stop once the body has started, so a line is never
     * emitted twice.
     */
    public fun getLines(
        url: String,
        headers: Map<String, String> = emptyMap(),
        trustedOrigin: String? = null,
        maxBytes: Long = MAX_DOWNLOAD_BYTES,
    ): Flow<String> = flow {
        var opened = false
        try {
            followingRedirects(url, headers, trustedOrigin, canRetry = { !opened }) { requestUrl, response ->
                opened = true
                var total = 0L
                response.byteChunks()
                    .onEach { chunk ->
                        total += chunk.size
                        if (total > maxBytes) {
                            throw DownloadError(requestUrl, message = "Refusing to read more than $maxBytes bytes from $requestUrl")
                        }
                    }
                    .asLines()
                    .collect { line ->
                        val trimmed = line.removeSuffix("\r")
                        if (trimmed.isNotBlank()) emitDownstream(trimmed)
                    }
            }
        } catch (e: DownstreamFailure) {
            // The collector's own failure, handed back as itself: it is not a transport error and must
            // not be reported (or retried) as one.
            throw e.failure
        }
    }

    /**
     * GETs a body and emits it as it arrives, under the same redirect and origin guard as [getBytes].
     *
     * The streaming half of [getBytes] for bytes that have no line structure — a stored file's content
     * on its way to disk — where reading it whole would hold the entire file in memory to hand over
     * something the caller was going to write out chunk by chunk anyway. Chunks are whatever the socket
     * produced; nothing is framed. The reference's `createBinaryStreamResponseHandler`.
     *
     * [onResponse] fires once, with the response metadata, before the first chunk — the media type a
     * download reports comes off those headers, and a flow has no return value to carry it.
     *
     * Cold: collection issues the request. Retries stop once the body has started, so a chunk is never
     * emitted twice.
     */
    public fun getByteStream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        trustedOrigin: String? = null,
        maxBytes: Long = MAX_DOWNLOAD_BYTES,
        onResponse: (HttpResult<Unit>) -> Unit = {},
    ): Flow<ByteArray> = flow {
        var opened = false
        try {
            followingRedirects(url, headers, trustedOrigin, canRetry = { !opened }) { requestUrl, response ->
                opened = true
                onResponse(response.meta(requestUrl, null, Unit))
                var total = 0L
                response.byteChunks().collect { chunk ->
                    total += chunk.size
                    if (total > maxBytes) {
                        throw DownloadError(requestUrl, message = "Refusing to read more than $maxBytes bytes from $requestUrl")
                    }
                    emitDownstream(chunk)
                }
            }
        } catch (e: DownstreamFailure) {
            throw e.failure
        }
    }

    /** Emits, marking any exception the COLLECTOR raises so the transport guard leaves it alone. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> FlowCollector<T>.emitDownstream(value: T) {
        try {
            emit(value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw DownstreamFailure(e)
        }
    }

    /** A collector's exception in transit through the transport guard — see [emitDownstream]. */
    private class DownstreamFailure(val failure: Throwable) : RuntimeException(failure)

    /**
     * The download guard shared by [getBytes] and [getLines]: every hop is validated BEFORE it is
     * requested, credentials ride only on the trusted origin, and the final body is handed to [onBody]
     * still open — so a caller may read it whole or stream it.
     */
    private suspend fun <T> followingRedirects(
        url: String,
        headers: Map<String, String>,
        trustedOrigin: String?,
        canRetry: () -> Boolean = { true },
        onBody: suspend (requestUrl: String, response: HttpResponse) -> T,
    ): T {
        var hopUrl = url
        // Sanitised once, then only ever narrowed: a header the guard strips must not come back on a
        // later hop, and a chain that leaves the trusted origin and returns to it must not re-attach the
        // key it already dropped.
        var carried = sanitizeRequestHeaders(headers).withUserAgent()
        repeat(MAX_DOWNLOAD_REDIRECTS + 1) {
            DownloadUrl.validate(hopUrl)
            val requestUrl = hopUrl
            val sendHeaders = DownloadUrl.headersFor(requestUrl, trustedOrigin, carried)
            val hop: Hop<T> = retrying(canRetry) {
                // Redirects are followed by hand so each target is validated BEFORE it is requested.
                // Ktor's own following issues the request first, which means a public URL that
                // redirects to a metadata service has already reached it by the time we could look.
                val statement = redirectlessClient.prepareGet(requestUrl) { sendHeaders.applyTo(this) }
                try {
                    statement.execute { response ->
                        val location = response.headers[HttpHeaders.Location]
                        if (response.status.value in REDIRECT_STATUS_CODES && location != null) {
                            Hop.Redirect(URLBuilder(Url(requestUrl)).takeFrom(location).buildString())
                        } else {
                            if (!response.status.isSuccess()) throw response.toApiCallError(requestUrl, null)
                            Hop.Body(onBody(requestUrl, response))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: APICallError) {
                    throw e
                } catch (e: DownloadError) {
                    throw e
                } catch (e: DownstreamFailure) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                    throw e.asApiCallError(requestUrl, null)
                }
            }
            when (hop) {
                is Hop.Body -> return hop.result
                is Hop.Redirect -> {
                    if (!DownloadUrl.sameOrigin(hop.url, requestUrl)) {
                        // The fetch specification strips only `Authorization` on a cross-origin redirect,
                        // because a browser's CORS preflight covers the rest. There is no CORS here, and
                        // providers authenticate with custom headers — `x-api-key` and its cousins — so
                        // everything but the user agent goes.
                        carried = carried.filterKeys { it.lowercase() == "user-agent" }
                    }
                    hopUrl = hop.url
                }
            }
        }
        throw DownloadError(url, message = "Refusing to follow more than $MAX_DOWNLOAD_REDIRECTS redirects from $url")
    }

    private sealed interface Hop<out T> {
        data class Redirect(val url: String) : Hop<Nothing>
        data class Body<T>(val result: T) : Hop<T>
    }

    /**
     * The injected client with redirect following switched off.
     *
     * Derived from the caller's rather than built, so the engine, the connection pool and every other
     * plugin stay exactly as the host configured them.
     */
    private val redirectlessClient: HttpClient by lazy { client.config { followRedirects = false } }

    // -----------------------------------------------------------------------------------------------

    private suspend fun <T> retrying(
        canRetry: () -> Boolean = { true },
        block: suspend () -> T,
    ): T = withRetry(retryPolicy, canRetry) { block() }

    /** Issues a request, converting a transport failure into a RETRYABLE [APICallError]. */
    private suspend inline fun attempt(
        url: String,
        requestBody: String?,
        send: () -> HttpResponse,
    ): HttpResponse = runCatching { send() }.getOrElse { throw it.asApiCallError(url, requestBody) }

    private suspend inline fun executeStreaming(
        statement: io.ktor.client.statement.HttpStatement,
        url: String,
        requestBody: String?,
        crossinline body: suspend (HttpResponse) -> Unit,
    ) {
        try {
            statement.execute { response ->
                if (!response.status.isSuccess()) throw response.toApiCallError(url, requestBody)
                body(response)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: APICallError) {
            throw e
        } catch (e: Throwable) {
            throw e.asApiCallError(url, requestBody)
        }
    }

    private suspend fun HttpResponse.readJsonOrThrow(
        url: String,
        requestBody: String?,
    ): HttpResult<JsonElement> {
        val text = bodyAsText()
        if (!status.isSuccess()) throw toApiCallError(url, requestBody, text)
        if (text.isBlank()) throw EmptyResponseBodyError("Empty response body from $url")
        return meta(url, requestBody, parseJsonElement(text, json))
    }

    private fun <T> HttpResponse.meta(url: String, requestBody: String?, value: T): HttpResult<T> =
        HttpResult(
            value = value,
            url = url,
            statusCode = status.value,
            headers = lowerCasedHeaders(),
            requestBody = requestBody,
            timestamp = now().takeIf { it != 0L },
        )

    private suspend fun HttpResponse.toApiCallError(
        url: String,
        requestBody: String?,
        body: String? = null,
    ): APICallError = apiCallError(
        url = url,
        statusCode = status.value,
        responseBody = body ?: runCatching { bodyAsText() }.getOrNull(),
        responseHeaders = lowerCasedHeaders(),
        requestBody = requestBody,
        structure = errorStructure,
    )
}

/**
 * Reads the whole body, refusing one that exceeds [maxBytes] — WITHOUT buffering past the cap.
 *
 * An unbounded read of a provider-controlled body is an out-of-memory kill on Android. The
 * `Content-Length` check is only the fast path: a chunked response, or one whose declared length
 * lies, has to be caught by the running total, and it has to be caught BEFORE the bytes are held —
 * measuring after `readRawBytes()` is exactly the buffering the cap exists to prevent.
 */
public suspend fun HttpResponse.readCappedBytes(
    url: String,
    maxBytes: Long = MAX_DOWNLOAD_BYTES,
): ByteArray {
    // A JVM array is Int-indexed, so past Int.MAX_VALUE there is no ByteArray to return regardless
    // of what the caller asked for.
    val effectiveCap = minOf(maxBytes, Int.MAX_VALUE.toLong())
    val declared = headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > effectiveCap) {
        throw DownloadError(
            url,
            statusCode = status.value,
            message = "Refusing to read $declared bytes from $url: over the $maxBytes byte cap",
        )
    }
    val channel = bodyAsChannel()
    val buffer = ByteArray(READ_BUFFER)
    val chunks = mutableListOf<ByteArray>()
    var total = 0L
    while (true) {
        val read = channel.readAvailable(buffer)
        if (read <= 0) break
        total += read
        if (total > effectiveCap) {
            throw DownloadError(
                url,
                statusCode = status.value,
                message = "Response from $url exceeded the $maxBytes byte cap",
            )
        }
        chunks += buffer.copyOf(read)
    }
    val out = ByteArray(total.toInt())
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(out, offset)
        offset += chunk.size
    }
    return out
}

/**
 * Header names lower-cased.
 *
 * HTTP header names are case-insensitive and servers disagree in practice. Preserving the server's
 * casing is why `Retry-After` was invisible to a `responseHeaders["retry-after"]` lookup.
 */
public fun HttpResponse.lowerCasedHeaders(): Map<String, String> =
    headers.entries().associate { it.key.lowercase() to it.value.joinToString(",") }

private fun Map<String, String>.applyTo(builder: io.ktor.client.request.HttpRequestBuilder) {
    forEach { (k, v) -> builder.header(k, v) }
}

/**
 * The response body as it arrives, with no framing assumed.
 *
 * Whatever the socket produced, in the sizes it produced it. Every protocol's framing — SSE lines, AWS's
 * binary event stream — is applied over this by an operator, so one read loop serves both and neither
 * can drift from the other.
 */
private fun HttpResponse.byteChunks(): Flow<ByteArray> = flow {
    val channel = bodyAsChannel()
    val buffer = ByteArray(READ_BUFFER)
    while (currentCoroutineContext().isActive) {
        val read = channel.readAvailable(buffer)
        if (read <= 0) break
        emit(buffer.copyOf(read))
    }
}

/**
 * Maps a transport failure onto a RETRYABLE [APICallError], rethrowing cancellation untouched.
 *
 * Swallowing a [CancellationException] here would turn "the user pressed stop" into "the provider
 * failed", which is both a wrong error and a coroutine that no longer cancels.
 *
 * Retryable is the correct default and was previously the opposite by omission: with no status code,
 * `defaultIsRetryable(null)` returned false, so a dropped connection — the single most retryable failure
 * there is — was marked as one to give up on.
 */
public fun Throwable.asApiCallError(url: String, requestBody: String?): Throwable = when (this) {
    is CancellationException -> this
    is APICallError -> this
    is DownloadError -> this
    else -> APICallError(
        message = "Request to $url failed: ${message ?: this::class.simpleName}",
        url = url,
        requestBodyValues = requestBody,
        isRetryable = true,
        cause = this,
    )
}

/**
 * The status codes that are redirects per the fetch specification.
 *
 * 300 and 304 are deliberately absent: neither is a redirect, even when a server attaches a `Location`.
 */
private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)

/** A chain longer than this is a loop or a crawl, not a download. */
public const val MAX_DOWNLOAD_REDIRECTS: Int = 10

/** The default `User-Agent`. One place, so a version bump does not chase call sites. */
public const val AISDK_USER_AGENT: String = "aisdk-kotlin/0.1.0"

/** 2 GiB, matching the reference. Past this a caller wants a file on disk, not a byte array. */
public const val MAX_DOWNLOAD_BYTES: Long = 2L * 1024 * 1024 * 1024

private const val READ_BUFFER = 8 * 1024

/**
 * One part of a multipart form, for [ProviderHttp.postMultipartParts].
 *
 * A sealed list rather than a files-plus-fields pair because ORDER is the thing being expressed: some
 * vendors read settings only if they arrive before the upload, and a repeated field name — several
 * [File] parts all named `files[]` — is a list some APIs require and a map cannot say at all.
 */
public sealed interface MultipartPart {

    /**
     * A file part. [contentType] is nullable because some endpoints — Anthropic's Skills API — take
     * their files untyped, and a guessed `application/octet-stream` is a wire change the reference
     * does not make.
     */
    public data class File(
        val field: String,
        val fileName: String?,
        val bytes: ByteArray,
        val contentType: String? = null,
    ) : MultipartPart {

        override fun equals(other: Any?): Boolean = this === other ||
            (
                other is File && field == other.field && fileName == other.fileName &&
                    bytes.contentEquals(other.bytes) && contentType == other.contentType
                )

        override fun hashCode(): Int =
            listOf(field, fileName, bytes.contentHashCode(), contentType).hashCode()
    }

    /**
     * A file part whose bytes arrive as a flow — for [ProviderHttp.postMultipartStream].
     *
     * [content] is collected once, while the body is written, and never held whole. [byteSize], when
     * known, is declared as the part's length; null sends it chunked.
     */
    public class Stream(
        public val field: String,
        public val fileName: String?,
        public val content: Flow<ByteArray>,
        public val byteSize: Long? = null,
        public val contentType: String? = null,
    ) : MultipartPart

    /** A plain string field. */
    public data class Field(val name: String, val value: String) : MultipartPart
}

private fun String.withoutLineBreaks(): String = replace("\r", "").replace("\n", "")

/** A quoted-string parameter value: no line breaks, and the two quoted-string specials escaped. */
private fun String.asQuotedParameter(): String =
    withoutLineBreaks().replace("\\", "\\\\").replace("\"", "\\\"")
