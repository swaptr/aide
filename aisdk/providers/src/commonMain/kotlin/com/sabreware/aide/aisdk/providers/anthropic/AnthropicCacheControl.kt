package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.ProviderOptions
import com.sabreware.aide.aisdk.Warning
import kotlinx.serialization.json.JsonObject

/**
 * Anthropic's prompt-cache breakpoints, and the ceiling of four per request.
 *
 * `cache_control` is the single highest-value per-part option there is — it is the difference between
 * re-billing a 50k-token system prompt every turn and not — and it was unreachable, because nothing read
 * a part's `providerOptions` for anything but a thinking signature.
 *
 * The budget is stateful across ONE request because the limit is: a fifth breakpoint is a 400 for the
 * whole call, and losing the fifth with a warning is better than losing the call. Contexts that cannot
 * carry one at all (a thinking block, which is cached implicitly with the turn it belongs to) say so, so
 * a caller that set it in the wrong place hears about it instead of wondering why nothing was cached.
 */
internal class AnthropicCacheControlBudget {

    private var used = 0

    /**
     * The `cache_control` value for a part, or null when there is none or it cannot be honoured.
     *
     * Both `cacheControl` and `cache_control` are accepted, because both spellings are in circulation
     * and neither is worth a support thread.
     */
    fun take(
        options: ProviderOptions?,
        context: String,
        warnings: MutableList<Warning>,
        canCache: Boolean = true,
    ): JsonObject? {
        val anthropic = options?.get(ANTHROPIC_PROVIDER_ID) ?: return null
        val value = (anthropic["cacheControl"] ?: anthropic["cache_control"]) as? JsonObject ?: return null

        if (!canCache) {
            warnings += Warning.Unsupported(
                feature = "cache_control on non-cacheable context",
                details = "cache_control cannot be set on $context. It will be ignored.",
            )
            return null
        }

        used++
        if (used > MAX_BREAKPOINTS) {
            warnings += Warning.Unsupported(
                feature = "cacheControl breakpoint limit",
                details = "Maximum $MAX_BREAKPOINTS cache breakpoints exceeded (found $used). " +
                    "This breakpoint will be ignored.",
            )
            return null
        }
        return value
    }

    private companion object {
        const val MAX_BREAKPOINTS = 4
    }
}
