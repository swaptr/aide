package com.sabreware.aide.data.llm.remote

import com.sabreware.aide.core.domain.llm.ProviderManagement
import com.sabreware.aide.core.domain.llm.RemoteCatalogState
import com.sabreware.aide.core.domain.model.ModelMetadata
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.provider.ConnectionTestResult
import com.sabreware.aide.core.domain.provider.ProviderConfig
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.data.catalog.CachedModel
import com.sabreware.aide.data.catalog.RemoteCatalogCache
import com.sabreware.aide.data.catalog.toCachedListing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/**
 * Reusable provider management for any `:aisdk`-backed provider. The three things that differ per provider —
 * how the catalog is sourced ([catalog]; a curated list for Gemini, a live `/models` listing for
 * OpenAI-compatible servers and Anthropic), how one cached entry is re-minted into a spec ([mint]) and how a connection is tested
 * ([connectionTester]) — are injected lambdas, so the catalog-state machine (Unknown → Unconfigured /
 * Ready / Refreshing / Failed, driven by the credential flow) lives here once. Participates in the unified
 * registry via the single [remoteCatalogFlow] seam — zero registry edits. `pullModel`/`hydrateSpec` use the
 * interface defaults (hosted models, no probe).
 */
class RemoteProviderManagement(
    private val providerId: ProviderId,
    /** The connection's live endpoint + key, read before this is built; null once the connection is gone. */
    private val config: StateFlow<ProviderConfig?>,
    /** The connection's own scope: the config collector below dies with the connection. */
    scope: CoroutineScope,
    private val catalog: suspend () -> List<ChatModelSpec>,
    private val connectionTester: suspend () -> ConnectionTestResult,
    /** Last-good listing on disk, so a configured provider resolves its models before the network answers. */
    private val cache: RemoteCatalogCache,
    /** Re-mints one cached entry — the same [com.sabreware.aide.data.catalog.RemoteCatalog] builder [catalog] uses. */
    private val mint: suspend (CachedModel) -> ChatModelSpec,
    // Per-provider metadata resolver (Ollama /api/show → models.dev, or models.dev alone). Mirrors [catalog].
    private val metadata: suspend (String) -> ModelMetadata? = { null },
) : ProviderManagement {

    private val _state = MutableStateFlow<RemoteCatalogState>(RemoteCatalogState.Unknown)
    override val remoteCatalogFlow: Flow<RemoteCatalogState> = _state.asStateFlow()

    private var lastSpecs: List<ChatModelSpec> = emptyList()
    private val refreshMutex = Mutex()

    init {
        scope.launch {
            // Index 0 is not a change: it is the stored config being READ at construction. Refreshing on it
            // fired a network catalog fetch the moment the provider was constructed, which is during the
            // first frame (chat is the start destination and its ViewModel resolves the registry). The
            // initial fetch is a [com.sabreware.aide.data.llm.CatalogRefreshBootstrap] instead, which the
            // shell runs after that frame — but waiting for it left a configured cloud model unresolvable
            // until the network answered, so index 0 seeds from the on-disk last-good listing. Disk only;
            // still no request before the first frame. `collectIndexed` over a `drop(1)` + separate
            // `first()`: one subscription, so a config change cannot slip through between the two.
            // [config] holds a real value from the start (never an unread seed — a null here would wipe the
            // cache), and goes null only when the connection is removed. A new key refetches: that is how a
            // user fixes a wrong one.
            config.collectIndexed { index, current ->
                when {
                    current == null -> forget()
                    index == 0 -> seed(current.baseUrl)
                    else -> refreshInternal(current.baseUrl)
                }
            }
        }
    }

    /** No credentials: drop the cached listing too, so removing a key while the process was dead sticks. */
    private suspend fun forget() = refreshMutex.withLock {
        lastSpecs = emptyList()
        cache.clear(providerId)
        _state.value = RemoteCatalogState.Unconfigured
    }

    /**
     * The on-disk last-good listing, published as [RemoteCatalogState.Ready] with its ORIGINAL timestamp so
     * the provider screen's "updated" line stays true and the deferred refresh still has work to announce.
     *
     * Total by construction: a seed that threw would leave this provider [RemoteCatalogState.Unknown]
     * forever, and the registry snapshot null with it — a permanent skeleton is a worse failure than the
     * flash this replaces.
     *
     * Holds [refreshMutex] for the same reason [refreshInternal] does, and it is not a formality: the
     * deferred [com.sabreware.aide.data.llm.CatalogRefreshBootstrap] fires right after the first frame,
     * while this is still re-minting the cached listing. Without the lock that refresh published
     * `Refreshing(lastSpecs)` with `lastSpecs` still empty — a SETTLED "this provider has no models" over a
     * provider whose listing was one disk read away, which is exactly the "Set up a model to begin" flash.
     */
    private suspend fun seed(endpoint: String) = refreshMutex.withLock {
        val cached = runCatching { cache.read(providerId, endpoint) }
            .onFailure { AideLog.w(TAG, "catalog cache unreadable for $providerId", it) }
            .getOrNull()
        val specs = cached
            ?.let { listing -> runCatching { listing.models.map { mint(it) } }.getOrNull() }
            .orEmpty()
        if (cached == null || specs.isEmpty()) {
            // Configured but nothing cached (first run after adding the key). NOT Unconfigured — reporting
            // that is what made `isConfigured` false, and the model gate `NoModel`, for a provider that is
            // set up fine. `Refreshing` with no `previous` is UNSETTLED (RemoteCatalogState.isSettled), so
            // the registry keeps holding its snapshot back until the deferred refresh fills this in.
            _state.value = RemoteCatalogState.Refreshing(emptyList())
            return@withLock
        }
        lastSpecs = specs
        _state.value = RemoteCatalogState.Ready(specs, cached.fetchedAt)
    }

    private suspend fun refreshInternal(endpoint: String) = refreshMutex.withLock {
        _state.value = RemoteCatalogState.Refreshing(lastSpecs)
        runCatching { catalog() }
            .onSuccess { specs ->
                lastSpecs = specs
                val fetchedAt = Clock.System.now().toEpochMilliseconds()
                _state.value = RemoteCatalogState.Ready(specs, fetchedAt)
                cache.write(providerId, specs.toCachedListing(endpoint, fetchedAt))
            }
            .onFailure {
                // Keep whatever was seeded from disk: an unreachable endpoint must not empty the picker or
                // drop the chat header back to "No model".
                _state.value = RemoteCatalogState.Failed(lastSpecs, it.message ?: "fetch failed")
            }
    }

    override suspend fun refreshCatalog() {
        val endpoint = config.first()?.baseUrl ?: return
        refreshInternal(endpoint)
    }

    override suspend fun testConnection(): ConnectionTestResult = connectionTester()

    override suspend fun modelMetadata(modelId: String): ModelMetadata? = metadata(modelId)

    private companion object {
        const val TAG = "RemoteProviderManagement"
    }
}
