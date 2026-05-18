package com.swaptr.aide.domain.llm.ollama

import android.util.Log
import com.swaptr.aide.data.catalog.ModelSpec
import com.swaptr.aide.data.catalog.RemoteCatalog
import com.swaptr.aide.data.provider.CachedOllamaTag
import com.swaptr.aide.data.provider.CachedOllamaTags
import com.swaptr.aide.data.provider.ConnectionTestResult
import com.swaptr.aide.data.provider.OllamaConfig
import com.swaptr.aide.data.provider.OllamaTagsCache
import com.swaptr.aide.data.provider.ProviderConfigRepository
import com.swaptr.aide.domain.llm.ProviderManagement
import com.swaptr.aide.domain.llm.PullProgress
import com.swaptr.aide.domain.llm.RemoteCatalogState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

class OllamaManagement(
    private val client: OllamaClient,
    private val providerConfigs: ProviderConfigRepository,
    private val tagsCache: OllamaTagsCache,
    appScope: CoroutineScope,
) : ProviderManagement {

    private val _state = MutableStateFlow<RemoteCatalogState>(RemoteCatalogState.Unconfigured)
    override val remoteCatalogFlow: Flow<RemoteCatalogState> = _state.asStateFlow()

    private val _specs = MutableStateFlow<List<ModelSpec>>(emptyList())
    val specsFlow: StateFlow<List<ModelSpec>> = _specs.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshingFlow: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _fetchedAt = MutableStateFlow<Long?>(null)
    val fetchedAtFlow: StateFlow<Long?> = _fetchedAt.asStateFlow()

    // One Mutex per tag name serialises concurrent /api/show hydration so two
    // ChatViewModels observing the same model don't fire duplicate requests.
    private val hydrateLocks = mutableMapOf<String, Mutex>()
    private val hydrateLocksGuard = Mutex()

    init {
        appScope.launch {
            providerConfigs.ollamaConfigFlow
                .onEach { config ->
                    if (config == null) {
                        _state.value = RemoteCatalogState.Unconfigured
                        _specs.value = emptyList()
                        _fetchedAt.value = null
                        tagsCache.clear()
                    } else {
                        loadCacheThenMaybeRefresh(config)
                    }
                }
                .collect()
        }
    }

    private suspend fun loadCacheThenMaybeRefresh(config: OllamaConfig) {
        val cached = tagsCache.cacheFlow.first()
        val match = cached?.takeIf {
            it.baseUrl == config.baseUrl && it.isCloud == config.isCloud
        }
        if (match != null) {
            // /api/tags returns server-defined order that can change between requests.
            val specs = match.tags
                .map { it.toModelSpec(isCloud = config.isCloud) }
                .sortedBy { it.displayName.lowercase() }
            _specs.value = specs
            _fetchedAt.value = match.fetchedAt
            _state.value = RemoteCatalogState.Ready(specs, match.fetchedAt)
        } else {
            _specs.value = emptyList()
            _fetchedAt.value = null
            _state.value = RemoteCatalogState.Refreshing(emptyList())
        }
        val stale = match == null ||
            (System.currentTimeMillis() - match.fetchedAt) > OllamaTagsCache.TTL_MS
        if (stale) refreshInternal(config)
    }

    private suspend fun refreshInternal(config: OllamaConfig) {
        if (_refreshing.value) return
        _refreshing.value = true
        _state.value = RemoteCatalogState.Refreshing(_specs.value)
        try {
            runCatching { client.listTags() }
                .onSuccess { resp ->
                    // Preserve already-hydrated caps so refresh doesn't trigger a second /api/show.
                    val priorByName = (tagsCache.cacheFlow.first())
                        ?.takeIf { it.baseUrl == config.baseUrl && it.isCloud == config.isCloud }
                        ?.tags
                        ?.associateBy { it.name }
                        ?: emptyMap()
                    val tags: List<CachedOllamaTag> = resp.models.map { entry ->
                        priorByName[entry.name]
                            ?: CachedOllamaTag(name = entry.name)
                    }
                    val specs = tags
                        .map { it.toModelSpec(isCloud = config.isCloud) }
                        .sortedBy { it.displayName.lowercase() }
                    val now = System.currentTimeMillis()
                    _specs.value = specs
                    _fetchedAt.value = now
                    _state.value = RemoteCatalogState.Ready(specs, now)
                    tagsCache.write(
                        CachedOllamaTags(
                            baseUrl = config.baseUrl,
                            isCloud = config.isCloud,
                            tags = tags,
                            fetchedAt = now,
                        ),
                    )
                }
                .onFailure {
                    Log.w(TAG, "ollama /api/tags failed", it)
                    _state.value = RemoteCatalogState.Failed(
                        previous = _specs.value,
                        message = it.message ?: "fetch failed",
                    )
                }
        } finally {
            _refreshing.value = false
        }
    }

    override suspend fun hydrateSpec(modelId: String) {
        val cfg = providerConfigs.ollamaConfigFlow.first() ?: return
        val tagName = stripIdPrefix(modelId) ?: return
        val cached = tagsCache.cacheFlow.first()
            ?.takeIf { it.baseUrl == cfg.baseUrl && it.isCloud == cfg.isCloud }
        val existing = cached?.tags?.firstOrNull { it.name == tagName }
        if (existing != null && existing.capabilities.isNotEmpty()) return

        val lock = hydrateLocksGuard.withLock {
            hydrateLocks.getOrPut(tagName) { Mutex() }
        }
        lock.withLock {
            // Re-check after acquiring — a concurrent caller may have just hydrated.
            val recheck = tagsCache.cacheFlow.first()
                ?.takeIf { it.baseUrl == cfg.baseUrl && it.isCloud == cfg.isCloud }
                ?.tags?.firstOrNull { it.name == tagName }
            if (recheck != null && recheck.capabilities.isNotEmpty()) return@withLock

            val show = runCatching { client.showModel(tagName) }
                .getOrElse {
                    Log.w(TAG, "ollama /api/show failed for $tagName", it)
                    return@withLock
                }
            val family = show.details?.family ?: tagName.substringBefore(':').lowercase()
            val ctxLen = show.modelInfo
                ?.get("$family.context_length")
                ?.let { (it as? JsonPrimitive)?.longOrNull }
            val hydrated = CachedOllamaTag(
                name = tagName,
                capabilities = show.capabilities,
                contextLength = ctxLen,
            )

            val priorTags = recheck?.let { _ ->
                cached?.tags ?: emptyList()
            } ?: cached?.tags ?: emptyList()
            val mergedTags = if (priorTags.any { it.name == tagName }) {
                priorTags.map { if (it.name == tagName) hydrated else it }
            } else {
                priorTags + hydrated
            }
            val now = System.currentTimeMillis()
            tagsCache.write(
                CachedOllamaTags(
                    baseUrl = cfg.baseUrl,
                    isCloud = cfg.isCloud,
                    tags = mergedTags,
                    fetchedAt = cached?.fetchedAt ?: now,
                ),
            )
            _specs.value = mergedTags
                .map { it.toModelSpec(isCloud = cfg.isCloud) }
                .sortedBy { it.displayName.lowercase() }
            _state.value = RemoteCatalogState.Ready(_specs.value, _fetchedAt.value ?: now)
        }
    }

    private fun stripIdPrefix(modelId: String): String? = when {
        modelId.startsWith("ollama-cloud:") -> modelId.removePrefix("ollama-cloud:")
        modelId.startsWith("ollama:") -> modelId.removePrefix("ollama:")
        else -> null
    }

    override suspend fun refreshCatalog() {
        val cfg = providerConfigs.ollamaConfigFlow.first() ?: return
        refreshInternal(cfg)
    }

    override suspend fun testConnection(): ConnectionTestResult = client.testConnection()

    override fun pullModel(name: String): Flow<PullProgress> = flow {
        client.pull(name.trim()).collect { chunk ->
            when {
                chunk.error != null -> emit(
                    PullProgress(status = "error", percent = null, terminal = true, error = chunk.error),
                )
                chunk.status == "success" -> {
                    emit(PullProgress(status = "success", percent = 1f, terminal = true))
                    refreshCatalog()
                }
                else -> {
                    val total = chunk.total ?: 0
                    val pct = if (total > 0 && chunk.completed != null)
                        chunk.completed.toFloat() / total else null
                    emit(
                        PullProgress(
                            status = chunk.status ?: "…",
                            percent = pct,
                            terminal = false,
                        ),
                    )
                }
            }
        }
    }

    private fun CachedOllamaTag.toModelSpec(isCloud: Boolean): ModelSpec =
        RemoteCatalog.ollamaSpec(
            tagName = name,
            isCloud = isCloud,
            capabilities = capabilities,
            contextLength = contextLength,
        )

    private companion object {
        private const val TAG = "OllamaManagement"
    }
}
