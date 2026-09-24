package com.sabreware.aide.aisdk.providers.groq

import com.sabreware.aide.aisdk.util.ProviderToolFactory
import com.sabreware.aide.aisdk.util.providerExecutedTool
import kotlinx.serialization.json.JsonObject

/**
 * Groq's server-side tools.
 *
 * One today. Unlike Chat Completions' `function` tools, `browser_search` is a bare `{"type": ...}`
 * entry in the `tools` array — the model drives a real browser on Groq's side and streams back what it
 * found, so there is nothing for a client to execute and no schema for it to fill in.
 *
 * That shape is why the OpenAI-compatible model needs a
 * [com.sabreware.aide.aisdk.providers.openaicompatible.ProviderToolDialect] to send it at all: the
 * shared Chat Completions path drops every provider-defined tool with a warning, because Chat
 * Completions has no such concept. Groq's extension is opt-in per vendor rather than a branch in the
 * shared model.
 */
public object GroqTools {

    /**
     * Interactive browser search — navigates pages rather than reading a result list.
     *
     * Provider-EXECUTED: Groq runs it and returns the findings inside the assistant turn, so the
     * runtime never dispatches it and could not, holding no browser of its own.
     *
     * Both schemas are empty, matching the reference. The tool takes no input — including it in the
     * tools array is the whole activation, and the prompt steers what it looks for — and declares no
     * structured output, so the findings arrive as ordinary assistant content.
     */
    public val browserSearch: ProviderToolFactory = providerExecutedTool(
        id = "$GROQ_PROVIDER_ID.browser_search",
        wireName = "browser_search",
        inputSchema = JsonObject(emptyMap()),
        outputSchema = JsonObject(emptyMap()),
    )

    /** Every Groq tool, for a [com.sabreware.aide.aisdk.util.ToolNameMapping]. */
    public val all: List<ProviderToolFactory> = listOf(browserSearch)
}

/**
 * The models Groq serves `browser_search` on.
 *
 * An allow-list rather than a pattern, because Groq documents exactly these two and the failure mode
 * for guessing wrong is a 400 on a request the caller believed was fine. A model outside the list gets
 * a warning naming both the models that work and the one that was asked for — the reference's own
 * message, which is the difference between "search silently did nothing" and a fixable mistake.
 */
internal val GROQ_BROWSER_SEARCH_MODELS: List<String> = listOf(
    "openai/gpt-oss-20b",
    "openai/gpt-oss-120b",
)

internal fun supportsGroqBrowserSearch(modelId: String): Boolean =
    modelId in GROQ_BROWSER_SEARCH_MODELS
