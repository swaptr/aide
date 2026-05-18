package com.swaptr.aide.domain.search

import android.util.Log
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSearchResolver @Inject constructor(
    private val providers: Map<WebSearchProviderId, @JvmSuppressWildcards WebSearchProvider>,
    private val prefs: UserPreferencesRepository,
) {

    suspend fun resolve(): WebSearchProvider {
        val override = prefs.webSearchProviderOverrideFlow.first()
        if (override != null) {
            val picked = providers[override]
            if (picked != null && picked.isAvailable()) {
                Log.i(TAG, "resolved=override:${picked.id}")
                return picked
            }
            Log.i(TAG, "override=$override unavailable; falling back to auto")
        }
        for (id in WebSearchProviderId.entries) {
            val candidate = providers[id] ?: continue
            if (candidate.isAvailable()) {
                Log.i(TAG, "resolved=auto:${candidate.id}")
                return candidate
            }
        }
        // Loud fallback if DDG binding goes missing — surfaces misconfig instead of a silent NPE.
        error("No web search provider available — DuckDuckGo binding is missing")
    }

    fun activeProviderNameFlow(): Flow<String> = flow {
        prefs.webSearchProviderOverrideFlow.collect {
            emit(resolve().displayName)
        }
    }

    fun allProviders(): List<WebSearchProvider> =
        WebSearchProviderId.entries.mapNotNull { providers[it] }

    companion object {
        private const val TAG = "WebSearchResolver"
    }
}
