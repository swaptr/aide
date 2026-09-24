package com.sabreware.aide.core.domain.model

import com.sabreware.aide.core.common.persist.DocState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for the per-model UI snapshot (LOCAL catalog + per-provider remote feeds merged).
 * Domain-facing contract; the implementation ([com.sabreware.aide.data.model.ModelRegistryRepositoryImpl])
 * owns the catalog/storage/prefs wiring and is constructed for one [Modality].
 *
 * Listing is modality-generic — a [ModelSummary] carries whatever [ModelSpec] the registry holds — while the
 * members that only make sense for a conversation ([modelMetadata], [gateStateFlow]) are chat-typed.
 */
interface ModelRegistryRepository {
    /** True while [provider]'s remote catalog is mid-refresh; always-false for providers without one. */
    fun refreshing(provider: ProviderId): StateFlow<Boolean>

    /** Epoch-ms of [provider]'s last successful catalog fetch, or null. */
    fun catalogFetchedAt(provider: ProviderId): StateFlow<Long?>

    /** Per-tier last-used; survives restarts so tier-flips restore the right pick. */
    val lastUsedByTier: StateFlow<Map<ProviderTier, String>>
    val lastUsedModelIdFlow: StateFlow<String?>

    /** Force a catalog refresh for [provider], bypassing any TTL. No-op when unconfigured. */
    fun refresh(provider: ProviderId)

    /**
     * The per-model UI snapshot, cached app-wide: null until the session's first resolution, then the
     * latest list. A StateFlow so a surface seeds its UI state synchronously from [StateFlow.value]
     * (`stateInUiCached`) and re-opens with content instead of a skeleton; collecting restarts the shared
     * upstream, which re-stats the disk and refreshes the snapshot behind whatever was replayed.
     *
     * "Resolved" means every remote provider has reported — not merely that the local catalog was stat'ed.
     * A provider still reading its stored config contributes no specs, and answering from that picture said
     * "no such model" about a configured cloud model, which is what flashed "No model" in the chat header
     * on every cold start. Consumers keep their skeleton while this is null.
     */
    val models: StateFlow<List<ModelSummary>?>

    /**
     * The user's model choices, read from disk at process start — see [ModelSelection]. What every surface
     * PAINTS before [models] settles: the chat header's name, whether anything is picked at all.
     *
     * Deliberately not derived from [models]: it exists precisely because [models] is slow to resolve on a
     * cold start. [DocState.Loading] lasts one small file read; after that, `lastUsedModelId == null` is a
     * settled answer (nothing here ever picks a model on the user's behalf), while a named card is only
     * something to draw — acting on it waits for [gateStateFlow].
     */
    val selection: StateFlow<DocState<ModelSelection>>

    /** Cross-provider spec lookup. Includes LOCAL catalog and live Ollama tags. */
    fun findSpec(id: String): ModelSpec?

    /**
     * Rich metadata for the model detail sheet — the owning provider serves it (Ollama /api/show →
     * models.dev), falling back to spec-derived facts for local models / unknown remote ids. Chat-typed:
     * [ModelMetadata] is built around [ChatCapabilities], so another modality brings its own.
     */
    suspend fun modelMetadata(spec: ChatModelSpec): ModelMetadata

    /**
     * What [modelMetadata] can answer WITHOUT waiting — the last fetched result this session, else the facts
     * derived from the spec — so the detail page's first frame has rows and a re-open skips the fetch wait.
     */
    fun metadataSnapshot(spec: ChatModelSpec): ModelMetadata

    /** The last-used model while it is usable, else null — [effectiveModelId] over the live snapshot. */
    val effectiveLastUsedModelIdFlow: Flow<String?>

    /**
     * Can the app hold a conversation right now, with the model the user chose? Resolves only that model:
     * a local pick is checked against the disk alone and never waits on a cloud provider; a remote pick
     * waits on its own provider, not on all of them. See [ModelGateState].
     */
    val gateStateFlow: StateFlow<ModelGateState>

    /**
     * [gateStateFlow]'s answer for one specific model — the chat header's chosen model rather than the
     * modality's active slot. Same scoping: only that model's own source is consulted.
     */
    fun resolve(id: String): Flow<ModelGateState>

    suspend fun setDefaultModelId(id: String): Boolean
    suspend fun clearDefaultModelId()

    /** Clears the global default pref *only when* it currently points at [id]. */
    suspend fun clearDefaultIfMatches(id: String)

    suspend fun recordUsed(spec: ModelSpec)
    suspend fun recordSelected(spec: ModelSpec)

    /**
     * Makes [spec] the user's pick once its download completes — a download the user started IS the choice.
     * Runs on the app scope, so it outlives the surface that started the download; a failed or cancelled
     * download records nothing.
     */
    fun selectWhenDownloaded(spec: ModelSpec)

    /** Remove [spec] from its tier's last-used slot. */
    suspend fun clearUsed(spec: ModelSpec)

    /** Drops the global last-used pointer when it currently names [id]. */
    suspend fun clearLastUsedIfMatches(id: String)
}

/**
 * The ONE rule for "which model is in use": the last-used id, if a downloaded row for it exists. Shared by the
 * registry's flow and by a surface seeding its first frame from the cached snapshot.
 */
fun effectiveModelId(lastUsedId: String?, rows: List<ModelSummary>): String? =
    if (lastUsedId.isNullOrBlank()) null
    else rows.firstOrNull { it.spec.id == lastUsedId && it.isDownloaded }?.spec?.id
