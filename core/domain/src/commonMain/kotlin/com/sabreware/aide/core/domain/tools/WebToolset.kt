package com.sabreware.aide.core.domain.tools

import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.search.ProviderChain
import com.sabreware.aide.core.domain.search.WebFetcher

/**
 * The Web category: fetching a URL, and — when the user has opened the web-search gate for the turn —
 * searching. One [Toolset] because Settings offers one Web row; the two tool builders stay separate
 * classes because they are unrelated pieces of work.
 *
 * [activeProviderName] is a supplier rather than a value because the user can switch search providers
 * while the app runs, and the tool description names the provider.
 */
class WebToolset(
    private val chain: ProviderChain,
    private val fetcher: WebFetcher,
    private val activeProviderName: () -> String,
) : Toolset {

    override val category = ToolCategory.Web
    override val displayName = "Web"
    override val blurb = "Search the web and fetch URLs."

    override fun tools(scope: ToolsetScope): List<AideTool> = buildList {
        add(WebFetchToolset(fetcher).asAideTool())
        // Search is gated per turn, not per category: the category can be on while the user has search off.
        if (ToolGate.WEB_SEARCH in scope.enabledGated) {
            add(WebSearchToolset(chain, activeProviderName()).asAideTool())
        }
    }
}
