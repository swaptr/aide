package com.sabreware.aide.aisdk.providers.testing

import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.RetryPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One MockEngine harness for every provider test in this module.
 *
 * Before this, six test files each kept a private `var lastRequest` closure. That shape can only see the
 * LAST request, cannot see a query parameter or a multipart field, and returns the same response to
 * every call — so a retry, a poll loop and a second tool round were all unassertable, and an entire class
 * of defect (wrong auth header, missing query param, wrong multipart part name) was invisible by
 * construction. The Azure bug in the audit is exactly that class.
 *
 * The reference's `createTestServer` is used by 129 of its test files and exposes precisely these
 * affordances; this is that, in Kotlin.
 *
 * ```kotlin
 * val server = TestServer(TestServer.sse("data: {...}\n\n"))
 * val model = SomeModel(http = server.http())
 * // ...
 * server.request().assertBodyJson { assertEquals("gpt-4o", it["model"].string()) }
 * ```
 */
public class TestServer(private vararg val responses: TestResponse) {

    private val recorded = mutableListOf<RecordedCall>()

    /** Every call the client made, in order. */
    public val calls: List<RecordedCall> get() = recorded.toList()

    public val callCount: Int get() = recorded.size

    /** The request at [index], failing with a useful message rather than an index error. */
    public fun request(index: Int = 0): RecordedCall {
        assertTrue(
            index < recorded.size,
            "Expected at least ${index + 1} request(s), but ${recorded.size} were made",
        )
        return recorded[index]
    }

    /** The last request, for the single-call case. */
    public fun lastRequest(): RecordedCall = request(recorded.size - 1)

    public fun engine(): MockEngine = MockEngine { request ->
        val index = recorded.size
        recorded += RecordedCall.of(request)
        // Past the end, the last response repeats. A poll loop of unknown length is the normal case, and
        // making the test declare the exact count turns a timing detail into a broken assertion.
        val response = responses.getOrNull(index) ?: responses.lastOrNull()
            ?: TestResponse.json("{}")
        respond(
            content = response.channel(),
            status = response.status,
            headers = response.headers,
        )
    }

    /**
     * A transport over this server.
     *
     * Retry is OFF by default: a test asserting a request body would otherwise see the same request
     * three times on a 5xx fixture, and a test asserting a FAILURE would take six seconds of real
     * backoff. Retry behaviour gets its own tests, which opt in.
     */
    public fun http(
        retryPolicy: RetryPolicy = RetryPolicy.None,
        now: () -> Long = { FIXED_NOW },
    ): ProviderHttp = ProviderHttp(HttpClient(engine()), retryPolicy = retryPolicy, now = now)

    public companion object {

        /** A pinned clock, so a timestamp assertion is a value rather than a range. */
        public const val FIXED_NOW: Long = 1_700_000_000_000

        public fun sse(vararg chunks: String): TestResponse = TestResponse(
            body = chunks.joinToString(""),
            headers = headersOf(HttpHeaders.ContentType, listOf("text/event-stream")),
        )

        public fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK): TestResponse =
            TestResponse.json(body, status)

        public fun bytes(body: ByteArray, contentType: String = "application/octet-stream"): TestResponse =
            TestResponse(
                bytes = body,
                headers = headersOf(HttpHeaders.ContentType, listOf(contentType)),
            )

        public fun error(status: Int, body: String = "{}"): TestResponse =
            TestResponse.json(body, HttpStatusCode.fromValue(status))
    }
}

/** One canned response. */
public class TestResponse(
    body: String? = null,
    bytes: ByteArray? = null,
    public val status: HttpStatusCode = HttpStatusCode.OK,
    public val headers: io.ktor.http.Headers = headersOf(),
) {

    private val payload: ByteArray = bytes ?: (body ?: "").encodeToByteArray()

    internal fun channel(): ByteReadChannel = ByteReadChannel(payload)

    /** The same response with extra headers — a `retry-after`, a request id, a rate-limit counter. */
    public fun withHeaders(vararg pairs: Pair<String, String>): TestResponse = TestResponse(
        bytes = payload,
        status = status,
        headers = io.ktor.http.Headers.build {
            appendAll(headers)
            pairs.forEach { (k, v) -> append(k, v) }
        },
    )

    public companion object {
        public fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK): TestResponse =
            TestResponse(
                body = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, listOf("application/json")),
            )
    }
}

/**
 * One request as the client actually sent it.
 *
 * Everything a provider can get wrong is readable here: the URL it built, the query it appended, the
 * headers it merged, and the body — as JSON, as a multipart field map, or as raw text.
 */
public class RecordedCall(
    public val method: String,
    public val url: String,
    public val headers: Map<String, String>,
    public val bodyText: String,
    public val multipart: Map<String, String>,
    /**
     * The FILE parts, by field name.
     *
     * Separate from [multipart] because the two are asserted about differently: a value part is checked
     * for its value, a file part for the name and content type the vendor keys its decoder off. Folding
     * a file into the value map hides both — which is why "the audio was uploaded under the field this
     * vendor reads" was previously unassertable.
     */
    public val multipartFiles: Map<String, MultipartFile> = emptyMap(),
) {

    /**
     * Query parameters, percent-decoded.
     *
     * Decoded properly rather than by un-escaping the one character that happened to come up: an
     * assertion written against `https%3A%2F%2Fexample.com` reads as a test about escaping rather than
     * about the callback URL, and every reader has to re-derive which characters the harness un-escapes
     * and which it leaves. `getAll` is beside it because several vendors repeat a parameter — a comma
     * would be part of the term, not a separator — and a map keeps only the last one.
     */
    public val query: Map<String, String> by lazy { queryPairs.toMap() }

    /** Every occurrence of [name], in order. Repeated parameters are how keyterm lists travel. */
    public fun queryAll(name: String): List<String> =
        queryPairs.filter { it.first == name }.map { it.second }

    private val queryPairs: List<Pair<String, String>> by lazy {
        url.substringAfter('?', missingDelimiterValue = "")
            .split('&')
            .filter { it.isNotBlank() }
            .map { percentDecode(it.substringBefore('=')) to percentDecode(it.substringAfter('=', "")) }
    }

    /** The path, without scheme, host or query. */
    public val path: String get() = url.substringAfter("://").substringAfter('/', "").substringBefore('?')

    public fun bodyJson(): JsonObject = com.sabreware.aide.aisdk.util.parseJsonObject(bodyText)

    /** A header, looked up case-insensitively — which is how HTTP header names actually work. */
    public fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /**
     * Asserts on the WHOLE request body.
     *
     * Reading one key at a time is why a missing Cartesia `bit_rate`, a missing Hume `voice.provider`
     * and an absent AssemblyAI `speech_models` were all green: a key that is never read is a key whose
     * absence no assertion can see.
     */
    public fun assertBodyJson(block: (JsonObject) -> Unit) {
        block(bodyJson())
    }

    /**
     * Asserts the WHOLE request body equals [expected], parsed as JSON.
     *
     * The reference's own image and video tests assert `toStrictEqual` on the whole body, and that is the
     * assertion that sees an EXTRA key as well as a missing one. A per-key read cannot: it is blind to
     * every field the test did not think to name, which is how a provider that quietly sends a stale
     * parameter stays green.
     */
    public fun assertBodyEquals(expected: String) {
        assertEquals(
            com.sabreware.aide.aisdk.util.parseJsonObject(expected),
            bodyJson(),
            "request body",
        )
    }

    /** Asserts the body has exactly [keys] at the top level — nothing extra, nothing missing. */
    public fun assertBodyKeys(vararg keys: String) {
        assertEquals(keys.toSortedSet(), bodyJson().keys.toSortedSet(), "request body keys")
    }

    public fun assertHeader(name: String, value: String) {
        assertEquals(value, header(name), "header $name")
    }

    public fun assertNoHeader(name: String) {
        assertEquals(null, header(name), "header $name should be absent")
    }

    /** Asserts a key is absent, which is how "we correctly omitted this" is stated. */
    public fun assertBodyMissing(key: String) {
        assertTrue(key !in bodyJson(), "expected '$key' to be absent from the request body")
    }

    public fun assertMultipartField(name: String, value: String) {
        assertEquals(value, multipart[name], "multipart field $name")
    }

    /** Asserts a file part was sent under [name], with the filename and content type given. */
    public fun assertMultipartFile(name: String, fileName: String? = null, contentType: String? = null) {
        val file = multipartFiles[name]
        assertNotNull(
            file,
            "expected a file part named '$name', got file parts ${multipartFiles.keys} " +
                "and value parts ${multipart.keys}",
        )
        if (fileName != null) assertEquals(fileName, file.fileName, "filename of part $name")
        if (contentType != null) assertEquals(contentType, file.contentType, "content type of part $name")
    }

    internal companion object {
        /**
         * Reads the body whatever shape it was written in.
         *
         * A multipart upload is a `WriteChannelContent`, so a `when` over `TextContent` and
         * `ByteArrayContent` alone returns "" for it — and every `assertMultipartField` then compares
         * against null and passes for a field that was never sent. That is the exact bug class this
         * harness exists to catch, so the read goes through the mock engine's own drain.
         */
        suspend fun of(request: HttpRequestData): RecordedCall {
            val text = runCatching { request.body.toByteArray().decodeToString() }.getOrDefault("")
            return RecordedCall(
                method = request.method.value,
                url = request.url.toString(),
                // Ktor keeps the body's content type on the body, not in the header list, so a request
                // whose ONLY interesting header is `Content-Type` — every raw-bytes upload — read back
                // as having no content type at all, and an assertion about it compared null to null.
                headers = request.headers.entries()
                    .associate { it.key to it.value.joinToString(",") }
                    .let { headers ->
                        val contentType = request.body.contentType?.toString()
                        if (contentType == null || headers.keys.any {
                                it.equals(HttpHeaders.ContentType, ignoreCase = true)
                            }
                        ) {
                            headers
                        } else {
                            headers + (HttpHeaders.ContentType to contentType)
                        }
                    },
                bodyText = text,
                multipart = parseMultipart(text),
                multipartFiles = parseMultipartFiles(text),
            )
        }

        /**
         * Pulls simple `name -> value` pairs out of a multipart body.
         *
         * Deliberately naive: it exists so a test can assert that a field was SENT and with what value,
         * which is the whole bug class (Rev AI never sending `config`, Fish Audio never sending
         * `ignore_timestamps`). Binary parts are skipped rather than decoded.
         */
        /** The file parts, which [parseMultipart] deliberately skips. */
        private fun parseMultipartFiles(text: String): Map<String, MultipartFile> {
            if (!text.contains("Content-Disposition")) return emptyMap()
            val out = mutableMapOf<String, MultipartFile>()
            for (block in text.split(Regex("--[-A-Za-z0-9]+(--)?\r?\n"))) {
                val name = Regex("name=\"([^\"]+)\"").find(block) ?: continue
                val fileName = Regex("filename=\"([^\"]*)\"").find(block) ?: continue
                out[name.groupValues[1]] = MultipartFile(
                    fieldName = name.groupValues[1],
                    fileName = fileName.groupValues[1],
                    contentType = Regex("(?i)Content-Type:\\s*([^\r\n]+)").find(block)
                        ?.groupValues?.get(1)?.trim(),
                )
            }
            return out
        }

        private fun parseMultipart(text: String): Map<String, String> {
            if (!text.contains("Content-Disposition")) return emptyMap()
            val out = mutableMapOf<String, String>()
            val blocks = text.split(Regex("--[-A-Za-z0-9]+(--)?\r?\n"))
            for (block in blocks) {
                val nameMatch = Regex("name=\"([^\"]+)\"").find(block) ?: continue
                if (block.contains("filename=")) continue
                val value = block.substringAfter("\r\n\r\n", missingDelimiterValue = "")
                    .substringBeforeLast("\r\n")
                out[nameMatch.groupValues[1]] = value.trim()
            }
            return out
        }
    }
}

/**
 * `%XX` and `+`, decoded as UTF-8.
 *
 * Written out rather than borrowed from Ktor because a query value is bytes: decoding per character
 * mangles anything outside ASCII, and a vendor parameter carrying a non-English keyterm is exactly the
 * case a test would otherwise assert the mangled form of.
 */
private fun percentDecode(value: String): String {
    if ('%' !in value && '+' !in value) return value
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        when {
            value[index] == '%' && index + 2 < value.length -> {
                val hex = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (hex == null) {
                    bytes += value[index].code.toByte()
                    index++
                } else {
                    bytes += hex.toByte()
                    index += 3
                }
            }

            value[index] == '+' -> {
                bytes += ' '.code.toByte()
                index++
            }

            else -> {
                value.substring(index, index + 1).encodeToByteArray().forEach { bytes += it }
                index++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}

/** One file part of a multipart upload — everything about it except its bytes. */
public class MultipartFile(
    public val fieldName: String,
    public val fileName: String,
    public val contentType: String?,
)

// ---------------------------------------------------------------------------------------------------
// JSON reading, so an assertion reads like the wire it is checking
// ---------------------------------------------------------------------------------------------------

public fun JsonElement?.string(): String? = this?.runCatching { jsonPrimitive.content }?.getOrNull()

public fun JsonElement?.int(): Int? = this?.runCatching { jsonPrimitive.content.toInt() }?.getOrNull()

public fun JsonElement?.double(): Double? =
    this?.runCatching { jsonPrimitive.content.toDouble() }?.getOrNull()

public fun JsonElement?.bool(): Boolean? =
    this?.runCatching { jsonPrimitive.content.toBooleanStrict() }?.getOrNull()

public fun JsonObject.obj(vararg path: String): JsonObject? {
    var current: JsonObject? = this
    for (key in path) current = current?.get(key)?.runCatching { jsonObject }?.getOrNull()
    return current
}

public fun JsonObject.arr(key: String): List<JsonElement>? =
    this[key]?.runCatching { jsonArray }?.getOrNull()

// ---------------------------------------------------------------------------------------------------
// Warning assertions
//
// Every warning assertion in this module used to be `toString().contains("<one word>")`, which passes
// for any variant with any details. That is why all four `Unsupported`-should-be-`Compatibility` swaps
// in the audit were invisible: the assertion could not see the variant it was asserting about.
// ---------------------------------------------------------------------------------------------------

public fun List<Warning>.assertUnsupported(feature: String, details: String? = null) {
    val match = filterIsInstance<Warning.Unsupported>().firstOrNull { it.feature == feature }
    assertNotNull(match, "expected an Unsupported warning for '$feature', got: $this")
    if (details != null) assertEquals(details, match.details, "details of Unsupported($feature)")
}

public fun List<Warning>.assertCompatibility(feature: String, details: String? = null) {
    val match = filterIsInstance<Warning.Compatibility>().firstOrNull { it.feature == feature }
    assertNotNull(match, "expected a Compatibility warning for '$feature', got: $this")
    if (details != null) assertEquals(details, match.details, "details of Compatibility($feature)")
}

/** Asserts NOTHING warned about [feature] — the assertion a fixed false warning needs. */
public fun List<Warning>.assertNoWarningAbout(feature: String) {
    val offenders = filter {
        when (it) {
            is Warning.Unsupported -> it.feature == feature
            is Warning.Compatibility -> it.feature == feature
            is Warning.Deprecated -> it.setting == feature
            is Warning.Other -> it.message.contains(feature)
        }
    }
    assertTrue(offenders.isEmpty(), "expected no warning about '$feature', got: $offenders")
}

public fun List<Warning>.assertNoWarnings() {
    assertTrue(isEmpty(), "expected no warnings, got: $this")
}
