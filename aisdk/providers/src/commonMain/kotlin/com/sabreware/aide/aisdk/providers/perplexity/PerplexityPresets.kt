package com.sabreware.aide.aisdk.providers.perplexity

/**
 * The Agent API's presets: a model, a step budget and a tool set chosen by Perplexity, named by the
 * depth of research they buy.
 *
 * From the presets page (checked 2026-09-02): `fast` is one step of `web_search` on `gpt-5.6-luna`,
 * `low` and `medium` add `fetch_url` at 5 and 15 steps, `high` moves to `gpt-5.6-sol`, and `xhigh`
 * adds `finance_search` and the `sandbox` at 100 steps. A preset is "required if model is not
 * provided", and "any field you pass alongside the preset overrides that default" — so `model`,
 * `max_steps`, `reasoning` and `tools` may all ride beside one.
 *
 * A preset name never collides with a model id, because model ids are `provider/model`; that is what
 * lets [PerplexityProvider.languageModel] take a preset name as the id and send it as `preset` instead.
 */
public object PerplexityPresets {

    /** Single-fact lookups: one search step, the fastest answer. */
    public const val FAST: String = "fast"

    /** Everyday research with light multi-step lookups. */
    public const val LOW: String = "low"

    /** Multi-hop browsing across many sources. */
    public const val MEDIUM: String = "medium"

    /** Expert-level research on the deeper model. */
    public const val HIGH: String = "high"

    /** Open-ended agentic work with code execution. */
    public const val XHIGH: String = "xhigh"

    /** Every documented preset. */
    public val all: Set<String> = setOf(FAST, LOW, MEDIUM, HIGH, XHIGH)

    /** Whether [id] names a preset rather than a `provider/model` id. */
    public fun isPreset(id: String): Boolean = id in all

    /**
     * The preset Perplexity's migration guide maps a retiring Sonar model onto — a HELPER, never an
     * automatic rewrite.
     *
     * The guide (docs/agent-api/migrate-from-sonar, checked 2026-09-02) pairs `sonar` with `fast`,
     * `sonar-pro` with `low`, `sonar-reasoning-pro` with `medium` and `sonar-deep-research` with
     * `high`. It is not applied silently because a preset is not the same product: it picks a
     * different model, a different step budget and a tool set, and a caller who wrote `sonar-pro` into
     * a prompt is owed the choice of what replaces it. Null for an id the guide does not name, which
     * includes the plain `sonar-reasoning`.
     */
    public fun forSonarModel(sonarModelId: String): String? = when (sonarModelId) {
        "sonar" -> FAST
        "sonar-pro" -> LOW
        "sonar-reasoning-pro" -> MEDIUM
        "sonar-deep-research" -> HIGH
        else -> null
    }
}
