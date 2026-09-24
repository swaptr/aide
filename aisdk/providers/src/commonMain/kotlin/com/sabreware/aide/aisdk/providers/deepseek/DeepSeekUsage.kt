package com.sabreware.aide.aisdk.providers.deepseek

import com.sabreware.aide.aisdk.Usage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * DeepSeek's token accounting, which the shared reading gets silently wrong.
 *
 * DeepSeek does not use OpenAI's `prompt_tokens_details.cached_tokens`. It reports two flat counters —
 * `prompt_cache_hit_tokens` and `prompt_cache_miss_tokens` — documented as "the number of tokens in the
 * input of this request that resulted in a cache hit" and its complement
 * (https://api-docs.deepseek.com/guides/kv_cache, and both fields are listed on the usage object at
 * https://api-docs.deepseek.com/api/create-chat-completion).
 *
 * The shared reader looks for the nested OpenAI field, does not find it, and reports **no cache read at
 * all** — which is indistinguishable from a call that genuinely missed the cache. On the vendor whose
 * headline feature is a 10x price difference between hit and miss, that is a bill nobody can audit.
 *
 * The miss count is preferred over `prompt − hit` where the vendor sends it: it is the vendor's own
 * number for exactly this quantity, and subtracting silently invents one when the two counters do not
 * add up.
 */
internal fun deepSeekUsage(raw: JsonObject): Usage {
    val prompt = raw.intOrNull("prompt_tokens")
    val hit = raw.intOrNull("prompt_cache_hit_tokens")
    val miss = raw.intOrNull("prompt_cache_miss_tokens")
    val completion = raw.intOrNull("completion_tokens")
    val reasoning = (raw["completion_tokens_details"] as? JsonObject)?.intOrNull("reasoning_tokens")

    return Usage(
        inputTokens = Usage.InputTokens(
            total = prompt,
            noCache = miss ?: prompt?.let { it - (hit ?: 0) },
            cacheRead = hit,
            // DeepSeek's cache is written by the platform, not requested per call, so there is no
            // write-side counter to report. Left null rather than zero: never-reported and
            // reported-as-none are different facts.
            cacheWrite = null,
        ),
        outputTokens = Usage.OutputTokens(
            total = completion,
            reasoning = reasoning,
            // Clamped: a vendor whose two counters disagree must not produce a negative text count.
            text = completion?.let { maxOf(0, it - (reasoning ?: 0)) },
        ),
        raw = raw,
    )
}

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
