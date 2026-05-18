package com.swaptr.aide.domain.search

import android.util.Log
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProviderChain"

@Singleton
class ProviderChain @Inject constructor(
    private val providers: Map<WebSearchProviderId, @JvmSuppressWildcards WebSearchProvider>,
    private val prefs: UserPreferencesRepository,
) {

    suspend fun displayName(): String {
        val override = prefs.webSearchProviderOverrideFlow.first()
        if (override != null) return providers[override]?.displayName ?: "Auto"
        return "Auto"
    }

    suspend fun search(query: String, max: Int): List<WebSearchResult> {
        val ordered = orderedProviders()
        if (ordered.isEmpty()) return emptyList()
        return withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            for (provider in ordered) {
                val outcome = runOne(provider, query, max)
                if (outcome.isNotEmpty()) {
                    Log.i(TAG, "served by ${provider.id} (${outcome.size} hits)")
                    return@withTimeoutOrNull outcome
                }
            }
            emptyList()
        } ?: emptyList()
    }

    private suspend fun runOne(
        provider: WebSearchProvider,
        query: String,
        max: Int,
    ): List<WebSearchResult> = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
        runCatching { provider.search(query, max) }
            .getOrElse {
                Log.w(TAG, "${provider.id} failed: ${it.message}")
                emptyList()
            }
    } ?: run {
        Log.w(TAG, "${provider.id} timed out after ${PROVIDER_TIMEOUT_MS}ms")
        emptyList()
    }

    private suspend fun orderedProviders(): List<WebSearchProvider> {
        val available = mutableListOf<WebSearchProvider>()
        val override = prefs.webSearchProviderOverrideFlow.first()
        if (override != null) {
            providers[override]?.takeIf { it.isAvailable() }?.let { available += it }
        }
        for (id in WebSearchProviderId.entries) {
            if (id == override) continue
            val candidate = providers[id] ?: continue
            if (candidate.isAvailable()) available += candidate
        }
        return available
    }

    companion object {
        const val PROVIDER_TIMEOUT_MS = 4_000L
        const val TOTAL_TIMEOUT_MS = 10_000L
    }
}
