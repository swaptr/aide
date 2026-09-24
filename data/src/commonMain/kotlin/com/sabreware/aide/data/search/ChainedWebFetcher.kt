package com.sabreware.aide.data.search

import com.sabreware.aide.core.domain.search.FetchOutcome
import com.sabreware.aide.core.domain.search.WebFetchProvider
import com.sabreware.aide.core.domain.search.WebFetchProviderId
import com.sabreware.aide.core.domain.search.WebFetcher
import com.sabreware.aide.core.domain.util.AideLog

/**
 * The [WebFetcher] binding: a chain over the available [WebFetchProvider]s in [WebFetchProviderId] order
 * (Ollama Cloud `web_fetch` when configured → DuckDuckGo scrape fallback). Returns the first NON-error
 * outcome; falls through to the next provider only on a typed [FetchOutcome.Error] ([FetchOutcome.Text]
 * and [FetchOutcome.Empty] are terminal — an empty page is a real answer, not a failure). DuckDuckGo is
 * always available, so the chain always has a usable terminal provider. Mirrors `ProviderChainImpl` (search).
 */
class ChainedWebFetcher(
    private val providers: Map<WebFetchProviderId, @JvmSuppressWildcards WebFetchProvider>,
) : WebFetcher {

    override suspend fun fetchOutcome(url: String, maxChars: Int): FetchOutcome {
        val ordered = WebFetchProviderId.entries.mapNotNull { providers[it] }.filter { it.isAvailable() }
        if (ordered.isEmpty()) return FetchOutcome.Error("FETCH_FAILED", "no fetch provider available")
        var lastError: FetchOutcome.Error? = null
        for (provider in ordered) {
            val outcome = runCatching { provider.fetch(url, maxChars) }.getOrElse {
                AideLog.w(TAG, "${provider.id} fetch threw: ${it.message}")
                FetchOutcome.Error("FETCH_FAILED", it.message ?: "fetch failed")
            }
            if (outcome !is FetchOutcome.Error) return outcome
            lastError = outcome
            if (ordered.size > 1) AideLog.i(TAG, "${provider.id} fetch error (${outcome.code}); trying next")
        }
        return lastError ?: FetchOutcome.Error("FETCH_FAILED", "all fetch providers failed")
    }

    private companion object {
        const val TAG = "WebFetchChain"
    }
}
