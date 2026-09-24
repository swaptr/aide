package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.Warning
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/** The provider id, and the key every Anthropic payload is namespaced under in `providerMetadata`. */
public const val ANTHROPIC_PROVIDER_ID: String = "anthropic"

/**
 * The opaque proof that Claude authored a thinking block.
 *
 * A replayed assistant turn must carry this back byte-for-byte or the next request is rejected with
 * `Invalid signature in thinking block`. It is the single value whose loss motivated this whole port.
 */
public const val ANTHROPIC_SIGNATURE_KEY: String = "signature"

/**
 * A safety-redacted thinking block's encrypted payload.
 *
 * Arrives complete, with no deltas, and must be replayed in ARRIVAL ORDER among the other blocks.
 */
public const val ANTHROPIC_REDACTED_KEY: String = "redactedData"

/**
 * A content block this library does not model, kept whole so it can be replayed whole.
 *
 * The alternative is to drop it, and a dropped block is a turn Anthropic will not accept back: the API
 * validates that a replayed assistant turn matches what it produced.
 */
public const val ANTHROPIC_BLOCK_KEY: String = "block"

/** Which `*_tool_result` block a provider-executed result arrived as, so the replay can reproduce it. */
public const val ANTHROPIC_BLOCK_TYPE_KEY: String = "blockType"

/** The documented `output_config.effort` vocabulary. */
internal val AnthropicEffortLevels = setOf("low", "medium", "high", "xhigh", "max")

/** Which thinking protocol a model speaks. */
public enum class AnthropicThinkingMode {
    /** ≤4.5 (opus-4-5, sonnet-4-5, haiku-4-5, 4.1, 4.0, 3.x): `enabled` + budget only; rejects `adaptive`. */
    Extended,

    /** 4.6+ (opus/sonnet 4.6–5, opus 4.7/4.8): adaptive; `disabled` accepted. */
    Adaptive,

    /** fable-5 / mythos-5 / mythos-preview: adaptive, and thinking cannot be turned off (`disabled` 400s). */
    AdaptiveAlwaysOn,
}

/**
 * Mode from the model id, read off the same capability table as everything else.
 *
 * It used to be a second classifier — first a regex over the id's version digits, then a substring test
 * for `fable`/`mythos` — which is one model-family table too many: the two eventually disagree about a
 * model, and the disagreement presents as a request the API rejects. The substring version did exactly
 * that, sorting **Mythos Preview** in with the adaptive-only families when the vendor documents it as
 * "Adaptive, extended". Keying on the capability table means a new variant is classified by the row
 * someone deliberately wrote for it, not by whether its name happens to contain a word.
 */
public fun anthropicThinkingMode(modelId: String): AnthropicThinkingMode {
    val caps = anthropicModelCapabilities(modelId)
    return when {
        !caps.supportsAdaptiveThinking -> AnthropicThinkingMode.Extended
        caps.rejectsDisabledThinking -> AnthropicThinkingMode.AdaptiveAlwaysOn
        else -> AnthropicThinkingMode.Adaptive
    }
}

/**
 * Resolves the `thinking` object, the effort and `max_tokens` together, because they constrain each other.
 *
 * Thinking needs room, and Anthropic bills it out of `max_tokens`. The rule the reference follows and
 * this used to get backwards: the budget is ADDED to what the caller asked for, never taken out of it.
 * Halving `max_tokens` to make room — which is what this did — silently cut every answer in half, and
 * the caller's own limit is the one number in the request it is entitled to trust.
 */
internal fun resolveThinking(
    modelId: String,
    caps: AnthropicModelCapabilities,
    requested: ReasoningEffort,
    requestedMaxTokens: Int?,
    options: AnthropicOptions,
    warnings: MutableList<Warning>,
): ResolvedThinking {
    val mode = anthropicThinkingMode(modelId)
    val extended = mode == AnthropicThinkingMode.Extended

    // An explicit provider option is the caller being specific about this vendor; the neutral effort is
    // the caller being general. The specific one wins, or `providerOptions` would be advisory.
    val explicit = options.thinking
    // `blockBinding` alone is a documented request — a recovery turn that only says how a mismatched
    // thinking prefix is handled — and the reference sends it with no `type`. Deriving one from the
    // neutral effort would turn that request into a thinking one the caller never asked for.
    val blockBinding = (explicit?.get("blockBinding") as? JsonObject)?.stringOrNull("prefixMismatchBehavior")
    val type = explicit?.stringOrNull("type")
        ?: if (blockBinding != null) null else derivedThinkingType(mode, requested)
    val active = type == "enabled" || type == "adaptive"

    var budget = when {
        type != "enabled" -> null
        explicit != null -> explicit["budgetTokens"]?.let {
            runCatching { it.jsonPrimitive.intOrNull }.getOrNull()
        }
        else -> extendedBudget(requested, caps, warnings)
    }
    if (type == "enabled" && budget == null) {
        warnings += Warning.Compatibility(
            feature = "extended thinking",
            details = "thinking budget is required when thinking is enabled. " +
                "using default budget of $MIN_THINKING_BUDGET tokens.",
        )
        budget = MIN_THINKING_BUDGET
    }

    // `display` belongs to adaptive thinking only. Extended thinking always returns its trace, and the
    // field is not part of that request shape.
    val display = if (type == "adaptive") explicit?.stringOrNull("display") ?: "summarized" else null
    val thinking = if (type != null || blockBinding != null) {
        AnthropicThinking(
            type = type,
            budgetTokens = budget,
            display = display,
            blockBinding = blockBinding?.let(::AnthropicBlockBinding),
        )
    } else {
        null
    }

    val maxTokens = resolveMaxTokens(modelId, caps, requestedMaxTokens, budget.takeIf { active }, warnings)

    return ResolvedThinking(
        maxTokens = maxTokens,
        thinking = thinking,
        effort = resolveEffort(modelId, caps, requested, options, extended, warnings)
            .withoutDisabledEffortConflict(type, caps, warnings),
        extended = extended,
        thinkingActive = active,
        // Thinking between tool calls on 4.5-and-earlier needs an opt-in beta header; adaptive models
        // interleave on their own and ignore it.
        interleavedBeta = extended,
        betas = buildSet {
            if (display == THINKING_DISPLAY_UPDATES) add(ANTHROPIC_THINKING_DISPLAY_UPDATES_BETA)
            if (blockBinding != null) add(ANTHROPIC_THINKING_BINDING_CONTROLS_BETA)
        },
    )
}

/**
 * The `thinking.type` a neutral effort implies, or null to omit the field.
 *
 * Two models reject the value that would otherwise be obvious. A ≤4.5 model 400s on `adaptive`, and the
 * always-on families 400 on `disabled` — for those, "no thinking" is expressed by saying nothing, which
 * leaves thinking running with its trace hidden. That is the honest outcome: the request cannot turn it
 * off, and pretending otherwise costs the whole call.
 */
private fun derivedThinkingType(mode: AnthropicThinkingMode, requested: ReasoningEffort): String? = when {
    requested == ReasoningEffort.None -> when (mode) {
        AnthropicThinkingMode.AdaptiveAlwaysOn -> null
        AnthropicThinkingMode.Extended -> null
        AnthropicThinkingMode.Adaptive -> "disabled"
    }
    mode == AnthropicThinkingMode.Extended -> "enabled"
    else -> "adaptive"
}

/**
 * The extended-thinking budget, as a share of what the MODEL can emit rather than of what the caller
 * asked for.
 *
 * Anchoring on the caller's `max_tokens` is what made a short answer also a shallow one: asking for 500
 * tokens of output used to buy 250 tokens of thinking, which is below Anthropic's own 1024 floor.
 */
private fun extendedBudget(
    requested: ReasoningEffort,
    caps: AnthropicModelCapabilities,
    warnings: MutableList<Warning>,
): Int {
    val share = when (requested) {
        ReasoningEffort.Minimal -> MINIMAL_SHARE
        ReasoningEffort.Low -> LOW_SHARE
        ReasoningEffort.Medium -> MEDIUM_SHARE
        // ProviderDefault sends no effort, and `high` is what Anthropic does when none is sent.
        ReasoningEffort.High, ReasoningEffort.ProviderDefault -> HIGH_SHARE
        ReasoningEffort.XHigh -> XHIGH_SHARE
        ReasoningEffort.None -> return MIN_THINKING_BUDGET
    }
    val budget = (caps.maxOutputTokens * share).roundToInt()
        .coerceIn(MIN_THINKING_BUDGET, caps.maxOutputTokens)
    if (budget == MIN_THINKING_BUDGET && caps.maxOutputTokens * share < MIN_THINKING_BUDGET) {
        warnings += Warning.Compatibility(
            feature = "reasoning",
            details = "this model's thinking budget was raised to Anthropic's $MIN_THINKING_BUDGET minimum.",
        )
    }
    return budget
}

/**
 * `max_tokens`: what the caller asked for, plus room for thinking, capped at what the model can emit.
 *
 * The cap only applies to a model the table knows. Clamping an id we are guessing about would truncate a
 * reply for no reason, so an unknown model gets the limit and a warning naming it instead.
 */
private fun resolveMaxTokens(
    modelId: String,
    caps: AnthropicModelCapabilities,
    requestedMaxTokens: Int?,
    thinkingBudget: Int?,
    warnings: MutableList<Warning>,
): Int {
    if (!caps.known && requestedMaxTokens == null) {
        warnings += Warning.Compatibility(
            feature = "maxOutputTokens",
            details = "The model \"$modelId\" is unknown. " +
                "The max output tokens have been limited to ${caps.maxOutputTokens}. " +
                "Set maxOutputTokens explicitly to override this limit.",
        )
    }

    val requested = (requestedMaxTokens ?: caps.maxOutputTokens) + (thinkingBudget ?: 0)
    if (requestedMaxTokens != null && requested > requestedMaxTokens) {
        // The budget is genuinely extra spend against the caller's own ceiling. Adding it silently is how
        // a caller who capped a turn at 100 tokens ends up paying for 19,300.
        warnings += Warning.Compatibility(
            feature = "maxOutputTokens",
            details = "Raised to $requested to leave room for a ${thinkingBudget}-token thinking budget.",
        )
    }
    if (!caps.known || requested <= caps.maxOutputTokens) return requested

    if (requestedMaxTokens != null) {
        warnings += Warning.Unsupported(
            feature = "maxOutputTokens",
            details = "$requested (maxOutputTokens + thinkingBudget) is greater than " +
                "$modelId ${caps.maxOutputTokens} max output tokens. " +
                "The max output tokens have been limited to ${caps.maxOutputTokens}.",
        )
    }
    return caps.maxOutputTokens
}

/**
 * `output_config.effort`.
 *
 * Effort is NOT exclusive to adaptive mode. Opus 4.5 is extended-thinking-only yet supports it, where it
 * shapes the overall response while `budget_tokens` sets thinking depth — the docs say to set both.
 * Gating effort on `!extended`, as the code carried over from AIDE did, silently dropped the only depth
 * control Opus 4.5 users have beyond the budget.
 *
 * `high` is dropped because it is the API default, and setting a parameter to its own default does not
 * invalidate the prompt cache either way.
 */
private fun resolveEffort(
    modelId: String,
    caps: AnthropicModelCapabilities,
    requested: ReasoningEffort,
    options: AnthropicOptions,
    extended: Boolean,
    warnings: MutableList<Warning>,
): String? {
    options.effort?.let { return it.takeIf { level -> level in AnthropicEffortLevels } }
    if (extended && !supportsEffortInExtendedMode(modelId)) return null

    val level = when (requested) {
        ReasoningEffort.ProviderDefault, ReasoningEffort.None -> return null
        ReasoningEffort.Minimal -> "low"
        ReasoningEffort.Low -> "low"
        ReasoningEffort.Medium -> "medium"
        ReasoningEffort.High -> "high"
        // A model without `xhigh` still has a level above `high`; it spells it `max`.
        ReasoningEffort.XHigh -> if (caps.supportsXhighEffort) "xhigh" else "max"
    }
    if (!level.equals(requested.name, ignoreCase = true)) {
        warnings += Warning.Compatibility(
            feature = "reasoning",
            details = "reasoning \"${requested.name.lowercase()}\" is not directly supported by " +
                "this model. mapped to effort \"$level\".",
        )
    }
    return level.takeIf { it != "high" }
}

/**
 * Drops an effort level that the model refuses to see beside `thinking: {type:"disabled"}`.
 *
 * Opus 5 and later accept `disabled` only at effort `high` or below; paired with `xhigh` or `max` the
 * request is a 400, enforced per request. Both halves are individually legal, so the pairing is the kind
 * of mistake nothing catches until the call fails.
 *
 * The effort is what gives way, not the `disabled`, for two reasons. Turning thinking off is a decision
 * about cost and behaviour that the caller stated outright; effort is a dial. And effort is the primary
 * thinking lever — with thinking off there is almost nothing left for `xhigh` to mean, so dropping it
 * costs the caller nothing real, where overriding `disabled` would silently bill them for reasoning they
 * asked not to have. Omitting the field lands on the server default of `high`, which is inside the
 * accepted range.
 */
private fun String?.withoutDisabledEffortConflict(
    thinkingType: String?,
    caps: AnthropicModelCapabilities,
    warnings: MutableList<Warning>,
): String? {
    if (thinkingType != "disabled" || !caps.disabledRequiresLowEffort) return this
    if (this != "xhigh" && this != "max") return this
    warnings += Warning.Compatibility(
        feature = "reasoning",
        details = "this model accepts thinking \"disabled\" only at effort \"high\" or below, " +
            "so effort \"$this\" was dropped rather than failing the request.",
    )
    return null
}

/**
 * Whether an extended-thinking-only model also accepts `output_config.effort`.
 *
 * Opus 4.5 is the only one. Every other extended-only model (Sonnet 4.5, Haiku 4.5, the earlier Claude 4
 * family) takes depth from `budget_tokens` alone.
 */
internal fun supportsEffortInExtendedMode(modelId: String): Boolean =
    "opus" in modelId.lowercase() && anthropicThinkingMode(modelId) == AnthropicThinkingMode.Extended

internal data class ResolvedThinking(
    val maxTokens: Int,
    val thinking: AnthropicThinking?,
    val effort: String?,
    val extended: Boolean,
    /** Whether the model will actually think. The three sampler parameters are rejected when it will. */
    val thinkingActive: Boolean,
    /** Whether this model needs the interleaved-thinking beta header to think between tool calls. */
    val interleavedBeta: Boolean = false,
    /** The betas the resolved `thinking` object itself requires — `display: updates`, block binding. */
    val betas: Set<String> = emptySet(),
)

/**
 * Opt-in header for thinking between tool calls on Claude 4.5 and earlier.
 *
 * Adaptive models interleave automatically and ignore it; the Claude API accepts it on any model, so
 * sending it where it does nothing is harmless. Without it, a 4.5-era model with tools thinks once at the
 * start of the turn and never again — it cannot reason about a tool result before the next call.
 */
public const val ANTHROPIC_INTERLEAVED_THINKING_BETA: String = "interleaved-thinking-2025-05-14"

/** Streams thinking updates between tool calls; gated behind its own beta rather than a model rule. */
public const val ANTHROPIC_THINKING_DISPLAY_UPDATES_BETA: String = "thinking-display-updates-2026-08-18"

/** Required whenever `thinking.block_binding` is sent — without it the field is an unknown-parameter 400. */
public const val ANTHROPIC_THINKING_BINDING_CONTROLS_BETA: String = "thinking-binding-controls-2026-08-01"

private const val THINKING_DISPLAY_UPDATES = "updates"

private const val MIN_THINKING_BUDGET = 1024

// The reference's budget shares, and the reason they differ so widely: thinking is billed as output, so
// `minimal` has to be cheap enough to be worth asking for and `xhigh` has to leave room for an answer.
private const val MINIMAL_SHARE = 0.02
private const val LOW_SHARE = 0.1
private const val MEDIUM_SHARE = 0.3
private const val HIGH_SHARE = 0.6
private const val XHIGH_SHARE = 0.9
