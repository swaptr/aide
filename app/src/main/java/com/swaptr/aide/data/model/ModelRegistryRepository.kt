package com.swaptr.aide.data.model

import com.swaptr.aide.data.catalog.ModelCatalog
import com.swaptr.aide.data.catalog.ModelSpec
import com.swaptr.aide.data.catalog.ProviderId
import com.swaptr.aide.data.catalog.ProviderTier
import com.swaptr.aide.data.download.DownloadStatus
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import com.swaptr.aide.data.storage.ModelStorage
import com.swaptr.aide.di.ApplicationScope
import com.swaptr.aide.domain.llm.RemoteCatalogState
import com.swaptr.aide.domain.llm.ollama.OllamaManagement
import com.swaptr.aide.domain.model.ModelSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

// Single source of truth for the per-model UI snapshot; LOCAL catalog + per-provider
// remote feeds merged through abstract RemoteCatalogState.
@Singleton
class ModelRegistryRepository @Inject constructor(
    private val downloadRepo: ModelDownloadRepository,
    private val engineRepo: LlmEngineRepository,
    private val storage: ModelStorage,
    private val prefs: UserPreferencesRepository,
    private val ollamaManagement: OllamaManagement,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    val ollamaRefreshing: StateFlow<Boolean> = ollamaManagement.refreshingFlow
    val ollamaTagsFetchedAt: StateFlow<Long?> = ollamaManagement.fetchedAtFlow

    private val ollamaSpecs: StateFlow<List<ModelSpec>> = ollamaManagement.specsFlow

    // Per-tier last-used; survives restarts so tier-flips restore the right pick.
    val lastUsedByTier: StateFlow<Map<ProviderTier, String>> =
        prefs.lastUsedByTierJsonFlow
            .map(::decodeTierMap)
            .stateIn(appScope, SharingStarted.Eagerly, emptyMap())

    // Hot-shared so chat VM can read .value synchronously when seeding the draft picker.
    val lastUsedModelIdFlow: StateFlow<String?> =
        prefs.lastUsedModelIdFlow
            .stateIn(appScope, SharingStarted.Eagerly, null)

    /** Force a `/api/tags` refresh, bypassing the TTL. No-op when the provider is unconfigured. */
    fun refreshOllamaTags() {
        appScope.launch { ollamaManagement.refreshCatalog() }
    }

    private val loadedFlow: Flow<Pair<String?, com.swaptr.aide.data.catalog.Accelerator?>> =
        combine(engineRepo.loadedModelIdFlow, engineRepo.loadedAcceleratorFlow) { id, acc -> id to acc }

    fun observeModels(): Flow<List<ModelSummary>> = combine(
        downloadRepo.observeAllStatuses(),
        loadedFlow,
        prefs.defaultModelIdFlow,
        ollamaSpecs,
        lastUsedByTier,
    ) { statuses, loaded, prefDefault, ollamaSpecs, lastUsed ->
        val (loadedId, loadedAcc) = loaded
        val inUseIds = lastUsed.values.toSet()
        val out = mutableListOf<ModelSummary>()

        for (spec in ModelCatalog.models) {
            val rawStatus = statuses[spec.id] ?: DownloadStatus.Idle(spec.id)
            // Disk authoritative; stale SUCCEEDED records can outlive deleted weights.
            val onDisk = storage.isDownloaded(spec)
            val downloaded = onDisk
            val effectiveStatus = when {
                rawStatus is DownloadStatus.InProgress -> rawStatus
                onDisk -> DownloadStatus.Completed(spec.id)
                rawStatus is DownloadStatus.Completed -> DownloadStatus.Idle(spec.id)
                else -> rawStatus
            }
            val isLoaded = loadedId == spec.id
            out += ModelSummary(
                spec = spec,
                downloadStatus = effectiveStatus,
                isLoadedInEngine = isLoaded,
                isDefault = prefDefault == spec.id && downloaded,
                isInUse = spec.id in inUseIds && downloaded,
                loadedAccelerator = if (isLoaded) loadedAcc else null,
            )
        }

        for (spec in ollamaSpecs) {
            out += ModelSummary(
                spec = spec,
                downloadStatus = DownloadStatus.Completed(spec.id),
                isLoadedInEngine = loadedId == spec.id,
                isDefault = prefDefault == spec.id,
                isInUse = spec.id in inUseIds,
            )
        }

        out
    }

    /** Cross-provider spec lookup. Includes LOCAL catalog and live Ollama tags. */
    fun findSpec(id: String): ModelSpec? =
        ModelCatalog.findById(id)
            ?: ollamaSpecs.value.firstOrNull { it.id == id }

    // Null when pref stale/unset or model unavailable (not downloaded / provider unconfigured).
    val effectiveDefaultModelIdFlow: Flow<String?> = observeModels().map { rows ->
        rows.firstOrNull { it.isDefault }?.spec?.id
    }

    // Top priority in draft auto-pick / new-chat resolution (before effectiveDefault); gated on availability.
    val effectiveLastUsedModelIdFlow: Flow<String?> = combine(
        lastUsedModelIdFlow,
        observeModels(),
    ) { id, rows ->
        if (id.isNullOrBlank()) null
        else rows.firstOrNull { it.spec.id == id && it.isDownloaded }?.spec?.id
    }

    // Provider availability alone is not enough — user must have an active selection;
    // reachable Ollama or downloaded weight without `Use` tap stays NoModel.
    val gateStateFlow: StateFlow<ModelGateState> = combine(
        downloadRepo.observeAllStatuses(),
        engineRepo.loadedModelIdFlow,
        prefs.defaultModelIdFlow,
        ollamaManagement.remoteCatalogFlow,
        ollamaSpecs,
    ) { statuses, loadedId, prefDefault, ollamaCatalog, ollamaSpecs ->
        resolveGate(statuses, loadedId, prefDefault, ollamaCatalog, ollamaSpecs)
    }.stateIn(appScope, SharingStarted.Eagerly, ModelGateState.NoModel)

    private fun resolveGate(
        statuses: Map<String, DownloadStatus>,
        loadedId: String?,
        prefDefault: String?,
        ollamaCatalog: RemoteCatalogState,
        ollamaSpecs: List<ModelSpec>,
    ): ModelGateState {
        val ollamaConfigured = ollamaCatalog !is RemoteCatalogState.Unconfigured

        loadedId?.let { id ->
            findSpec(id)?.let { return ModelGateState.Ready(it) }
        }

        prefDefault?.let { id ->
            findSpec(id)?.let { spec ->
                val ok = when (spec.provider) {
                    ProviderId.LOCAL -> storage.isDownloaded(spec)
                    ProviderId.OLLAMA -> ollamaConfigured && ollamaSpecs.any { it.id == id }
                }
                if (ok) return ModelGateState.Ready(spec)
            }
        }

        var pending: Pair<ModelSpec, Float>? = null
        for (spec in ModelCatalog.models) {
            when (val s = statuses[spec.id]) {
                is DownloadStatus.InProgress -> return ModelGateState.Downloading(spec, s.progress)
                is DownloadStatus.Queued, is DownloadStatus.Paused -> {
                    if (pending == null) pending = spec to 0f
                }
                else -> Unit
            }
        }
        pending?.let { (spec, p) -> return ModelGateState.Downloading(spec, p) }
        return ModelGateState.NoModel
    }

    // Rejects unknown ids and unconfigured/undownloaded providers.
    suspend fun setDefaultModelId(id: String): Boolean {
        val spec = findSpec(id) ?: return false
        val ok = when (spec.provider) {
            ProviderId.LOCAL -> storage.isDownloaded(spec)
            ProviderId.OLLAMA -> {
                val state = ollamaManagement.remoteCatalogFlow.first()
                state !is RemoteCatalogState.Unconfigured && ollamaSpecs.value.any { it.id == id }
            }
        }
        if (!ok) return false
        prefs.setDefaultModelId(id)
        return true
    }

    suspend fun clearDefaultModelId() = prefs.clearDefaultModelId()

    /** Clears the global default pref *only when* it currently points at [id]. */
    suspend fun clearDefaultIfMatches(id: String) {
        val current = prefs.defaultModelIdFlow.first()
        if (current == id) prefs.clearDefaultModelId()
    }

    // No-op on unchanged value to save a DataStore write per send in the steady state.
    suspend fun recordUsed(spec: ModelSpec) {
        val tier = ProviderTier.of(spec)
        val current = lastUsedByTier.value
        if (current[tier] != spec.id) {
            val next = current + (tier to spec.id)
            prefs.setLastUsedByTierJson(encodeTierMap(next))
        }
        if (lastUsedModelIdFlow.value != spec.id) {
            prefs.setLastUsedModelId(spec.id)
        }
    }

    // Called on picker selection (not send) so choice survives an unsent app close.
    suspend fun recordSelected(spec: ModelSpec) = recordUsed(spec)

    /** Remove [spec] from its tier's last-used slot. */
    suspend fun clearUsed(spec: ModelSpec) {
        val tier = ProviderTier.of(spec)
        val current = lastUsedByTier.value
        if (current[tier] != spec.id) return
        val next = current - tier
        prefs.setLastUsedByTierJson(encodeTierMap(next))
    }

    /** Drops the global last-used pointer when it currently names [id]. */
    suspend fun clearLastUsedIfMatches(id: String) {
        if (lastUsedModelIdFlow.value == id) prefs.clearLastUsedModelId()
    }

    private fun encodeTierMap(map: Map<ProviderTier, String>): String =
        TIER_JSON.encodeToString(TIER_MAP_SERIALIZER, map.mapKeys { it.key.name })

    private fun decodeTierMap(raw: String): Map<ProviderTier, String> =
        runCatching { TIER_JSON.decodeFromString(TIER_MAP_SERIALIZER, raw) }
            .getOrDefault(emptyMap())
            .mapNotNull { (k, v) ->
                runCatching { ProviderTier.valueOf(k) }.getOrNull()?.let { it to v }
            }
            .toMap()

    companion object {
        private val TIER_JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val TIER_MAP_SERIALIZER =
            MapSerializer(String.serializer(), String.serializer())
    }
}
