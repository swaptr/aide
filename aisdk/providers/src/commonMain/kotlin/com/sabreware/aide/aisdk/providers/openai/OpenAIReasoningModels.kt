package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.Warning

/** The provider id, and the key every OpenAI payload is namespaced under in `providerMetadata`. */
public const val OPENAI_PROVIDER_ID: String = "openai"

/**
 * The Responses API's own id for a reasoning item.
 *
 * A replayed reasoning item must carry it back, either as the item's `id` or — with `store: true` — as
 * an `item_reference`. Without it OpenAI has no way to match the item to the chain of thought it holds,
 * and the model starts over.
 */
public const val OPENAI_ITEM_ID_KEY: String = "itemId"

/**
 * The encrypted chain of thought.
 *
 * OpenAI's counterpart to Anthropic's `signature` and Gemini's `thoughtSignature`, and the value this
 * provider exists to preserve. It is returned only when the request asks for it
 * (`include: ["reasoning.encrypted_content"]`), and it is the ONLY way a client that does not let OpenAI
 * store the conversation (`store: false`) can carry reasoning across a tool round. Dropped, the model
 * re-derives its whole chain of thought every round and bills for it every time.
 */
public const val OPENAI_ENCRYPTED_REASONING_KEY: String = "reasoningEncryptedContent"

/** The response id, filed on the result so a caller can chain with `previousResponseId`. */
public const val OPENAI_RESPONSE_ID_KEY: String = "responseId"

/** `include: ["reasoning.encrypted_content"]` — the opt-in that makes stateless reasoning replay work. */
public const val OPENAI_INCLUDE_ENCRYPTED_REASONING: String = "reasoning.encrypted_content"

/**
 * What a model family will accept.
 *
 * The two facts that produce a guaranteed 400 if wrong: a reasoning model rejects `temperature` and
 * `top_p` outright, and it wants its instructions under the `developer` role rather than `system`.
 * Mirrors the reference's `getOpenAILanguageModelCapabilities`, including its deliberate conservatism —
 * an unrecognized id (a fine-tune, a third-party gateway, a stealth model) is treated as NON-reasoning,
 * because guessing wrong in that direction merely forgoes a parameter rather than breaking the call.
 */
internal data class OpenAICapabilities(
    val isReasoningModel: Boolean,
    val systemRole: String,
    /** Effort levels this family accepts. Empty on a non-reasoning model. */
    val effortLevels: Set<String>,
    /**
     * GPT-5.1 and later accept `temperature`/`top_p` again, but only while reasoning effort is `none`.
     * Sampling and reasoning are mutually exclusive on that family rather than jointly unavailable.
     */
    val allowsSamplingWhenEffortNone: Boolean,
    /**
     * GPT-6 and later: a `configuration_update` input item may raise or lower the effort from this
     * response on without touching the request-level setting, so the cached prefix survives.
     */
    val supportsConfigurationUpdate: Boolean = false,
    /** GPT-6 and later: a function or custom tool may be marked `async` — the model continues without its result. */
    val supportsAsyncToolCalling: Boolean = false,
    /**
     * GPT-6 and later: [effortLevels] is a closed list. An explicit `reasoningEffort` outside it is
     * dropped with a warning rather than forwarded, because the endpoint rejects it — where earlier
     * families accepted a level the neutral enum had no word for, which is what the provider option
     * exists to name.
     */
    val enforcesEffortLevels: Boolean = false,
)

private data class GptVersion(val major: Int, val minor: Int?, val variant: String?)

private val OSeriesPattern = Regex("""^o(\d+)(?:-|$)""")
private val GptPattern = Regex("""^gpt-(\d+)(?:\.(\d+))?(?:-(.+))?$""")

/**
 * Which parameter rules apply to [modelId].
 *
 * Version is read off the id rather than looked up in a table of names, because a name table is a list
 * that is wrong the week after it is written: a new `gpt-5.7` would fall off it and be handed a
 * `temperature` that returns 400.
 */
@Suppress("ReturnCount")
internal fun openAICapabilities(modelId: String): OpenAICapabilities {
    val oSeries = OSeriesPattern.find(modelId)?.groupValues?.get(1)?.toIntOrNull()
    if (oSeries != null) {
        // o1/o3/o4 take low/medium/high only: `minimal` and `none` are gpt-5 vocabulary and 400 here.
        return OpenAICapabilities(
            isReasoningModel = true,
            systemRole = DEVELOPER_ROLE,
            effortLevels = setOf("low", "medium", "high"),
            allowsSamplingWhenEffortNone = false,
        )
    }

    val gpt = GptPattern.find(modelId)?.let { match ->
        GptVersion(
            major = match.groupValues[1].toInt(),
            minor = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt(),
            variant = match.groupValues[3].takeIf { it.isNotEmpty() },
        )
    } ?: return OpenAICapabilities(false, SYSTEM_ROLE, emptySet(), allowsSamplingWhenEffortNone = false)

    // `gpt-5-chat-latest` and friends are the non-reasoning members of a reasoning family.
    val isChatModel = gpt.minor == null && (gpt.variant?.startsWith("chat") ?: false)
    if (gpt.major < FIRST_REASONING_GPT_MAJOR || isChatModel) {
        return OpenAICapabilities(false, SYSTEM_ROLE, emptySet(), allowsSamplingWhenEffortNone = false)
    }

    val minor = gpt.minor ?: 0
    val isGpt6OrLater = gpt.major >= GPT6_MAJOR
    val effortLevels = if (isGpt6OrLater) {
        // GPT-6 closed the list — low through max, and no `none` — and refuses anything outside it.
        setOf("low", "medium", "high", "xhigh", "max")
    } else {
        buildSet {
            addAll(listOf("low", "medium", "high"))
            // gpt-5 spelled "think as little as possible" `minimal`; 5.1 renamed it `none` and dropped
            // the old spelling. Sending the wrong one of the pair is a 400, not a fallback.
            if (gpt.major == FIRST_REASONING_GPT_MAJOR && minor == 0) add("minimal") else add("none")
            // `xhigh` arrived with gpt-5.1-codex-max and became general from 5.2.
            if (minor >= XHIGH_FROM_MINOR || gpt.variant?.contains("codex-max") == true) add("xhigh")
            if (minor >= MAX_EFFORT_FROM_MINOR) add("max")
        }
    }

    return OpenAICapabilities(
        isReasoningModel = true,
        systemRole = DEVELOPER_ROLE,
        effortLevels = effortLevels,
        // GPT-5.1 reopened sampling under `none`; GPT-6 has no `none` and closed it again.
        allowsSamplingWhenEffortNone = !isGpt6OrLater && (gpt.major > FIRST_REASONING_GPT_MAJOR || minor >= 1),
        supportsConfigurationUpdate = isGpt6OrLater,
        supportsAsyncToolCalling = isGpt6OrLater,
        enforcesEffortLevels = isGpt6OrLater,
    )
}

/** What the request should send for reasoning, and what it could not honour. */
internal data class ResolvedEffort(
    val effort: String?,
    val warnings: List<Warning>,
)

/**
 * Maps the neutral [ReasoningEffort] onto OpenAI's per-family vocabulary, SAYING SO when it substitutes.
 *
 * The ladders below are ordered preference, not aliases: `XHigh` asks for `xhigh` and settles for
 * `high`, `Minimal` asks for `minimal` and settles for `low`. Every other provider in this module used
 * to collapse those silently — `XHigh` simply became `high` — so a caller who paid for the deepest
 * reasoning available got the second-deepest and had nothing to tell them. `CallOptions.reasoning`
 * documents the opposite promise, and a `Warning.Compatibility` is how it is kept.
 *
 * A dropped value is [Warning.Unsupported]; a substituted one is [Warning.Compatibility]. The
 * distinction is the whole point: the first says nothing happened, the second says something else did.
 */
internal fun resolveEffort(
    requested: ReasoningEffort,
    capabilities: OpenAICapabilities,
    modelId: String,
): ResolvedEffort {
    if (requested == ReasoningEffort.ProviderDefault) return ResolvedEffort(null, emptyList())

    if (!capabilities.isReasoningModel) {
        return ResolvedEffort(
            effort = null,
            warnings = listOf(
                Warning.Unsupported(
                    feature = "reasoningEffort",
                    details = "$modelId is not a reasoning model; the effort was dropped.",
                ),
            ),
        )
    }

    val ladder = when (requested) {
        ReasoningEffort.ProviderDefault -> emptyList()
        // `minimal` and `none` mean the same thing to a caller and are spelled differently per family,
        // so each accepts the other before it gives up.
        ReasoningEffort.None -> listOf("none", "minimal")
        ReasoningEffort.Minimal -> listOf("minimal", "none", "low")
        ReasoningEffort.Low -> listOf("low")
        ReasoningEffort.Medium -> listOf("medium")
        ReasoningEffort.High -> listOf("high")
        // Not `max`: that level is deeper than anything the neutral enum can ask for, and choosing it
        // for the caller would spend tokens they did not request.
        ReasoningEffort.XHigh -> listOf("xhigh", "high")
    }

    val chosen = ladder.firstOrNull { it in capabilities.effortLevels }
        ?: return ResolvedEffort(
            effort = null,
            warnings = listOf(
                Warning.Unsupported(
                    feature = "reasoningEffort",
                    details = "$modelId accepts ${capabilities.effortLevels.sorted()}, none of which " +
                        "expresses ${requested.name}; the effort was dropped.",
                ),
            ),
        )

    val asked = ladder.first()
    val warnings = if (chosen == asked) {
        emptyList()
    } else {
        listOf(
            Warning.Compatibility(
                feature = "reasoningEffort",
                details = "$modelId does not accept '$asked'; sent '$chosen' instead.",
            ),
        )
    }
    return ResolvedEffort(chosen, warnings)
}

private const val SYSTEM_ROLE = "system"
private const val DEVELOPER_ROLE = "developer"
private const val FIRST_REASONING_GPT_MAJOR = 5
private const val GPT6_MAJOR = 6
private const val XHIGH_FROM_MINOR = 2
private const val MAX_EFFORT_FROM_MINOR = 6
