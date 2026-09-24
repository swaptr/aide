package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.ModalityResponse
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.ResponseInfo
import com.sabreware.aide.aisdk.ResponseMetadata
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A response, with the things a provider needs about it other than the payload.
 *
 * Returning the payload alone — which is what this transport used to do — leaves every provider with
 * nothing to build a `ResponseInfo` from, so a caller gets no request id for a vendor support ticket, no
 * rate-limit headers, and no `Retry-After`. All three are in the response and all three were discarded
 * one function short of the code that needed them.
 *
 * [headers] keys are LOWER-CASED. HTTP header names are case-insensitive and servers disagree in
 * practice — `Retry-After` and `retry-after` are the same header, and a map that preserves the server's
 * casing silently misses one of them at every lookup.
 */
public data class HttpResult<T>(
    /** The decoded payload. */
    val value: T,
    /** The URL that was called, for error messages and same-origin checks. */
    val url: String,
    /** The HTTP status. */
    val statusCode: Int,
    /** Response headers, keys lower-cased — see the class doc. */
    val headers: Map<String, String> = emptyMap(),
    /** The serialized request body, kept so a failed call can report what it sent. */
    val requestBody: String? = null,
    /** Epoch millis at which the response was received, where the caller supplied a clock. */
    val timestamp: Long? = null,
) {

    /** The vendor's own id for this response, under whichever of the usual header names it uses. */
    public val requestId: String?
        get() = headers["x-request-id"] ?: headers["request-id"] ?: headers["x-amzn-requestid"]

    /** The same envelope around a transformed payload. */
    public fun <R> map(transform: (T) -> R): HttpResult<R> = HttpResult(
        value = transform(value),
        url = url,
        statusCode = statusCode,
        headers = headers,
        requestBody = requestBody,
        timestamp = timestamp,
    )

    /** Response identity for the language-model modality. */
    public fun responseInfo(modelId: String? = null, id: String? = null, body: String? = null): ResponseInfo =
        ResponseInfo(
            metadata = ResponseMetadata(
                id = id ?: requestId,
                timestamp = timestamp,
                modelId = modelId,
            ),
            headers = headers,
            body = body,
        )

    /** Response identity for the non-chat modalities. */
    public fun modalityResponse(modelId: String? = null, id: String? = null, body: String? = null):
        ModalityResponse = ModalityResponse(
        modelId = modelId,
        timestamp = timestamp,
        id = id ?: requestId,
        headers = headers,
        body = body,
    )

    /** The request half of the envelope, for [com.sabreware.aide.aisdk.GenerateResult.request]. */
    public fun requestInfo(): RequestInfo = RequestInfo(body = requestBody)
}

/**
 * How long a server asked us to wait, in millis, or null if it did not.
 *
 * `retry-after-ms` is checked first and is a float count of milliseconds; `retry-after` is either whole
 * seconds or an HTTP date. Only the numeric forms are honoured — an HTTP-date needs a clock and a parser
 * this module deliberately does not depend on, and guessing at one is worse than falling back to the
 * computed backoff.
 */
public fun Map<String, String>.retryAfterMillis(maxMillis: Long = MAX_RETRY_AFTER_MS): Long? {
    val ms = this["retry-after-ms"]?.toDoubleOrNull()?.toLong()
    val seconds = this["retry-after"]?.trim()?.toDoubleOrNull()?.let { (it * MS_PER_SECOND).toLong() }
    val value = ms ?: seconds ?: return null
    return value.coerceIn(0, maxMillis)
}

private const val MS_PER_SECOND = 1000.0

/** An hour is already absurd; anything past it is a server telling us to give up, not to wait. */
public const val MAX_RETRY_AFTER_MS: Long = 60 * 60 * 1000

/**
 * How a vendor spells its errors.
 *
 * Every provider here used to produce the same `HTTP {code} from {url}: {first 500 chars}` for
 * everything, so `APICallError.data` was never populated, a provider could not mark its own
 * documented-retryable 4xx as retryable, and a caller had no structured code to switch on.
 *
 * The shapes genuinely differ and cannot be handled by one parser: Cerebras and Mistral are flat with no
 * `error` wrapper, xAI is a three-way union that includes a bare `{error: "string"}`, Baseten and
 * Fireworks allow `error` to be either a string or an object.
 */
public data class ProviderErrorStructure(
    /** Pulls a human-readable message out of the parsed body, or null if this shape does not match. */
    val extractMessage: (JsonElement) -> String? = ::defaultErrorMessage,
    /** Lets a vendor override retryability for a status it documents as transient. */
    val isRetryable: (statusCode: Int, body: JsonElement?) -> Boolean? = { _, _ -> null },
) {

    public companion object {
        /** The OpenAI-convention shapes via [defaultErrorMessage], and the standard retryability rule. */
        public val Default: ProviderErrorStructure = ProviderErrorStructure()
    }
}

/**
 * The union of the message shapes seen across the ported vendors.
 *
 * Ordered from most to least specific. `error.message` is the OpenAI convention most vendors copied;
 * a bare `error` string is xAI, Baseten and Fireworks; `message` and `detail` alone are the flat
 * shapes (Cerebras, Mistral, and several of the media vendors).
 */
public fun defaultErrorMessage(body: JsonElement): String? {
    val obj = body as? JsonObject ?: return null
    val error = obj["error"]
    val fromError = when (error) {
        is JsonObject -> error.stringOrNull("message") ?: error.stringOrNull("detail")
        else -> error?.stringValueOrNull()
    }
    return fromError
        ?: obj.stringOrNull("message")
        ?: obj.stringOrNull("detail")
        ?: obj.stringOrNull("error_message")
        ?: (obj["detail"] as? JsonObject)?.stringOrNull("message")
}

private fun JsonObject.stringOrNull(key: String): String? = this[key]?.stringValueOrNull()

private fun JsonElement.stringValueOrNull(): String? =
    runCatching { jsonPrimitive }.getOrNull()?.takeIf { it.isString }?.content

/**
 * Builds the error for a non-2xx response, using [structure] to find the vendor's own message.
 *
 * The parsed body is attached to [APICallError.data] rather than only excerpted into the message, so a
 * caller can switch on a vendor error code instead of pattern-matching English.
 */
public fun apiCallError(
    url: String,
    statusCode: Int,
    responseBody: String?,
    responseHeaders: Map<String, String>,
    requestBody: String?,
    structure: ProviderErrorStructure = ProviderErrorStructure.Default,
): APICallError {
    val parsed = responseBody?.let { parseJsonElementOrNull(it) }
    val vendorMessage = parsed?.let(structure.extractMessage)
    val excerpt = vendorMessage ?: responseBody?.take(ERROR_EXCERPT)
    return APICallError(
        message = "HTTP $statusCode from $url" + (excerpt?.let { ": $it" } ?: ""),
        url = url,
        requestBodyValues = requestBody,
        statusCode = statusCode,
        responseHeaders = responseHeaders,
        responseBody = responseBody,
        isRetryable = structure.isRetryable(statusCode, parsed)
            ?: APICallError.defaultIsRetryable(statusCode),
        data = parsed,
    )
}

private const val ERROR_EXCERPT = 500
