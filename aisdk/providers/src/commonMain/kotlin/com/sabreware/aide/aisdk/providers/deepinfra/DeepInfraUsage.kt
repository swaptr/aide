package com.sabreware.aide.aisdk.providers.deepinfra

import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.providers.openaicompatible.defaultOpenAICompatibleUsage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * DeepInfra's token counting, with one repair for the families that report it inconsistently.
 *
 * The OpenAI convention is that `completion_tokens` INCLUDES the reasoning tokens, so the text count is
 * the difference. On DeepInfra's Gemini and Gemma deployments the two are reported side by side instead,
 * and subtracting then drives the text count to zero on exactly the turns where the model reasoned most.
 *
 * The repair is keyed on the ARITHMETIC, not on the model id: when the reasoning count exceeds the
 * completion count, the two cannot be nested, so they are added. That is deliberately narrower than a
 * family check — an id list goes stale every time a vendor adds a model, and the inequality is the thing
 * actually being detected. When the numbers are consistent, or when either is absent, this is exactly the
 * shared reading.
 *
 * **Docs status:** DeepInfra's public documentation (`docs.deepinfra.com/chat/overview`, checked
 * 2026-09-01) documents `prompt_tokens`, `completion_tokens`, `total_tokens` and `estimated_cost`, and
 * says nothing about `reasoning_tokens` or this inconsistency. So this is an observed-behaviour guard
 * rather than a documented contract — which is why it is written to be a no-op whenever the counters
 * agree, instead of assuming the quirk applies.
 */
internal fun deepInfraUsage(raw: JsonObject): Usage {
    val shared = defaultOpenAICompatibleUsage(raw)
    val completion = raw.intOrNull("completion_tokens") ?: return shared
    val reasoning = (raw["completion_tokens_details"] as? JsonObject)?.intOrNull("reasoning_tokens")
        ?: return shared
    if (reasoning <= completion) return shared

    // The two are siblings here, not nested: the answer really was `completion` tokens long, and the
    // total the caller is billed for is the sum.
    return shared.copy(
        outputTokens = Usage.OutputTokens(
            total = completion + reasoning,
            reasoning = reasoning,
            text = completion,
        ),
    )
}

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
