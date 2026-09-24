package com.sabreware.aide.aisdk.providers.mistral

import com.sabreware.aide.aisdk.Usage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Mistral's token arithmetic.
 *
 * Two departures from the shared reading, and one of them is a spelling problem:
 *
 * - **The cache count has three possible names.** Mistral's own documentation shows exactly one —
 *   `prompt_tokens_details.cached_tokens` — and that is the one to get right. The other two,
 *   `num_cached_tokens` at the top level and `prompt_token_details` with a SINGULAR "token", are
 *   undocumented shapes the reference reads defensively. They are kept because a field that is not sent
 *   simply is not found, so reading three costs nothing, while reading only one of the undocumented
 *   spellings would silently report no caching at all.
 * - **Reasoning is not subtracted.** Mistral reports no reasoning token count, and `completion_tokens`
 *   is the whole output — so the text share is the completion count itself rather than a difference. The
 *   shared reading would report the same number here, but by coincidence rather than by rule.
 *
 * The uncached share is `prompt_tokens − cached_tokens`, which is what Mistral's own billing does:
 * "prompt_tokens contains all prompt tokens", with the cached portion billed at a tenth of the rate.
 *
 * A cache read of zero reads as ABSENT rather than as a reported zero — Mistral omits the field when
 * nothing was cached, and reporting a hard zero would claim the vendor said something it did not.
 */
internal fun mistralUsage(raw: JsonObject): Usage {
    val prompt = raw.intOrNull("prompt_tokens")
    val completion = raw.intOrNull("completion_tokens")
    val cacheRead = raw.intOrNull("num_cached_tokens")
        ?: (raw["prompt_tokens_details"] as? JsonObject)?.intOrNull("cached_tokens")
        ?: (raw["prompt_token_details"] as? JsonObject)?.intOrNull("cached_tokens")

    return Usage(
        inputTokens = Usage.InputTokens(
            total = prompt,
            noCache = prompt?.let { it - (cacheRead ?: 0) },
            cacheRead = cacheRead?.takeIf { it != 0 },
        ),
        outputTokens = Usage.OutputTokens(total = completion, text = completion),
        raw = raw,
    )
}

private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
