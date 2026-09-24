package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.Warning
import kotlin.math.roundToInt

/**
 * The level's name on the wire, and in a warning a user reads.
 *
 * Spelled out rather than derived from `name.lowercase()`, which would turn [ReasoningEffort.XHigh]
 * into `providerdefault`-shaped mush for the two hyphenated cases and leave every vendor that already
 * speaks `xhigh` matching on a string we invented.
 */
public val ReasoningEffort.wireName: String
    get() = when (this) {
        ReasoningEffort.ProviderDefault -> "provider-default"
        ReasoningEffort.None -> "none"
        ReasoningEffort.Minimal -> "minimal"
        ReasoningEffort.Low -> "low"
        ReasoningEffort.Medium -> "medium"
        ReasoningEffort.High -> "high"
        ReasoningEffort.XHigh -> "xhigh"
    }

/**
 * Whether the caller asked for a specific depth, as opposed to leaving it to the model.
 *
 * [ReasoningEffort.None] counts: "do not think" is a request a provider transmits, not an absence of
 * one. Only [ReasoningEffort.ProviderDefault] means send nothing.
 */
public val ReasoningEffort.isExplicit: Boolean
    get() = this != ReasoningEffort.ProviderDefault

/**
 * Maps a requested depth onto the effort levels one model actually accepts, saying so when it cannot.
 *
 * `CallOptions.reasoning` documents that "a provider that cannot honour a level says so with a
 * [Warning.Unsupported] rather than silently substituting one". Every provider hand-rolled a clamp
 * instead and emitted nothing, so `XHigh` became `high`, `Minimal` became `low`, and a model that takes
 * no effort at all dropped it — three different silent lies, each of which presents to the user as "the
 * depth control does nothing".
 *
 * [effortMap] is the model's own vocabulary: a level absent from it is one this model does not have.
 * That is a per-model fact, not a per-provider one — Opus 4.5 takes effort and Sonnet 4.5 does not, on
 * the same API — which is why the map is a parameter rather than a table in here.
 *
 * Warnings are appended to [warnings] rather than returned, so a provider's existing
 * `warnings = mutableListOf<Warning>()` accumulator is the one place they collect.
 *
 * [ReasoningEffort.ProviderDefault] is not a level and must not reach here — check [isExplicit] first,
 * or the caller who asked for nothing in particular is warned that nothing in particular is unsupported.
 * [T]'s `toString` is compared against the level's wire spelling to decide whether a substitution
 * happened, so the map's values are the vendor's own strings, not a type whose printed form differs.
 *
 * @return the model's own spelling of the level, or null when it has none.
 */
public fun <T : Any> mapReasoningToEffort(
    reasoning: ReasoningEffort,
    effortMap: Map<ReasoningEffort, T>,
    warnings: MutableList<Warning>,
): T? {
    val mapped = effortMap[reasoning]
    if (mapped == null) {
        warnings += Warning.Unsupported(
            feature = REASONING_FEATURE,
            details = "reasoning \"${reasoning.wireName}\" is not supported by this model.",
        )
        return null
    }
    // Substituting a neighbouring level is legitimate — it is substituting it in silence that is not.
    if (mapped.toString() != reasoning.wireName) {
        warnings += Warning.Compatibility(
            feature = REASONING_FEATURE,
            details = "reasoning \"${reasoning.wireName}\" is not directly supported by this model. " +
                "mapped to effort \"$mapped\".",
        )
    }
    return mapped
}

/**
 * Maps a requested depth onto an absolute thinking-token budget.
 *
 * The other half of the same failure. Providers substituted a constant `maxOutputTokens / 2`, which
 * makes [ReasoningEffort.Low] and [ReasoningEffort.High] produce an identical budget — so on every
 * extended-thinking model the depth control is a boolean wearing five names.
 *
 * The budget is a fraction of what the model may emit in total, because that is the resource it comes
 * out of: thinking tokens count against `max_tokens`, so a budget stated in absolute terms is either
 * impossible on a short call or trivial on a long one. [minReasoningBudget] is the vendor floor below
 * which thinking is refused outright, and [maxReasoningBudget] the model's own ceiling; the floor wins
 * a conflict, since a budget under the floor is rejected by the API rather than merely small.
 *
 * @return the budget in tokens, or null when this model has no budget for the requested level.
 */
public fun mapReasoningToBudget(
    reasoning: ReasoningEffort,
    maxOutputTokens: Int,
    maxReasoningBudget: Int,
    warnings: MutableList<Warning>,
    minReasoningBudget: Int = DEFAULT_MIN_REASONING_BUDGET,
    budgetPercentages: Map<ReasoningEffort, Double> = DefaultReasoningBudgetPercentages,
): Int? {
    val percentage = budgetPercentages[reasoning]
    if (percentage == null) {
        warnings += Warning.Unsupported(
            feature = REASONING_FEATURE,
            details = "reasoning \"${reasoning.wireName}\" is not supported by this model.",
        )
        return null
    }
    return minOf(maxReasoningBudget, maxOf(minReasoningBudget, (maxOutputTokens * percentage).roundToInt()))
}

/**
 * What fraction of the output budget each level spends on thinking.
 *
 * The reference's numbers, kept rather than re-derived: they are calibrated against the models, and a
 * provider that wants its own curve passes one.
 *
 * [ReasoningEffort.None] is deliberately absent. "Do not think" is expressed by the vendor's own off
 * switch — Anthropic's `thinking: {type: "disabled"}`, a missing `thinkingConfig` on Gemini — never by a
 * budget of zero, which several APIs reject outright. A provider resolves [ReasoningEffort.None] before
 * asking for a budget.
 */
public val DefaultReasoningBudgetPercentages: Map<ReasoningEffort, Double> = mapOf(
    ReasoningEffort.Minimal to 0.02,
    ReasoningEffort.Low to 0.1,
    ReasoningEffort.Medium to 0.3,
    ReasoningEffort.High to 0.6,
    ReasoningEffort.XHigh to 0.9,
)

/** Anthropic's floor, and the lowest any vendor documents. Below it the request is rejected. */
public const val DEFAULT_MIN_REASONING_BUDGET: Int = 1024

private const val REASONING_FEATURE = "reasoning"
