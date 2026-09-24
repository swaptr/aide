package com.sabreware.aide.aisdk.providers.anthropic

/**
 * What a Claude model will actually accept, read off its id.
 *
 * Every field here is a 400 or a lost capability if guessed wrong, and none of them is discoverable at
 * runtime — Anthropic publishes the differences in a comparison table and enforces them at the API. The
 * table is ported from the reference's `getModelCapabilities`, which is maintained against that page.
 *
 * The one this codebase was missing entirely is [maxOutputTokens]. A flat 4096 default meant an unset
 * `maxOutputTokens` on Opus 5 produced a reply capped at 4096 where the model will write 128000, and a
 * caller asking for 200000 on Sonnet 4.5 got a 400 instead of a clamp and a warning.
 */
internal data class AnthropicModelCapabilities(
    /** Both the cap and the default: what the model will emit when the caller names no limit. */
    val maxOutputTokens: Int,
    /** `output_config.format` with a JSON schema. Older families need the json-tool fallback instead. */
    val supportsStructuredOutput: Boolean,
    /** `thinking: {type:"adaptive"}`. Models without it take `enabled` + a budget, and 400 on adaptive. */
    val supportsAdaptiveThinking: Boolean,
    /**
     * Whether `thinking: {type:"disabled"}` is rejected outright — the docs' "Always on" column.
     *
     * Claude Fable 5, Mythos 5 and Mythos Preview think on every turn and 400 on `disabled`; for them
     * "no thinking" is expressed by saying nothing and hiding the trace with `display`. This used to be
     * a substring test for `fable`/`mythos` over the model id, which is a second classifier over the
     * same fact — and one that put Mythos Preview in the wrong family.
     */
    val rejectsDisabledThinking: Boolean,
    /**
     * Whether `thinking: {type:"enabled"}` with a budget is still accepted — the legacy extended mode.
     *
     * Read together with [supportsAdaptiveThinking] this reproduces the docs' "Thinking types" column:
     * adaptive-only, extended-only, or both. Mythos Preview is the one always-on model that is also
     * "Adaptive, extended", which is why the two flags are independent rather than an enum.
     */
    val supportsExtendedThinking: Boolean,
    /**
     * Whether `disabled` is accepted only at effort `high` or below.
     *
     * Opus 5 and later reject `thinking: {type:"disabled"}` combined with effort `xhigh` or `max`, per
     * request. Both halves look individually legal, so nothing short of this flag catches the pairing
     * before the 400.
     */
    val disabledRequiresLowEffort: Boolean,
    /** The 4.7-and-later families reject `temperature`, `top_p` and `top_k` outright. */
    val rejectsSamplingParameters: Boolean,
    /** Whether `output_config.effort` accepts `xhigh`; where it does not, `max` is the nearest level. */
    val supportsXhighEffort: Boolean,
    /**
     * Whether this id is one the table actually knows.
     *
     * Drives two behaviours the reference is careful about: an unknown id gets a compatibility warning
     * naming the limit it was given, and its [maxOutputTokens] is NOT used to clamp — clamping a model
     * whose real ceiling we are guessing at would truncate a reply for no reason.
     */
    val known: Boolean,
)

/**
 * The capability table, ordered most specific first.
 *
 * Matching is `contains`, as in the reference, so a dated id (`claude-sonnet-4-5-20250929`) resolves to
 * its family. Order is load-bearing: `claude-sonnet-4-5` has to be tested before `claude-sonnet-4-`.
 */
@Suppress("ReturnCount")
internal fun anthropicModelCapabilities(modelId: String): AnthropicModelCapabilities {
    fun caps(
        maxOutputTokens: Int,
        structuredOutput: Boolean = false,
        adaptive: Boolean = false,
        rejectsSamplers: Boolean = false,
        xhigh: Boolean = false,
        known: Boolean = true,
        rejectsDisabled: Boolean = false,
        extended: Boolean = !adaptive,
        disabledNeedsLowEffort: Boolean = false,
    ) = AnthropicModelCapabilities(
        maxOutputTokens = maxOutputTokens,
        supportsStructuredOutput = structuredOutput,
        supportsAdaptiveThinking = adaptive,
        rejectsDisabledThinking = rejectsDisabled,
        supportsExtendedThinking = extended,
        disabledRequiresLowEffort = disabledNeedsLowEffort,
        rejectsSamplingParameters = rejectsSamplers,
        supportsXhighEffort = xhigh,
        known = known,
    )

    return when {
        // Always on: these three 400 on `disabled`. Mythos Preview is the one that also keeps extended
        // thinking, which is why it cannot share a branch with the other two. Fable 5.1 is a point
        // release of the same family (`claude-fable-5-1` contains `claude-fable-5`), and the reference
        // gives it the same row.
        "claude-fable-5" in modelId || "claude-mythos-5" in modelId ->
            caps(
                LARGE_OUTPUT, structuredOutput = true, adaptive = true, rejectsSamplers = true,
                xhigh = true, rejectsDisabled = true,
            )

        "claude-mythos-preview" in modelId ->
            caps(
                LARGE_OUTPUT, structuredOutput = true, adaptive = true, rejectsSamplers = true,
                xhigh = true, rejectsDisabled = true, extended = true,
            )

        // Opus 5 accepts `disabled`, but only at effort `high` or below.
        "claude-opus-5" in modelId ->
            caps(
                LARGE_OUTPUT, structuredOutput = true, adaptive = true, rejectsSamplers = true,
                xhigh = true, disabledNeedsLowEffort = true,
            )

        "claude-opus-4-8" in modelId || "claude-opus-4-7" in modelId || "claude-sonnet-5" in modelId ->
            caps(LARGE_OUTPUT, structuredOutput = true, adaptive = true, rejectsSamplers = true, xhigh = true)

        // 4.6 keeps extended thinking alongside adaptive, deprecated but still accepted.
        "claude-sonnet-4-6" in modelId || "claude-opus-4-6" in modelId ->
            caps(LARGE_OUTPUT, structuredOutput = true, adaptive = true, extended = true)

        "claude-sonnet-4-5" in modelId || "claude-opus-4-5" in modelId || "claude-haiku-4-5" in modelId ->
            caps(MID_OUTPUT, structuredOutput = true)

        "claude-opus-4-1" in modelId -> caps(SMALL_OUTPUT, structuredOutput = true)

        // The base Claude 4 pair: a `-` before the date on Anthropic and Bedrock, an `@` on Vertex
        // (`claude-sonnet-4@20250514`). A plain `contains("claude-sonnet-4-")` missed the Vertex
        // spelling, which then fell through to the "newer than this table" row below and was sent
        // adaptive thinking — a 400 on a model that only takes a budget.
        SONNET_4_FAMILY.containsMatchIn(modelId) -> caps(MID_OUTPUT)
        OPUS_4_FAMILY.containsMatchIn(modelId) -> caps(SMALL_OUTPUT)
        "claude-3-haiku" in modelId -> caps(LEGACY_OUTPUT)

        // Claude 2, Instant and the 3.x line: real models, but old enough that the table treats them as
        // unknown so a caller still hears about the limit it is being given.
        LEGACY_FAMILY.containsMatchIn(modelId) -> caps(LEGACY_OUTPUT, known = false)

        // Every known family is handled above, so a remaining `claude-` id is newer than this table.
        // Assume the current generation's shape rather than the oldest one — a new model capped at 4096
        // would truncate every long answer — but keep it unknown so the warning still fires.
        "claude-" in modelId ->
            caps(
                LARGE_OUTPUT,
                structuredOutput = true,
                adaptive = true,
                rejectsSamplers = true,
                xhigh = true,
                known = false,
                // The docs scope the disabled-plus-high-effort rejection to "Claude Opus 5 and later
                // models", so a model newer than this table inherits it rather than discovering it.
                disabledNeedsLowEffort = true,
            )

        // Not a Claude model at all: MiniMax and the other vendors serving an Anthropic-shaped API.
        // Conservative on every axis, because none of these features is theirs to support.
        else -> caps(LEGACY_OUTPUT, known = false)
    }
}

private val LEGACY_FAMILY = Regex("claude-(?:instant(?:-|\$)|v?2(?=\$|[-.:])|3(?=\$|[-.]))")
private val SONNET_4_FAMILY = Regex("claude-sonnet-4(?:-|@)")
private val OPUS_4_FAMILY = Regex("claude-opus-4(?:-|@)")

private const val LARGE_OUTPUT = 128_000
private const val MID_OUTPUT = 64_000
private const val SMALL_OUTPUT = 32_000
private const val LEGACY_OUTPUT = 4_096
