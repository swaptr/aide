package com.sabreware.aide.aisdk.providers.alibaba

import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.options.optElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Model Studio accepts at most four cache markers in one request; past that, only the last four apply. */
private const val MAX_CACHE_BREAKPOINTS = 4

/** What counting the prompt's cache markers found. */
internal data class CacheBreakpoints(
    /** The marker for each message, index-aligned with the prompt; null where the message carries none. */
    val markers: List<JsonElement?>,
    val warnings: List<Warning>,
)

/**
 * Finds each message's explicit cache marker and warns when the request carries more than Alibaba honours.
 *
 * Counted here rather than at the wire because only the prompt knows how many there are: by the time the
 * body exists the markers are indistinguishable from any other vendor field, and a caller who set five
 * would silently get four with no clue which was dropped.
 *
 * Both spellings are accepted — `cacheControl` for a caller writing this port's camelCase convention,
 * `cache_control` for one copying Alibaba's documentation verbatim — and the value is passed through
 * untouched rather than validated. Model Studio documents exactly one shape today
 * (`{"type":"ephemeral"}`); rejecting anything else here would turn a vendor addition into a client-side
 * error, and the server validates it either way.
 */
internal fun Prompt.alibabaCacheBreakpoints(): CacheBreakpoints {
    val markers = map { message ->
        val alibaba = message.providerOptions?.get(ALIBABA_PROVIDER_ID)
        alibaba?.optElement("cacheControl") ?: alibaba?.optElement("cache_control")
    }
    val warnings = if (markers.count { it != null } > MAX_CACHE_BREAKPOINTS) {
        listOf(
            Warning.Other(
                "Max breakpoint limit exceeded. Only the last $MAX_CACHE_BREAKPOINTS cache markers " +
                    "will take effect.",
            ),
        )
    } else {
        emptyList()
    }
    return CacheBreakpoints(markers, warnings)
}

/** The prompt with each message's marker re-filed as `cache_control`, the spelling the wire wants. */
internal fun Prompt.withAlibabaCacheControl(markers: List<JsonElement?>): Prompt =
    mapIndexed { index, message ->
        val marker = markers.getOrNull(index) ?: return@mapIndexed message
        val options = message.providerOptions.orEmpty()
        val existing = options[ALIBABA_PROVIDER_ID] ?: JsonObject(emptyMap())
        // The camelCase spelling is this port's convention; only the snake_case one is Alibaba's.
        val rewritten = JsonObject(existing - "cacheControl" + ("cache_control" to marker))
        message.withProviderOptions(options + (ALIBABA_PROVIDER_ID to rewritten))
    }

/** [ModelMessage] has no `copy` across its subtypes, so the rewrite is one arm each. */
private fun ModelMessage.withProviderOptions(options: Map<String, JsonObject>): ModelMessage = when (this) {
    is ModelMessage.System -> copy(providerOptions = options)
    is ModelMessage.User -> copy(providerOptions = options)
    is ModelMessage.Assistant -> copy(providerOptions = options)
    is ModelMessage.Tool -> copy(providerOptions = options)
}
