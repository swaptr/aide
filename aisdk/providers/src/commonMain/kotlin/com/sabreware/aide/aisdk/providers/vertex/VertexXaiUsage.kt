package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.Usage
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Grok's token accounting on Vertex, which is NOT OpenAI's under the same field names.
 *
 * On the OpenAI wire `completion_tokens` INCLUDES the reasoning tokens that
 * `completion_tokens_details.reasoning_tokens` breaks out, so the shared reader
 * ([com.sabreware.aide.aisdk.providers.openaicompatible.defaultOpenAICompatibleUsage]) derives the
 * visible text as the difference. Grok on Vertex reports them SEPARATELY — Google's Grok reasoning
 * guide, verbatim: "The reasoning token count is outputted in the reasoning_tokens field, separated
 * from the completion_tokens"
 * (https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/partner-models/grok/capabilities/reasoning).
 * Its own documented example is 50 completion tokens beside 124 reasoning tokens; read with OpenAI's
 * arithmetic that is 50 output tokens in total and a negative text share clamped to zero, where the
 * truth is 174 out, of which 50 are the answer. The bill is for 174.
 *
 * This is the reference's `convertGoogleVertexXaiUsage`, its only `convertUsage` consumer — the seam
 * exists for exactly this: a vendor whose counters mean something different under names the shared
 * wire model already reads.
 *
 * One departure from the reference, which coerces every absent counter to zero: the top-level counts
 * stay null when the vendor did not send them, matching every other usage reader here. A block that
 * is absent still counts as zero in the ARITHMETIC — no `prompt_tokens_details` means nothing was
 * served from cache, and no `reasoning_tokens` means the non-reasoning variant spent none — so the
 * documented shape produces the reference's exact numbers.
 */
internal fun vertexXaiUsage(raw: JsonObject): Usage {
    val prompt = raw.intOrNull("prompt_tokens")
    val completion = raw.intOrNull("completion_tokens")
    val cached = (raw["prompt_tokens_details"] as? JsonObject)?.intOrNull("cached_tokens")
    val reasoning = (raw["completion_tokens_details"] as? JsonObject)?.intOrNull("reasoning_tokens")

    return Usage(
        inputTokens = Usage.InputTokens(
            total = prompt,
            noCache = prompt?.let { it - (cached ?: 0) },
            cacheRead = cached,
            // Vertex's Grok reports reads only; never-reported is not the same fact as reported-zero.
            cacheWrite = null,
        ),
        outputTokens = Usage.OutputTokens(
            // The two counters are disjoint here, so the total is their SUM — the whole difference.
            total = completion?.let { it + (reasoning ?: 0) },
            text = completion,
            reasoning = reasoning,
        ),
        raw = raw,
    )
}

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toIntOrNull()
