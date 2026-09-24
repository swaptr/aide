package com.sabreware.aide.aisdk.providers.moonshotai

import com.sabreware.aide.aisdk.Usage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Moonshot's token accounting, which puts its cache counter somewhere the shared reader never looks.
 *
 * `cached_tokens` sits at the TOP level of `usage` — confirmed against the current API reference at
 * <https://platform.kimi.ai/docs/api/chat>, which documents it there and nowhere else. The shared
 * OpenAI reading looks only at `prompt_tokens_details.cached_tokens`, so every Moonshot call reported
 * no cache reads at all: not an error, just a zero, which is indistinguishable from a genuine cache
 * miss and quietly overstates what the call cost.
 *
 * The nested spelling is still read as a fallback, because Moonshot also emits the OpenAI-shaped
 * details object on some deployments and the reference reads both in that order.
 */
internal fun moonshotUsage(raw: JsonObject): Usage {
    val prompt = raw.intOrNull("prompt_tokens")
    val completion = raw.intOrNull("completion_tokens")
    val cacheRead = raw.intOrNull("cached_tokens")
        ?: (raw["prompt_tokens_details"] as? JsonObject)?.intOrNull("cached_tokens")
    val reasoning = (raw["completion_tokens_details"] as? JsonObject)?.intOrNull("reasoning_tokens")
    return Usage(
        inputTokens = Usage.InputTokens(
            total = prompt,
            noCache = prompt?.let { it - (cacheRead ?: 0) },
            cacheRead = cacheRead,
        ),
        outputTokens = Usage.OutputTokens(
            total = completion,
            reasoning = reasoning,
            // Clamped: a vendor whose two counters disagree should not report a negative text count.
            text = completion?.let { maxOf(0, it - (reasoning ?: 0)) },
        ),
        raw = raw,
    )
}

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
