package com.sabreware.aide.core.domain.tools

import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.search.ProviderChain
import com.sabreware.aide.core.domain.tools.results.WebSearchResult
import com.sabreware.aide.core.domain.util.AideLog
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "AideTools"
private val WEB_SEARCH_ERROR_CODES = setOf("SEARCH_FAILED")

class WebSearchToolset(
    private val chain: ProviderChain,
    val providerDisplayName: String,
) {

    fun asAideTool(): AideTool = AideTool.Function(
        name = "WebSearch",
        readOnly = true,
        description = "Search the web for current, factual, or time-sensitive " +
            "information. Use this when the answer needs up-to-date data the user " +
            "asked about (weather, news, prices, recent events) or any fact you " +
            "are not certain of. Returns a list of titles, snippets, and URLs. " +
            "If the snippets do not contain a direct answer, call WebFetch " +
            "on the most relevant url to read its full content.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "query" to stringProp("Plain-language search query, e.g. 'Tokyo weather today'"),
            ),
        ),
        // Suspend handler: chain.search runs on its own IO context without parking the dispatcher thread.
        handler = { args ->
            val query = args["query"]?.jsonPrimitive?.content.orEmpty()
            AideLog.i(TAG, "WebSearch called: query='$query'")
            val hits = runCatching { chain.search(query, max = 3) }
                .getOrElse {
                    if (it is CancellationException) throw it
                    AideLog.w(TAG, "WebSearch failed", it)
                    return@Function WebSearchResult.Err(
                        "SEARCH_FAILED",
                        it.message ?: "search failed",
                    ).toEnvelope()
                }
            AideLog.i(TAG, "WebSearch returned ${hits.size} hits")
            val result = if (hits.isEmpty()) WebSearchResult.Empty
            else WebSearchResult.Hits(hits.map {
                WebSearchResult.Hit(it.title, it.snippet, it.url)
            })
            result.toEnvelope()
        },
        surfaces = BOTH_SURFACES,
        errorCodes = WEB_SEARCH_ERROR_CODES,
    )
}
