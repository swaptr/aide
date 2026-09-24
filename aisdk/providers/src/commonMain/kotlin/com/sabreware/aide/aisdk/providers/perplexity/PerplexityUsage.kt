package com.sabreware.aide.aisdk.providers.perplexity

import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optObject
import kotlinx.serialization.json.JsonObject

/**
 * The Agent API's usage block, which is OpenAI's Responses shape with Perplexity's cache names.
 *
 * The API reference (checked 2026-09-02) documents `input_tokens`, `output_tokens`, `total_tokens`,
 * `input_tokens_details.cache_read_input_tokens` / `cache_creation_input_tokens`, and a `cost` object.
 * The shared Responses reader looks for OpenAI's `cached_tokens` and would report every cache hit as a
 * miss, which is why the arithmetic is here.
 *
 * `input_tokens` is read as INCLUDING the cached share — the OpenAI convention on this wire — and the
 * no-cache count is what remains after the two cache counters, clamped at zero. The reference does not
 * say which way Perplexity counts; the clamp is what keeps a vendor that counts the other way from
 * driving the number negative on exactly the calls where caching worked. No reasoning split is
 * documented, so `output_tokens_details.reasoning_tokens` is read only if a model happens to send it.
 */
internal fun perplexityAgentUsage(raw: JsonObject?): Usage {
    if (raw == null) return Usage()
    val input = raw.optInt("input_tokens")
    val details = raw.optObject("input_tokens_details")
    val cacheRead = details?.optInt("cache_read_input_tokens")
    val cacheWrite = details?.optInt("cache_creation_input_tokens")
    val output = raw.optInt("output_tokens")
    val reasoning = raw.optObject("output_tokens_details")?.optInt("reasoning_tokens")
    return Usage(
        inputTokens = Usage.InputTokens(
            total = input,
            noCache = input?.let { maxOf(0, it - (cacheRead ?: 0) - (cacheWrite ?: 0)) },
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
        ),
        outputTokens = Usage.OutputTokens(
            total = output,
            text = output?.let { it - (reasoning ?: 0) },
            reasoning = reasoning,
        ),
        raw = raw,
    )
}

/**
 * `usage.cost`, verbatim.
 *
 * Perplexity is the one vendor here that prices a call in the response — `input_cost`, `output_cost`,
 * `tool_calls_cost`, the cache costs and `total_cost` in `currency` — and surfacing it is the
 * difference between a caller that can budget a research run and one that finds out monthly. Carried
 * as the vendor spells it rather than re-keyed: the object has grown two fields since the Sonar wire,
 * and a rename table is one more thing to be a release behind on.
 */
internal fun JsonObject?.perplexityCost(): JsonObject? = this?.optObject("cost")
