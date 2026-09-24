package com.sabreware.aide.data.llm

import com.sabreware.aide.core.common.startup.DeferredBootstrap
import com.sabreware.aide.core.domain.llm.ManageableRegistry
import com.sabreware.aide.core.domain.llm.ProviderManagement
import com.sabreware.aide.core.domain.util.AideLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * The first remote-catalog fetch for every connection, run **after the first frame** — and for every
 * connection that appears after it.
 *
 * It used to happen in each provider's constructor: the chat screen is the start destination, its ViewModel
 * injects the model registry, and resolving that registry constructs every chat provider — so opening the
 * app fired network requests before the first frame had rendered. Nothing needed them that early.
 *
 * The ONE owner of first fetches: providers seed from the disk cache only, so a connection with nothing
 * cached stays mid-first-fetch (unsettled) until this refreshes it. It watches the registry rather than
 * reading it once, which covers a cold start whose connections are not read yet (the keyboard can be the
 * process's first surface), a connection the user adds later, and one moved to another endpoint (a new
 * runtime, so a new [ProviderManagement]).
 *
 * Contributed like every other deferred task, and idempotent: the shell's effect re-runs on activity
 * recreation, and each provider's own refresh is mutex-guarded besides.
 */
class CatalogRefreshBootstrap(
    private val manageables: ManageableRegistry,
    private val appScope: CoroutineScope,
) : DeferredBootstrap {

    private var started = false

    override suspend fun start() {
        if (started) return
        started = true
        appScope.launch {
            val refreshed = HashSet<ProviderManagement>()
            manageables.flow.filterNotNull().collect { providers ->
                val current = providers.mapTo(HashSet()) { it.management }
                refreshed.retainAll(current)
                providers.filter { refreshed.add(it.management) }.forEach { provider ->
                    // One coroutine per provider: a slow or unreachable one must not hold up the others.
                    appScope.launch {
                        runCatching { provider.management.refreshCatalog() }
                            .onFailure { AideLog.w(TAG, "initial catalog refresh failed for ${provider.id}", it) }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "CatalogRefresh"
    }
}
