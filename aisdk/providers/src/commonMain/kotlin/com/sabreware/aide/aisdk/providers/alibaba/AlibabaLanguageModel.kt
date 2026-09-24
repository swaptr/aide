package com.sabreware.aide.aisdk.providers.alibaba

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optInt
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Alibaba (Model Studio / DashScope) quirks, applied around the OpenAI-compatible model.
 *
 * Its chat endpoint speaks the OpenAI wire, so the wire model is reused; what it does NOT share is
 * everything the caller cares about when a request costs money or thinks:
 *
 * - **Explicit context caching.** A per-message `cache_control` marker selects it, and Alibaba honours
 *   at most four per request. The prompt is where they can be counted, so the warning is raised here —
 *   see [alibabaCacheBreakpoints]. The marker itself reaches the wire through the compat model's
 *   message-level option passthrough.
 * - **Its own token arithmetic**, because a cache WRITE is billable and both cache counters live inside
 *   `prompt_tokens` — see [alibabaUsage].
 * - **Thinking is a body field, not a sampler.** `enable_thinking` / `thinking_budget`, either passed
 *   explicitly or derived from the spec-level [CallOptions.reasoning].
 * - **`top_k` is real here.** The shared model gates it off by default because OpenAI answers 400 to an
 *   unrecognized parameter; Model Studio documents it as supported, so this provider turns it back on.
 * - **`frequency_penalty` is not in its schema** and is warned rather than sent.
 */
internal class AlibabaLanguageModel(private val delegate: LanguageModel) : LanguageModel {

    override val provider: String = ALIBABA_PROVIDER_ID

    override val modelId: String get() = delegate.modelId

    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        val prepared = prepare(options)
        val result = delegate.doGenerate(prepared.options)
        return result.copy(warnings = result.warnings + prepared.warnings)
    }

    override suspend fun doStream(options: CallOptions): StreamResult {
        val prepared = prepare(options)
        val result = delegate.doStream(prepared.options)
        return result.copy(
            stream = result.stream.map { part ->
                if (part is StreamPart.StreamStart) {
                    part.copy(warnings = part.warnings + prepared.warnings)
                } else {
                    part
                }
            },
        )
    }

    private data class Prepared(val options: CallOptions, val warnings: List<Warning>)

    private fun prepare(options: CallOptions): Prepared {
        val warnings = mutableListOf<Warning>()

        if (options.frequencyPenalty != null) {
            warnings += Warning.Unsupported(
                feature = "frequencyPenalty",
                details = "Model Studio's chat parameters do not include frequency_penalty.",
            )
        }

        val breakpoints = options.prompt.alibabaCacheBreakpoints()
        warnings += breakpoints.warnings

        val vendor = options.providerOptions?.get(ALIBABA_PROVIDER_ID)
        val thinking = resolveThinking(vendor, options.reasoning, warnings)
        // The camelCase names are this port's spelling of Alibaba's fields; only the translated
        // snake_case ones reach the wire, so the originals are dropped rather than spread twice.
        val passthrough = vendor.orEmpty().filterKeys { it !in TRANSLATED_KEYS }
        val parallelToolCalls = vendor?.optBoolean("parallelToolCalls")
            // Only sent alongside tools: the field has nothing to govern on a request offering none.
            ?.takeIf { !options.tools.isNullOrEmpty() }
        val body = JsonObject(
            passthrough + thinking +
                (parallelToolCalls?.let { mapOf("parallel_tool_calls" to JsonPrimitive(it)) } ?: emptyMap()),
        )

        return Prepared(
            options = options.copy(
                prompt = options.prompt.withAlibabaCacheControl(breakpoints.markers),
                frequencyPenalty = null,
                providerOptions = options.providerOptions.orEmpty() + (ALIBABA_PROVIDER_ID to body),
            ),
            warnings = warnings,
        )
    }

    /**
     * `enable_thinking` / `thinking_budget`, from the vendor options or from the neutral setting.
     *
     * An explicit vendor option wins outright — a caller who named Alibaba's own field means it. Failing
     * that, a custom [ReasoningEffort] is mapped onto a budget, which is how a caller who never learned
     * this vendor's spelling still gets reasoning. The ceiling is the reference's; Model Studio documents
     * the budget as defaulting to each model's own maximum rather than publishing one number, so this is
     * a safe clamp rather than a documented limit.
     */
    private fun resolveThinking(
        vendor: JsonObject?,
        reasoning: ReasoningEffort,
        warnings: MutableList<Warning>,
    ): Map<String, JsonPrimitive> {
        val enable = vendor?.optBoolean("enableThinking")
        val budget = vendor?.optInt("thinkingBudget")
        if (enable != null || budget != null) {
            return buildMap {
                enable?.let { put("enable_thinking", JsonPrimitive(it)) }
                budget?.let { put("thinking_budget", JsonPrimitive(it)) }
            }
        }

        return when (reasoning) {
            ReasoningEffort.ProviderDefault -> emptyMap()
            ReasoningEffort.None -> mapOf("enable_thinking" to JsonPrimitive(false))
            else -> {
                val mapped = reasoning.toThinkingBudget()
                if (mapped > MAX_THINKING_BUDGET) {
                    warnings += Warning.Compatibility(
                        feature = "reasoning",
                        details = "Clamped the thinking budget to $MAX_THINKING_BUDGET tokens.",
                    )
                }
                mapOf(
                    "enable_thinking" to JsonPrimitive(true),
                    "thinking_budget" to JsonPrimitive(minOf(mapped, MAX_THINKING_BUDGET)),
                )
            }
        }
    }

    private fun ReasoningEffort.toThinkingBudget(): Int = when (this) {
        ReasoningEffort.Minimal -> MAX_THINKING_BUDGET / 16
        ReasoningEffort.Low -> MAX_THINKING_BUDGET / 8
        ReasoningEffort.Medium -> MAX_THINKING_BUDGET / 4
        ReasoningEffort.High -> MAX_THINKING_BUDGET / 2
        else -> MAX_THINKING_BUDGET
    }

    private companion object {
        /** Read under this port's camelCase names, sent under Alibaba's — never spread as both. */
        val TRANSLATED_KEYS = setOf("enableThinking", "thinkingBudget", "parallelToolCalls")

        /**
         * The reference's ceiling, carried over.
         *
         * Not a documented vendor limit: Model Studio says the budget defaults to each model's own
         * maximum chain-of-thought length and points at the per-model console page. Clamping keeps a
         * caller's "think hard" from becoming a 400 on the models with smaller ceilings.
         */
        const val MAX_THINKING_BUDGET = 16384
    }
}
