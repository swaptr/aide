package com.sabreware.aide.aisdk.providers.deepseek

import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Whether a DeepSeek failure is worth trying again.
 *
 * The message half needs nothing custom — DeepSeek uses OpenAI's `{"error":{"message":…}}` envelope,
 * which the shared reader already handles. What it cannot know is retryability, and DeepSeek's own
 * table draws the line in a place no status-code heuristic would guess: 402 "you have run out of
 * balance" is a 4xx that will fail identically forever, while 503 "the server is overloaded" clears on
 * its own (https://api-docs.deepseek.com/quick_start/error_codes).
 *
 * It matters most on the STREAM. A mid-generation refusal arrives inside an HTTP 200, so no status code
 * is available to judge it by — before this, every such frame was assumed retryable, which turns an
 * exhausted balance into a retry storm that fails the same way each time.
 */
internal val DeepSeekErrors: ProviderErrorStructure = ProviderErrorStructure(
    isRetryable = { statusCode, body -> deepSeekRetryable(statusCode, body) },
)

/**
 * The vendor's error code decides where one is given; the HTTP status decides otherwise.
 *
 * Null means "no opinion" and leaves the caller's default in place — the honest answer for a shape this
 * table does not recognize.
 */
private fun deepSeekRetryable(statusCode: Int, body: JsonElement?): Boolean? {
    val error = (body as? JsonObject)?.get("error") as? JsonObject
    val discriminators = listOfNotNull(
        error?.get("code")?.stringOrNull(),
        error?.get("type")?.stringOrNull(),
    )

    for (discriminator in discriminators) {
        // A numeric `code` is DeepSeek restating the HTTP status inside the frame, which is the only
        // status a streamed error carries at all.
        discriminator.toIntOrNull()?.takeIf { it in HTTP_ERROR_RANGE }?.let { return retryableStatus(it) }
        RETRYABLE_CODES[discriminator]?.let { return it }
    }

    return retryableStatus(statusCode)
}

/**
 * DeepSeek's documented error vocabulary, mapped to retryability alone.
 *
 * The reference synthesizes an HTTP status for each of these as well; that half is dropped because this
 * seam consumes only the boolean — inventing a status nobody reads would be a second thing to keep
 * right.
 */
private val RETRYABLE_CODES: Map<String, Boolean> = mapOf(
    // Out of balance. A 4xx that no amount of retrying fixes — the one this table exists for.
    "insufficient_quota" to false,
    "rate_limit_exceeded" to true,
    "rate_limit_error" to true,
    "server_error" to true,
    "api_error" to true,
    "internal_server_error" to true,
    "overloaded_error" to true,
    "service_unavailable" to true,
    "timeout" to true,
    "timeout_error" to true,
    "authentication_error" to false,
    "invalid_api_key" to false,
    "permission_error" to false,
    "not_found_error" to false,
    "model_not_found" to false,
    "bad_request" to false,
    "context_length_exceeded" to false,
    "invalid_request_error" to false,
)

/** 408 and 409 are transient by convention; 429 and 5xx are DeepSeek's own documented retry advice. */
private fun retryableStatus(statusCode: Int): Boolean? = when {
    statusCode == 408 || statusCode == 409 -> true
    statusCode == 429 -> true
    statusCode >= 500 -> true
    statusCode in HTTP_ERROR_RANGE -> false
    else -> null
}

private val HTTP_ERROR_RANGE = 400..599

private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.content
