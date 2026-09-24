package com.sabreware.aide.aisdk.providers.alibaba

import com.sabreware.aide.aisdk.Usage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Alibaba's token arithmetic, which is the one place the shared reading is not merely incomplete but
 * wrong by construction.
 *
 * Two departures, both confirmed against Model Studio's own documentation:
 *
 * - **A cache WRITE is counted.** `prompt_tokens_details.cache_creation_input_tokens` is what explicit
 *   context caching charges for building the cache. The shared converter has no slot for it and reports
 *   null, so the one number that explains a bill spike is the one number a caller cannot see.
 * - **Both cache counters sit INSIDE `prompt_tokens`.** The docs say it outright — "This value is part
 *   of `usage.prompt_tokens`" — so the uncached share is `prompt − read − write`. Subtracting only the
 *   read, as the shared reading does, over-reports the tokens actually billed at full rate by exactly
 *   the size of the cache write.
 *
 * `cache_type` is read through to [Usage.raw] rather than modelled: it names which caching mode served
 * the request (explicit versus implicit), the two are priced differently, and it is the discriminator a
 * caller needs to attribute reads to a rate. It appears in the reference's schema but not in the public
 * documentation, so nothing here depends on its presence.
 */
internal fun alibabaUsage(raw: JsonObject): Usage {
    val prompt = raw.intOrNull("prompt_tokens")
    val details = raw["prompt_tokens_details"] as? JsonObject
    val cacheRead = details?.intOrNull("cached_tokens")
    val cacheWrite = details?.intOrNull("cache_creation_input_tokens")
    val completion = raw.intOrNull("completion_tokens")
    val reasoning = (raw["completion_tokens_details"] as? JsonObject)?.intOrNull("reasoning_tokens")

    return Usage(
        inputTokens = Usage.InputTokens(
            total = prompt,
            noCache = prompt?.let { it - (cacheRead ?: 0) - (cacheWrite ?: 0) },
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
        ),
        outputTokens = Usage.OutputTokens(
            total = completion,
            reasoning = reasoning,
            // Clamped, as everywhere: two vendor counters that disagree must not produce a negative.
            text = completion?.let { maxOf(0, it - (reasoning ?: 0)) },
        ),
        raw = raw,
    )
}

private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
