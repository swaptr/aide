package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.DownloadError
import com.sabreware.aide.aisdk.EmptyResponseBodyError
import com.sabreware.aide.aisdk.RetryError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeStringUtf8
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The transport, which every provider now depends on and which had no test at all.
 *
 * Three properties here are the ones a provider cannot check for itself: what comes back alongside the
 * payload, what happens on a failure, and what never leaves the process. The retry tests run on the
 * scheduler's virtual clock, so a `retry-after: 7` is asserted exactly and costs no wall time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpTest {

    private val body = buildJsonObject { put("model", "gpt-4o") }

    private fun http(
        retryPolicy: RetryPolicy = RetryPolicy.None,
        errorStructure: ProviderErrorStructure = ProviderErrorStructure.Default,
        now: () -> Long = { 0L },
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Pair<ProviderHttp, MockEngine> {
        val engine = MockEngine { request -> handler(request) }
        return ProviderHttp(
            client = HttpClient(engine),
            errorStructure = errorStructure,
            retryPolicy = retryPolicy,
            now = now,
        ) to engine
    }

    /**
     * A body that delivers one event and then drops the connection.
     *
     * The failure waits on [emitted] so the collector has provably received that event first: a stream
     * that fails before emitting anything is a different case, and one that retries.
     */
    private fun CoroutineScope.failingStream(emitted: CompletableDeferred<Unit>): ByteReadChannel {
        val channel = ByteChannel(autoFlush = true)
        launch {
            channel.writeStringUtf8("data: {\"a\":1}\n\n")
            channel.flush()
            emitted.await()
            channel.cancel(IllegalStateException("connection reset"))
        }
        return channel
    }

    private fun MockRequestHandleScope.json(text: String, vararg headers: Pair<String, String>) =
        respond(
            content = text,
            headers = headersOf(*headers.map { it.first to listOf(it.second) }.toTypedArray()),
        )

    // -- what comes back alongside the payload -------------------------------------------------------

    @Test
    fun `a result carries the status, the lower-cased headers and the request body`() = runTest {
        val (http, _) = http(now = { 1_700_000_000_000 }) {
            json("""{"ok":true}""", "X-Request-Id" to "req_42", "Retry-After" to "3")
        }

        val result = http.postJson("https://api.example.com/v1/chat", body)

        assertEquals(HttpStatusCode.OK.value, result.statusCode)
        assertEquals("https://api.example.com/v1/chat", result.url)
        // Lower-cased, because a server's own casing is arbitrary and a lookup that respects it misses.
        assertEquals("req_42", result.headers["x-request-id"])
        assertEquals("3", result.headers["retry-after"])
        assertEquals("req_42", result.requestId)
        assertEquals(1_700_000_000_000, result.timestamp)
        // The exact bytes that went out, so a `RequestInfo` names what the vendor actually received.
        assertEquals("""{"model":"gpt-4o"}""", result.requestBody)
        assertEquals(true, result.value.jsonObject["ok"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `a GET carries no request body`() = runTest {
        val (http, _) = http { json("""{"status":"done"}""") }

        assertNull(http.getJson("https://api.example.com/v1/jobs/1").requestBody)
    }

    @Test
    fun `a success with an empty body is an error, not an empty object`() = runTest {
        val (http, _) = http { json("") }

        assertFailsWith<EmptyResponseBodyError> { http.postJson("https://api.example.com/v1/chat", body) }
    }

    // -- what happens on a failure -------------------------------------------------------------------

    @Test
    fun `an error structure extracts the vendor message and keeps the parsed body`() = runTest {
        val (http, _) = http {
            respondError(HttpStatusCode.BadRequest, """{"error":{"message":"model not found","code":"m404"}}""")
        }

        val error = assertFailsWith<APICallError> { http.postJson("https://api.example.com/v1/chat", body) }

        assertContains(error.message.orEmpty(), "model not found")
        // The parsed body, so a caller switches on the vendor's own code instead of matching English.
        assertEquals("m404", error.data?.jsonObject?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.content)
        assertEquals(HttpStatusCode.BadRequest.value, error.statusCode)
        assertEquals("""{"model":"gpt-4o"}""", error.requestBodyValues)
    }

    @Test
    fun `a vendor may declare its own status retryable`() = runTest {
        val structure = ProviderErrorStructure(isRetryable = { code, _ -> code == 400 })
        var calls = 0
        val (http, _) = http(retryPolicy = RetryPolicy(maxRetries = 1), errorStructure = structure) {
            calls++
            if (calls == 1) respondError(HttpStatusCode.BadRequest, """{"error":"warming up"}""")
            else json("""{"ok":true}""")
        }

        http.postJson("https://api.example.com/v1/chat", body)

        assertEquals(2, calls)
    }

    @Test
    fun `a transport failure is retried and reported as an API call error`() = runTest {
        var calls = 0
        val (http, _) = http(retryPolicy = RetryPolicy(maxRetries = 2)) {
            calls++
            if (calls == 1) throw IllegalStateException("connection reset")
            json("""{"ok":true}""")
        }

        // A dropped connection is the most retryable failure there is, and used to be marked otherwise
        // by omission: with no status code the default said "do not retry".
        http.postJson("https://api.example.com/v1/chat", body)

        assertEquals(2, calls)
    }

    @Test
    fun `a transport failure that never recovers surfaces every attempt`() = runTest {
        val (http, _) = http(retryPolicy = RetryPolicy(maxRetries = 2)) {
            throw IllegalStateException("connection reset")
        }

        val error = assertFailsWith<RetryError> { http.postJson("https://api.example.com/v1/chat", body) }

        assertEquals(RetryError.Reason.MaxRetriesExceeded, error.reason)
        // Every failure, not only the last: the first attempt often names the real problem and the rest
        // merely time out behind it.
        assertEquals(3, error.errors.size)
        assertTrue(error.errors.all { it is APICallError })
    }

    @Test
    fun `a non-retryable failure on the first attempt propagates untouched`() = runTest {
        val (http, _) = http(retryPolicy = RetryPolicy(maxRetries = 2)) {
            respondError(HttpStatusCode.Unauthorized, """{"error":{"message":"bad key"}}""")
        }

        // Not wrapped in a RetryError: nothing was retried, so there is nothing to describe, and a
        // wrapper here is a `catch (e: APICallError)` that stops matching.
        val error = assertFailsWith<APICallError> { http.postJson("https://api.example.com/v1/chat", body) }

        assertEquals(HttpStatusCode.Unauthorized.value, error.statusCode)
    }

    @Test
    fun `retry-after is honoured over the computed backoff`() = runTest {
        var calls = 0
        val (http, _) = http(retryPolicy = RetryPolicy(maxRetries = 1, initialDelayMillis = 2_000)) {
            calls++
            if (calls == 1) {
                respond(
                    content = """{"error":"slow down"}""",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf("Retry-After", listOf("7")),
                )
            } else {
                json("""{"ok":true}""")
            }
        }

        val start = testScheduler.currentTime
        http.postJson("https://api.example.com/v1/chat", body)

        // The server's number, not ours: 7s, not the 2s the policy would have computed.
        assertEquals(7_000, testScheduler.currentTime - start)
    }

    @Test
    fun `retry-after-ms wins over retry-after and both are clamped`() = runTest {
        var calls = 0
        val (http, _) = http(retryPolicy = RetryPolicy(maxRetries = 1)) {
            calls++
            if (calls == 1) {
                respond(
                    content = """{"error":"slow down"}""",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(
                        "retry-after-ms" to listOf("1500.5"),
                        "retry-after" to listOf("30"),
                    ),
                )
            } else {
                json("""{"ok":true}""")
            }
        }

        val start = testScheduler.currentTime
        http.postJson("https://api.example.com/v1/chat", body)

        assertEquals(1_500, testScheduler.currentTime - start)
    }

    @Test
    fun `an absurd retry-after is clamped rather than obeyed`() {
        // A server naming a week is telling us to give up, not to hold a coroutine open for a week.
        assertEquals(MAX_RETRY_AFTER_MS, mapOf("retry-after" to "604800").retryAfterMillis())
        assertEquals(0, mapOf("retry-after" to "-5").retryAfterMillis())
        // Only the numeric forms are honoured; an HTTP date falls back to the computed backoff.
        assertNull(mapOf("retry-after" to "Wed, 21 Oct 2026 07:28:00 GMT").retryAfterMillis())
    }

    // -- streams ------------------------------------------------------------------------------------

    @Test
    fun `postSse reports the response before the first event and frames the body`() = runTest {
        val (http, _) = http {
            respond(
                content = "data: {\"a\":1}\n\ndata: {\"a\":2}\n\ndata: $SSE_DONE\n\n",
                headers = headersOf("X-Request-Id", listOf("req_7")),
            )
        }
        var meta: HttpResult<Unit>? = null

        val events = http.postSse("https://api.example.com/v1/chat", body) { meta = it }.toList()

        // A stream has no return value to hang headers off, so this callback is the provider's only
        // chance at the request id.
        assertEquals("req_7", meta?.requestId)
        assertEquals(listOf("""{"a":1}""", """{"a":2}"""), events.map { it.data })
    }

    @Test
    fun `a transport failure mid-stream arrives as an API call error`() = runTest {
        val emitted = CompletableDeferred<Unit>()
        val (http, _) = http { respond(content = failingStream(emitted)) }

        // Every provider catches APICallError around its stream. A raw Ktor exception escaping here
        // passes straight through all of them and out to the UI as an unhandled crash.
        val error = assertFailsWith<APICallError> {
            http.postSse("https://api.example.com/v1/chat", body).collect { emitted.complete(Unit) }
        }

        assertContains(error.message.orEmpty(), "https://api.example.com/v1/chat")
    }

    @Test
    fun `a stream that has already emitted is not retried`() = runTest {
        var calls = 0
        val emitted = CompletableDeferred<Unit>()
        val (http, _) = http(retryPolicy = RetryPolicy(maxRetries = 2)) {
            calls++
            respond(content = failingStream(emitted))
        }

        assertFailsWith<APICallError> {
            http.postSse("https://api.example.com/v1/chat", body).collect { emitted.complete(Unit) }
        }

        // Replaying would re-emit output the caller has already shown the user. Retrying a stream is the
        // step loop's decision, since only it knows what has been rendered.
        assertEquals(1, calls)
    }

    @Test
    fun `a stream that fails before opening is retried`() = runTest {
        var calls = 0
        val (http, _) = http(retryPolicy = RetryPolicy(maxRetries = 2)) {
            calls++
            if (calls == 1) {
                respondError(HttpStatusCode.ServiceUnavailable, """{"error":"restarting"}""")
            } else {
                respond(content = "data: {\"a\":1}\n\n")
            }
        }

        val events = http.postSse("https://api.example.com/v1/chat", body).toList()

        assertEquals(2, calls)
        assertEquals(1, events.size)
    }

    @Test
    fun `postBytes wraps a transport failure rather than letting Ktor's escape`() = runTest {
        val emitted = CompletableDeferred<Unit>()
        val (http, _) = http { respond(content = failingStream(emitted)) }

        assertFailsWith<APICallError> {
            http.postBytes("https://api.example.com/v1/stream", "in".encodeToByteArray())
                .collect { emitted.complete(Unit) }
        }
    }

    // -- what never leaves the process ---------------------------------------------------------------

    @Test
    fun `getBytes refuses an address that is not on the public internet`() = runTest {
        val (http, engine) = http { json("secret") }

        listOf(
            "http://169.254.169.254/latest/meta-data/iam/",
            "http://127.0.0.1:8080/x",
            "http://10.1.2.3/x",
            "file:///etc/passwd",
        ).forEach { url ->
            assertFailsWith<DownloadError>(url) { http.getBytes(url) }
        }
        // Refused BEFORE the request: Ktor follows redirects blindly, so a check after the fact is a
        // check that already made the connection.
        assertEquals(0, engine.requestHistory.size)
    }

    @Test
    fun `getBytes drops credentials off the trusted origin`() = runTest {
        val (http, engine) = http { json("bytes") }
        val auth = mapOf("authorization" to "Bearer secret")

        http.getBytes("https://cdn.other.example/f/1", auth, trustedOrigin = "https://api.example.com")

        // A vendor naming a foreign host in its own response would otherwise be handed our API key.
        assertNull(engine.requestHistory.single().headers["authorization"])
    }

    @Test
    fun `getBytes keeps credentials on the trusted origin`() = runTest {
        val (http, engine) = http { json("bytes") }
        val auth = mapOf("authorization" to "Bearer secret")

        http.getBytes("https://api.example.com/f/1", auth, trustedOrigin = "https://api.example.com")

        assertEquals("Bearer secret", engine.requestHistory.single().headers["authorization"])
    }

    @Test
    fun `an oversized content-length is refused before the body is read`() = runTest {
        val (http, _) = http {
            respond(
                content = ByteReadChannel("12345678901234567890"),
                headers = headersOf("Content-Length", listOf("20")),
            )
        }

        // An unbounded read of a provider-controlled body is an out-of-memory kill on a phone, and the
        // number driving it is one we neither chose nor need to trust.
        assertFailsWith<DownloadError> { http.getBytes("https://cdn.example.com/f/1", maxBytes = 8) }
    }

    @Test
    fun `an oversized body with no content-length is refused after the read`() = runTest {
        val (http, _) = http { respond(content = ByteReadChannel("123456789")) }

        assertFailsWith<DownloadError> { http.getBytes("https://cdn.example.com/f/1", maxBytes = 8) }
    }

    @Test
    fun `a body within the cap comes back whole`() = runTest {
        val (http, _) = http { respond(content = ByteReadChannel("12345678")) }

        val result = http.getBytes("https://cdn.example.com/f/1", maxBytes = 8)

        assertEquals("12345678", result.value.decodeToString())
    }

    // -- request shape -------------------------------------------------------------------------------

    @Test
    fun `a multipart field may repeat, which a map could not express`() = runTest {
        val (http, engine) = http { json("""{"id":"t1"}""") }

        http.postMultipart(
            url = "https://api.example.com/v1/transcribe",
            fileField = "audio",
            fileName = "speech.wav",
            fileBytes = "RIFF".encodeToByteArray(),
            fileContentType = "audio/wav",
            fields = listOf("speech_models" to "best", "speech_models" to "nano"),
        )

        val sent = engine.requestHistory.single().body.toByteArrayText()
        assertContains(sent, "speech.wav")
        assertEquals(2, Regex("speech_models").findAll(sent).count())
    }

    @Test
    fun `postRawBytes declares the format in the content type`() = runTest {
        val (http, engine) = http { json("""{"text":"hi"}""") }

        http.postRawBytes("https://api.example.com/v1/listen", "RIFF".encodeToByteArray(), "audio/wav")

        // Ktor carries a declared content type on the body rather than in the header map.
        assertEquals("audio/wav", engine.requestHistory.single().body.contentType?.toString())
    }

    @Test
    fun `a response body that is not JSON raises rather than parsing to an empty object`() = runTest {
        val (http, _) = http { json("<html>502 Bad Gateway</html>") }

        assertFailsWith<com.sabreware.aide.aisdk.JsonParseError> {
            http.postJson("https://api.example.com/v1/chat", body)
        }
    }

    @Test
    fun `map carries the metadata onto a decoded payload`() {
        val result = HttpResult(
            value = JsonObject(emptyMap()),
            url = "https://api.example.com/v1/chat",
            statusCode = 200,
            headers = mapOf("x-request-id" to "req_1"),
            requestBody = "{}",
            timestamp = 5,
        )

        val mapped = result.map { "decoded" }

        assertEquals("decoded", mapped.value)
        assertEquals("req_1", mapped.responseInfo(modelId = "m").metadata.id)
        assertEquals("m", mapped.modalityResponse(modelId = "m").modelId)
        assertEquals("{}", mapped.requestInfo().body)
    }

    // --- byte cap and user agent ----------------------------------------------------------------

    @Test
    fun `a body over the cap fails without being buffered whole`() = runTest {
        // No Content-Length: the fast path cannot fire, so the running total must.
        val http = ProviderHttp(
            HttpClient(
                MockEngine {
                    respond(content = ByteReadChannel(ByteArray(64) { 1 }), headers = headersOf())
                },
            ),
        )

        val error = assertFailsWith<DownloadError> {
            http.getBytes("https://cdn.example/big.bin", maxBytes = 16)
        }
        assertContains(error.message!!, "exceeded")
    }

    @Test
    fun `every request carries the default user agent unless the caller sets one`() = runTest {
        var seen: String? = null
        val http = ProviderHttp(
            HttpClient(
                MockEngine { request ->
                    seen = request.headers["User-Agent"]
                    respond(content = "{}", headers = headersOf("Content-Type", "application/json"))
                },
            ),
        )

        http.postJson("https://api.example/v1/x", body)
        assertEquals(AISDK_USER_AGENT, seen)

        http.postJson("https://api.example/v1/x", body, headers = mapOf("user-agent" to "mine/1"))
        assertEquals("mine/1", seen)
    }

}

private suspend fun io.ktor.http.content.OutgoingContent.toByteArrayText(): String = when (this) {
    is io.ktor.http.content.OutgoingContent.ByteArrayContent -> bytes().decodeToString()
    is io.ktor.http.content.OutgoingContent.WriteChannelContent -> {
        val channel = ByteChannel(autoFlush = true)
        writeTo(channel)
        channel.close()
        channel.readRemainingText()
    }
    else -> error("unsupported content ${this::class.simpleName}")
}

private suspend fun io.ktor.utils.io.ByteReadChannel.readRemainingText(): String {
    val out = StringBuilder()
    val buffer = ByteArray(4096)
    while (true) {
        val read = readAvailable(buffer)
        if (read <= 0) break
        out.append(buffer.decodeToString(0, read))
    }
    return out.toString()
}
