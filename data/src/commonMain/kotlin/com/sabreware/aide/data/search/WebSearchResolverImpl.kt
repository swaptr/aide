package com.sabreware.aide.data.search
import com.sabreware.aide.core.domain.search.SearchPrefs
import com.sabreware.aide.core.domain.search.WebSearchProvider
import com.sabreware.aide.core.domain.search.WebSearchProviderId
import com.sabreware.aide.core.domain.search.WebSearchResolver

import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.core.common.prefs.PreferenceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import com.sabreware.aide.core.domain.search.WebSearchCredentialsRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

class WebSearchResolverImpl(
    private val providers: Map<WebSearchProviderId, @JvmSuppressWildcards WebSearchProvider>,
    private val prefs: PreferenceStore,
    private val credentials: WebSearchCredentialsRepository,
) : WebSearchResolver {

    override suspend fun resolve(): WebSearchProvider {
        val override = prefs.flow(SearchPrefs.ProviderOverride).first()
        if (override != null) {
            val picked = providers[override]
            if (picked != null && picked.isAvailable()) {
                AideLog.i(TAG, "resolved=override:${picked.id}")
                return picked
            }
            AideLog.i(TAG, "override=$override unavailable; falling back to auto")
        }
        for (id in WebSearchProviderId.entries) {
            val candidate = providers[id] ?: continue
            if (candidate.isAvailable()) {
                AideLog.i(TAG, "resolved=auto:${candidate.id}")
                return candidate
            }
        }
        // Loud fallback if DDG binding goes missing — surfaces misconfig instead of a silent NPE.
        error("No web search provider available — DuckDuckGo binding is missing")
    }

    // Re-resolved on either input that can change the answer: the override, or which providers have a key
    // (adding a Brave key moves Auto off DuckDuckGo — the tool used to keep advertising the old name).
    override fun activeProviderNameFlow(): Flow<String> =
        combine(prefs.flow(SearchPrefs.ProviderOverride), credentials.configured.filterNotNull()) { _, _ -> }
            .map { resolve().displayName }
            .distinctUntilChanged()

    override fun allProviders(): List<WebSearchProvider> =
        WebSearchProviderId.entries.mapNotNull { providers[it] }

    companion object {
        private const val TAG = "WebSearchResolver"
    }
}
