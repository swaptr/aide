package com.sabreware.aide.data.model
import com.sabreware.aide.core.domain.cache.snapshotCache
import com.sabreware.aide.core.domain.chat.Chat
import com.sabreware.aide.core.domain.model.Accelerator
import com.sabreware.aide.core.common.persist.DocState
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.domain.model.ModelCard
import com.sabreware.aide.core.domain.model.ModelFallback
import com.sabreware.aide.core.domain.model.ModelFallbackPrefs
import com.sabreware.aide.core.domain.model.ModelSelection
import com.sabreware.aide.core.domain.model.ModelSelectionStore
import com.sabreware.aide.core.domain.model.activeModelFor
import com.sabreware.aide.core.domain.model.selections
import com.sabreware.aide.core.domain.llm.LlmEngineRepository
import com.sabreware.aide.core.domain.model.ModelGateState
import com.sabreware.aide.core.domain.model.effectiveModelId

import com.sabreware.aide.core.domain.catalog.ModelCatalog
import com.sabreware.aide.core.domain.model.MetadataSource
import com.sabreware.aide.core.domain.model.ModelDetail
import com.sabreware.aide.core.domain.model.ModelMetadata
import com.sabreware.aide.core.domain.model.ModelModality
import com.sabreware.aide.core.domain.model.ModelSpec
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ProviderCatalog
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.model.ProviderTier
import com.sabreware.aide.core.domain.download.AssetKind
import com.sabreware.aide.core.domain.download.DownloadScheduler
import com.sabreware.aide.core.domain.download.DownloadStatus
import com.sabreware.aide.core.domain.download.awaitCompletion
import com.sabreware.aide.core.domain.model.ModelStorage
import com.sabreware.aide.core.domain.llm.Manageable
import com.sabreware.aide.core.domain.llm.ManageableRegistry
import com.sabreware.aide.core.domain.llm.RemoteCatalogState
import com.sabreware.aide.core.domain.llm.currentSpecs
import com.sabreware.aide.core.domain.llm.isSettled
import com.sabreware.aide.core.domain.model.ModelSummary
import com.sabreware.aide.core.domain.model.named
import com.sabreware.aide.core.domain.label.LabelStore
import com.sabreware.aide.core.domain.connection.ProviderDirectory
import com.sabreware.aide.core.domain.connection.ProviderInfo
import com.sabreware.aide.core.domain.label.LabelSubject
import com.sabreware.aide.core.domain.label.Labels
import com.sabreware.aide.core.domain.label.labels
import com.sabreware.aide.core.domain.label.labelsNow
import com.sabreware.aide.core.domain.model.ModelRegistryRepository
import com.sabreware.aide.core.domain.model.ModelImportRepository
import com.sabreware.aide.core.domain.model.toSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Single source of truth for the per-model UI snapshot; LOCAL catalog + every provider's remote
// feed merged through the abstract RemoteCatalogState — no concrete provider dependency.
class ModelRegistryRepositoryImpl(
    private val scheduler: DownloadScheduler,
    private val engineRepo: LlmEngineRepository,
    private val storage: ModelStorage,
    /** The user's choices — the document every "which model" answer is read from and written to. */
    private val selectionStore: ModelSelectionStore,
    private val importRepo: ModelImportRepository,
    private val modelCatalog: ModelCatalog,
    // Contribution registry of providers with a remote catalog — no allProviders()/filterIsInstance.
    private val manageables: ManageableRegistry,
    private val appScope: CoroutineScope,
    /**
     * Which modality's registry this is. The active-model choice is already stored per modality
     * ([ModelSelection.activeByModality]); this is what stops the repository from being the only thing in the
     * chain that assumes chat. A second modality is a second instance, not a second class.
     */
    private val modality: Modality = Modality.Chat,
    /** Settings the resolution honours — today [ModelFallbackPrefs.Policy]. */
    private val prefs: PreferenceStore,
    /**
     * Which manageable providers actually serve [modality].
     *
     * [Manageable] cross-cuts modality on purpose — a vendor with a refreshable catalog may serve images,
     * transcription or embeddings and never a text turn — so "every provider with a catalog" is the wrong
     * set for any one registry. Waiting on all of them means the chat header holds its skeleton open for a
     * catalog no chat turn can use, and reports "no model" if that catalog comes back empty.
     *
     * Injected as a predicate rather than resolved from a `when (modality)`: [Modality] is a value class
     * and deliberately open, so the modality→role-interface mapping is not a closed set this class could
     * enumerate. `:di` passes `{ it is ChatProvider }` for the chat registry; a second modality passes its
     * own role interface, which is the same shape as every other injected policy here.
     */
    private val serves: (Manageable) -> Boolean,
    /** The user's names for models: applied where rows are built, so every surface paints the same name. */
    private val labels: LabelStore,
    /** Who serves each model, by the name the user knows it by — stamped on each row with the alias. */
    private val directory: ProviderDirectory,
) : ModelRegistryRepository {

    // Each manageable provider's remote catalog, keyed by id — null until the connections have been read.
    //
    // The provider set is DYNAMIC: every cloud provider is one of the user's connections, added and removed at
    // runtime. Null is "the connections are not read yet", which is not "none" — reading it as none would
    // resolve every cloud pick as missing on a cold start. An empty list is settled-and-empty (flowOf, since
    // `combine` of nothing never emits and would pin the last connection's models on screen after removing
    // it). Runtimes are reused across edits that do not change the endpoint, so a rename re-subscribes nothing.
    private val providerCatalogs: StateFlow<Map<ProviderId, RemoteCatalogState>?> =
        manageables.flow
            .map { providers -> providers?.filter(serves) }
            // Instances, not ids: a connection moved to another endpoint is a new runtime under the same id.
            .distinctUntilChanged()
            .flatMapLatest { providers ->
                when {
                    providers == null -> flowOf(null)
                    providers.isEmpty() -> flowOf(emptyMap())
                    else -> combine(providers.map { p -> p.management.remoteCatalogFlow.map { p.id to it } }) { it.toMap() }
                }
            }
            .stateIn(appScope, SharingStarted.Eagerly, null)

    // Merged remote specs across all providers (live or last-good per state) — null until every provider has
    // ANSWERED (see RemoteCatalogState.isSettled). A provider still reading its stored config, or mid-first-
    // fetch with no last-good behind it, contributes nothing, so answering from that picture says "no such
    // model" about a model that is configured fine; `models` propagates the null and surfaces keep their
    // skeleton for the extra disk read rather than flashing the wrong answer.
    //
    // Once settled it stays settled: a connection the user adds later starts mid-first-fetch, and re-nulling
    // the aggregate for it would put the chat header, the keyboard and the assistant back behind a skeleton
    // for a model none of them is using. The new connection's models land when its fetch does; a pick of one
    // of them still waits on that connection alone ([resolveOwn]).
    private val remoteSpecs: StateFlow<List<ChatModelSpec>?> = flow {
        var settledOnce = false
        providerCatalogs.collect { states ->
            if (states != null && (settledOnce || states.values.all(RemoteCatalogState::isSettled))) {
                settledOnce = true
                emit(states.values.flatMap(RemoteCatalogState::currentSpecs))
            } else if (!settledOnce) {
                emit(null)
            }
        }
    }.stateIn(appScope, SharingStarted.Eagerly, null)

    // User-imported local models (BYO files), merged alongside the bundled allowlist. Null until the import
    // list has been read: an imported pick asked about before then is "not yet", not "missing".
    private val importedSpecs: StateFlow<List<ChatModelSpec>?> =
        importRepo.observe()
            .map { entries -> entries.map { it.toSpec() } }
            .stateIn(appScope, SharingStarted.Eagerly, null)

    // The user's labels, as the rows need them. Empty until read: a name is painting, and the snapshot
    // re-emits the moment the file lands (a few ms), so no row waits on it.
    private val labelsFlow: StateFlow<Labels> = labels.labels.stateIn(appScope, SharingStarted.Eagerly, labels.labelsNow)

    /** [spec] under the user's name for it, if they gave one. */
    private fun named(spec: ChatModelSpec, current: Labels = labelsFlow.value): ChatModelSpec =
        current[LabelSubject.model(spec.id)].alias?.let(spec::named) ?: spec

    private class Sources(
        val remote: List<ChatModelSpec>?,
        val imported: List<ChatModelSpec>,
        val labels: Labels,
        val sources: Map<String, ProviderInfo>?,
    )

    // Grouped so observeModels stays a 5-input combine (every source of a row's content in one slot).
    private val sourcesAndLabels: Flow<Sources> =
        combine(remoteSpecs, importedSpecs.filterNotNull(), labelsFlow, directory.connections, ::Sources)

    // Derived per call rather than precomputed per provider: the provider set is dynamic, so a map built at
    // construction would answer "not refreshing / never fetched" forever for every connection added since.
    // Only provider-settings UI asks, and it stops collecting when it closes.
    override fun refreshing(provider: ProviderId): StateFlow<Boolean> =
        providerCatalogs
            .map { it?.get(provider) is RemoteCatalogState.Refreshing }
            .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), providerCatalogs.value?.get(provider) is RemoteCatalogState.Refreshing)

    override fun catalogFetchedAt(provider: ProviderId): StateFlow<Long?> =
        providerCatalogs
            .map { (it?.get(provider) as? RemoteCatalogState.Ready)?.fetchedAt }
            .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), (providerCatalogs.value?.get(provider) as? RemoteCatalogState.Ready)?.fetchedAt)

    override val selection: StateFlow<DocState<ModelSelection>> get() = selectionStore.state

    // The choice document is a startup document — in memory before any surface draws — so these seed from
    // it rather than from an empty map / null that reads as "nothing chosen" until the next dispatch.
    private val selectionNow: ModelSelection?
        get() = (selectionStore.state.value as? DocState.Ready)?.value

    // Per-tier last-used; survives restarts so tier-flips restore the right pick.
    override val lastUsedByTier: StateFlow<Map<ProviderTier, String>> =
        selectionStore.selections
            .map(::byTier)
            .stateIn(appScope, SharingStarted.Eagerly, selectionNow?.let(::byTier) ?: emptyMap())

    private fun byTier(sel: ModelSelection) = sel.lastUsedByTier.entries.associate { (key, id) -> ProviderTier(key) to id }

    // Hot-shared so chat VM can read .value synchronously when seeding the draft picker.
    override val lastUsedModelIdFlow: StateFlow<String?> =
        selectionStore.selections
            .map { it.lastUsedModelId }
            .stateIn(appScope, SharingStarted.Eagerly, selectionNow?.lastUsedModelId)

    /** Force a catalog refresh for [provider], bypassing any TTL. No-op when unconfigured/unknown. */
    override fun refresh(provider: ProviderId) {
        appScope.launch {
            runCatching { manageables[provider]?.management?.refreshCatalog() }
        }
    }

    /**
     * Combined [DownloadStatus] for every catalog entry, keyed by model id — one scheduler observation per
     * entry rather than a model-specific download repository whose only job was to fan these out.
     */
    private fun observeDownloadStatuses(): Flow<Map<String, DownloadStatus>> {
        val catalog = modelCatalog.models
        if (catalog.isEmpty()) return flowOf(emptyMap())
        return combine(catalog.map { scheduler.observe(AssetKind.MODEL, it.id) }) { statuses ->
            statuses.associateBy { it.modelId }
        }
            // Emit "nothing in flight" up front so this never gates the snapshot. Each observation is a
            // WorkManager query plus two disk stats, `combine` waits for ALL of them, and the snapshot they
            // gate answers "which model is active" — a question no download progress bears on. Whether a
            // model is USABLE is decided from disk below (`onDisk`), which is authoritative and cheap; a
            // transfer that is genuinely running simply lands a beat later and repaints its own row.
            .onStart { emit(emptyMap()) }
    }

    // ONE fan-out (a scheduler flow + disk stats per catalog model) shared by the snapshot, the gate and every
    // per-model resolve, instead of a fresh N-way combine each — the gate alone keeps it live all session.
    private val downloadStatuses: Flow<Map<String, DownloadStatus>> =
        observeDownloadStatuses().shareIn(appScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val loadedFlow: Flow<Pair<String?, com.sabreware.aide.core.domain.model.Accelerator?>> =
        combine(engineRepo.loadedModelIdFlow, engineRepo.loadedAcceleratorFlow) { id, acc -> id to acc }

    override val models: StateFlow<List<ModelSummary>?> = combine(
        downloadStatuses,
        loadedFlow,
        selectionStore.activeModelFor(modality),
        sourcesAndLabels,
        lastUsedByTier,
    ) { statuses, loaded, prefDefault, sources, lastUsed ->
        val remote = sources.remote
        val imported = sources.imported
        fun ModelSummary.labelled(): ModelSummary {
            val source = sources.sources?.get(spec.provider.value)?.name
            val alias = sources.labels[LabelSubject.model(spec.id)].alias
            val chat = spec as? ChatModelSpec
            return if (alias == null || chat == null) copy(sourceName = source)
            else copy(spec = chat.named(alias), originalName = spec.displayName, sourceName = source)
        }
        // Still null = a provider has not reported yet. Stay unresolved rather than publish a snapshot that
        // omits a configured provider's models — that is what flashed "No model" in the chat header.
        if (remote == null) return@combine null
        val (loadedId, loadedAcc) = loaded
        val inUseIds = lastUsed.values.toSet()
        val out = mutableListOf<ModelSummary>()

        for (spec in modelCatalog.models) {
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
                updateAvailable = storage.hasUpdate(spec),
            )
        }

        for (spec in remote) {
            // requiresDownload=false (Ollama, server-hosted) → always available; true (future
            // on-disk feeds) → disk-authoritative, so we never mint "installed" rows with nothing
            // on disk. Keyed off the spec, NOT off which provider fed it.
            val onDisk = !spec.requiresDownload || storage.isDownloaded(spec)
            val rawStatus = statuses[spec.id]
            val effectiveStatus = when {
                rawStatus is DownloadStatus.InProgress -> rawStatus
                onDisk -> DownloadStatus.Completed(spec.id)
                rawStatus is DownloadStatus.Completed -> DownloadStatus.Idle(spec.id)
                rawStatus != null -> rawStatus
                else -> DownloadStatus.Idle(spec.id)
            }
            out += ModelSummary(
                spec = spec,
                downloadStatus = effectiveStatus,
                isLoadedInEngine = loadedId == spec.id,
                isDefault = prefDefault == spec.id && onDisk,
                isInUse = spec.id in inUseIds && onDisk,
            )
        }

        // Imported local models: on disk (no download), so probe presence via the storage port.
        for (spec in imported) {
            val present = storage.isPresent(spec)
            out += ModelSummary(
                spec = spec,
                downloadStatus = if (present) DownloadStatus.Completed(spec.id) else DownloadStatus.Idle(spec.id),
                isLoadedInEngine = loadedId == spec.id,
                isDefault = prefDefault == spec.id && present,
                isInUse = spec.id in inUseIds && present,
                loadedAccelerator = if (loadedId == spec.id) loadedAcc else null,
            )
        }

        out.map { it.labelled() }
    }
        // A settled answer is the truth, so it refreshes what the next cold start paints: the names on the
        // user's chosen cards (a renamed model). The write is a no-op unless something changed. Riding this flow rather than a standing collector is deliberate —
        // it costs nothing until a surface is actually watching the registry.
        .onEach { rows -> if (rows != null) onSettled(rows) }
        // Each emission stats model files on disk and folds WorkManager snapshots — never let a collector
        // (the chat VM collects this on Main.immediate during first composition) run that inline.
        .flowOn(Dispatchers.Default)
        .snapshotCache(appScope, "models")

    // Covariant override: the port promises a ModelSpec, and every source this registry merges — the
    // bundled LLM allowlist, imported LiteRT weights, a Manageable provider's remote catalog — is a chat
    // model, so callers that need one get it without narrowing.
    /** Cross-provider spec lookup. Includes the LOCAL catalog and live remote feeds. */
    // Remote ids are looked up per provider, NOT through the all-settled `remoteSpecs`: a spec a provider has
    // already published is real whether or not some other vendor has answered yet, and waiting for all of
    // them is what held a send to one provider behind every other provider's catalog.
    override fun findSpec(id: String): ChatModelSpec? =
        (
            modelCatalog.findById(id)
                ?: importedSpecs.value?.firstOrNull { it.id == id }
                ?: providerCatalogs.value?.values?.firstNotNullOfOrNull { state ->
                    state.currentSpecs.firstOrNull { it.id == id }
                }
            )?.let { named(it) }

    // Last fetched metadata per model id, for the session: an Ollama /api/show is a network round trip.
    private val metadataCache = MutableStateFlow<Map<String, ModelMetadata>>(emptyMap())

    override suspend fun modelMetadata(spec: ChatModelSpec): ModelMetadata {
        val remoteName = spec.remoteName
        val fetched = remoteName?.let {
            runCatching { manageables.await(spec.provider)?.management?.modelMetadata(it) }.getOrNull()
        } ?: return spec.fallbackMetadata()
        metadataCache.update { it + (spec.id to fetched) }
        return fetched
    }

    override fun metadataSnapshot(spec: ChatModelSpec): ModelMetadata =
        metadataCache.value[spec.id] ?: spec.fallbackMetadata()

    // Local (allowlist-described) models + any remote registry miss: derive metadata from the spec itself.
    private fun ChatModelSpec.fallbackMetadata(): ModelMetadata = ModelMetadata(
        capabilities = capabilities,
        detail = ModelDetail(
            family = family.takeIf { it.isNotBlank() },
            parameterSize = params.takeIf { it.isNotBlank() },
            quantization = quantization.takeIf { it.isNotBlank() && it != "(cloud)" },
            contextTokens = capabilities.maxContext.takeIf { it > 0 },
            maxOutputTokens = capabilities.maxOutput.takeIf { it > 0 },
            inputModalities = buildSet {
                add(ModelModality.Text)
                if (capabilities.visionIn) add(ModelModality.Image)
                if (capabilities.audioIn) add(ModelModality.Audio)
            },
            license = licenseName.takeIf { it.isNotBlank() && it != "(provider terms)" },
            sizeBytes = sizeBytes,
            source = if (remoteName != null) MetadataSource.NEUTRAL else MetadataSource.ALLOWLIST,
        ),
        samplerDefaults = defaultConfig,
    )

    private suspend fun onSettled(rows: List<ModelSummary>) {
        selectionStore.update { sel -> rows.fold(sel) { acc, row -> acc.withCard(ModelCard.of(row.spec)) } }
    }


    // Top priority in draft auto-pick / new-chat resolution (before effectiveDefault); gated on availability.
    // A pick that is no longer usable stays RECORDED (it is the user's choice, and the header says it is
    // missing); it just is not an id anything may act on.
    override val effectiveLastUsedModelIdFlow: Flow<String?> =
        combine(lastUsedModelIdFlow, models.filterNotNull(), ::effectiveModelId)

    // Scoped to the ONE model the user chose for this modality. The old gate waited for every provider to
    // settle — Keystore decrypts, the models.dev parse, sometimes the network — before it would say anything,
    // so a downloaded on-device model sat behind a cloud catalog it has nothing to do with.
    private val fallbackPolicy: Flow<ModelFallback> = prefs.flow(ModelFallbackPrefs.Policy)

    private val choiceAndPolicy: Flow<Pair<DocState<ModelSelection>, ModelFallback>> =
        combine(selectionStore.state, fallbackPolicy) { sel, policy -> sel to policy }

    override val gateStateFlow: StateFlow<ModelGateState> = combine(
        choiceAndPolicy,
        engineRepo.loadedModelIdFlow,
        importedSpecs,
        providerCatalogs,
        downloadStatuses,
    ) { (sel, policy), loadedId, imported, catalogs, statuses ->
        resolveGate(sel, policy, loadedId, imported, catalogs, statuses)
    }.stateIn(appScope, SharingStarted.Eagerly, ModelGateState.Unresolved)

    override fun resolve(id: String): Flow<ModelGateState> = combine(
        selectionStore.selections,
        fallbackPolicy,
        importedSpecs,
        providerCatalogs,
        downloadStatuses,
    ) { sel, policy, imported, catalogs, statuses ->
        resolveChosen(id, sel, policy, imported, catalogs, statuses)
    }.distinctUntilChanged()

    private suspend fun resolveGate(
        sel: DocState<ModelSelection>,
        policy: ModelFallback,
        loadedId: String?,
        imported: List<ChatModelSpec>?,
        catalogs: Map<ProviderId, RemoteCatalogState>?,
        statuses: Map<String, DownloadStatus>,
    ): ModelGateState {
        val selection = (sel as? DocState.Ready)?.value ?: return ModelGateState.Unresolved

        // A model already in memory is a fast path ONLY when it is the one chosen. Answering with whatever
        // the engine happens to hold (a keep-alive after a switch, the model the user just lost) would put a
        // model the user did not pick behind every surface without a word.
        val chosen = selection.chosenFor(modality)
        loadedId?.takeIf { it == chosen }?.let { id ->
            findSpec(id)?.let { return ModelGateState.Ready(it) }
        }

        // Provider availability alone is not enough — the user must have chosen; a reachable provider or a
        // downloaded weight without a `Use` tap stays NoModel. Nothing here ever chooses on their behalf.
        chosen?.let { id -> return resolveChosen(id, selection, policy, imported, catalogs, statuses) }

        downloadingIn(statuses)?.let { return it }
        return ModelGateState.NoModel
    }

    private suspend fun resolveChosen(
        id: String,
        selection: ModelSelection,
        policy: ModelFallback,
        imported: List<ChatModelSpec>?,
        catalogs: Map<ProviderId, RemoteCatalogState>?,
        statuses: Map<String, DownloadStatus>,
    ): ModelGateState {
        val card = selection.cards[id]
        return when (val own = resolveOwn(id, card, imported, catalogs, statuses)) {
            is ModelGateState.Missing -> substituteFor(own.card, selection, policy, imported, catalogs) ?: own
            else -> own
        }
    }

    /**
     * A usable stand-in for [missing], or null. Only ever reached from a SETTLED "missing" — never while the
     * chosen model's source is still answering — and only as far as the user's [policy] allows. Candidates
     * the user has already picked come first (their other active/last-used models), then everything usable;
     * a same-kind candidate (on-device for on-device) is always preferred, even under [ModelFallback.AnyModel].
     * The choice itself is untouched: the moment [missing] is usable again, it is the answer again.
     */
    private suspend fun substituteFor(
        missing: ModelCard,
        selection: ModelSelection,
        policy: ModelFallback,
        imported: List<ChatModelSpec>?,
        catalogs: Map<ProviderId, RemoteCatalogState>?,
    ): ModelGateState? {
        if (policy == ModelFallback.Never) return null
        val local = modelCatalog.models + imported.orEmpty()
        // Remote candidates come only from providers that have answered and are configured: a substitute is
        // an answer, so it cannot be built from a catalog still loading.
        val remote = catalogs.orEmpty().filter { (provider, state) -> state.isSettled && isConfigured(catalogs, provider) }
            .values.flatMap(RemoteCatalogState::currentSpecs)
        val everything = local + remote
        val picked = (listOfNotNull(selection.chosenFor(modality), selection.activeFor(modality)) +
            selection.lastUsedByTier.values)
            .mapNotNull { id -> everything.firstOrNull { it.id == id } }
        val pool = (picked + everything).distinctBy { it.id }.filter { it.id != missing.id }
        val (sameKind, otherKind) = pool.partition { isLocal(it) == missing.local }
        val ordered = if (policy == ModelFallback.SameKind) sameKind else sameKind + otherKind
        val spec = ordered.firstOrNull { !isLocal(it) || storage.isDownloaded(it) } ?: return null
        return ModelGateState.Ready(named(spec), reroutedFrom = missing)
    }

    private fun isLocal(spec: ChatModelSpec): Boolean = ProviderCatalog.of(spec.provider).local

    /** The chosen model on its own terms: Ready, Downloading, Missing, or Unresolved while its source answers. */
    private suspend fun resolveOwn(
        id: String,
        card: ModelCard?,
        imported: List<ChatModelSpec>?,
        catalogs: Map<ProviderId, RemoteCatalogState>?,
        statuses: Map<String, DownloadStatus>,
    ): ModelGateState {
        val bundled = modelCatalog.findById(id)
        val isLocal = bundled != null || card?.local == true || imported?.any { it.id == id } == true
        return if (isLocal) resolveLocal(id, card, bundled, imported, statuses) else resolveRemote(id, card, catalogs)
    }

    private suspend fun resolveLocal(
        id: String,
        card: ModelCard?,
        bundled: ChatModelSpec?,
        imported: List<ChatModelSpec>?,
        statuses: Map<String, DownloadStatus>,
    ): ModelGateState {
        // On-device: the disk is the whole answer. No provider, catalog or network is consulted.
        val spec = bundled ?: imported?.firstOrNull { it.id == id }
            ?: return if (imported == null) ModelGateState.Unresolved else ModelGateState.Missing(card ?: ModelCard(id))
        if (storage.isDownloaded(spec)) return ModelGateState.Ready(named(spec))
        // Ask the scheduler about THIS model directly: the combined status map starts empty (so it never
        // gates anything), and reading that seed as "not downloading" briefly called a model mid-download
        // missing — long enough for a reroute to answer in its place.
        val status = statuses[id] ?: scheduler.observe(AssetKind.MODEL, id).first()
        return when (status) {
            is DownloadStatus.InProgress -> ModelGateState.Downloading(spec, status.progress)
            is DownloadStatus.Queued, is DownloadStatus.Paused -> ModelGateState.Downloading(spec, 0f)
            else -> ModelGateState.Missing(card ?: ModelCard.of(spec))
        }
    }

    private fun resolveRemote(id: String, card: ModelCard?, catalogs: Map<ProviderId, RemoteCatalogState>?): ModelGateState {
        // The connections are not read yet: whether this model's connection still exists is not known.
        if (catalogs == null) return ModelGateState.Unresolved
        // Remote: wait for THIS provider only. Without a card (never seen through this build) the provider is
        // not known yet, so fall back to waiting until every catalog has answered and look the id up.
        val provider = card?.providerId
            ?: if (catalogs.values.all(RemoteCatalogState::isSettled)) {
                catalogs.values.flatMap(RemoteCatalogState::currentSpecs).firstOrNull { it.id == id }?.provider
                    ?: return ModelGateState.Missing(ModelCard(id))
            } else return ModelGateState.Unresolved
        val state = catalogs[provider] ?: return ModelGateState.Missing(card ?: ModelCard(id))
        if (!state.isSettled) return ModelGateState.Unresolved
        val spec = state.currentSpecs.firstOrNull { it.id == id }
        return if (spec != null && isConfigured(catalogs, provider)) ModelGateState.Ready(named(spec))
        else ModelGateState.Missing(card ?: ModelCard(id))
    }

    /** A catalog download in flight ([only] narrows it to one model), as the gate reports it. */
    private fun downloadingIn(statuses: Map<String, DownloadStatus>): ModelGateState? {
        var pending: ChatModelSpec? = null
        for (spec in modelCatalog.models) {
            when (val s = statuses[spec.id]) {
                is DownloadStatus.InProgress -> return ModelGateState.Downloading(spec, s.progress)
                is DownloadStatus.Queued, is DownloadStatus.Paused -> if (pending == null) pending = spec
                else -> Unit
            }
        }
        return pending?.let { ModelGateState.Downloading(it, 0f) }
    }

    // Unknown counts as NOT configured: it means the provider has not read its stored config yet, so
    // claiming otherwise would let a gate resolve Ready off a catalog that is still empty.
    private fun isConfigured(catalogs: Map<ProviderId, RemoteCatalogState>?, provider: ProviderId): Boolean =
        when (catalogs?.get(provider)) {
            null, RemoteCatalogState.Unknown, RemoteCatalogState.Unconfigured -> false
            else -> true
        }

    // Rejects unknown ids and unconfigured/undownloaded providers.
    override suspend fun setDefaultModelId(id: String): Boolean {
        val spec = findSpec(id) ?: return false
        val ok = if (ProviderCatalog.of(spec.provider).local) {
            storage.isDownloaded(spec)
        } else {
            isConfigured(providerCatalogs.value, spec.provider) &&
                remoteSpecs.value?.any { it.id == id } == true
        }
        if (!ok) return false
        selectionStore.update { it.withActive(modality, id).withCard(ModelCard.of(spec)) }
        return true
    }

    override suspend fun clearDefaultModelId() = selectionStore.update { it.withActive(modality, null) }

    /** Clears the global default *only when* it currently points at [id] — decided against the file. */
    override suspend fun clearDefaultIfMatches(id: String) =
        selectionStore.update { if (it.activeFor(modality) == id) it.withActive(modality, null) else it }

    // One atomic write, and none at all when nothing changed (the steady state of every send). The card is
    // written with the pick so an immediate app close still paints this name on the next first frame.
    override suspend fun recordUsed(spec: ModelSpec) =
        selectionStore.update { it.withUsed(ModelCard.of(spec), ProviderTier.of(spec)) }

    // Called on picker selection (not send) so choice survives an unsent app close.
    override suspend fun recordSelected(spec: ModelSpec) = recordUsed(spec)

    override fun selectWhenDownloaded(spec: ModelSpec) {
        appScope.launch {
            if (scheduler.awaitCompletion(AssetKind.MODEL, spec.id)) {
                setDefaultModelId(spec.id)
                recordSelected(spec)
            }
        }
    }

    /** Remove [spec] from its tier's last-used slot. */
    override suspend fun clearUsed(spec: ModelSpec) {
        val tier = ProviderTier.of(spec)
        selectionStore.update { sel ->
            if (sel.lastUsedFor(tier) != spec.id) sel else sel.copy(lastUsedByTier = sel.lastUsedByTier - tier.key)
        }
    }

    /** Drops the global last-used pointer when it currently names [id]; its card is pruned with it. */
    override suspend fun clearLastUsedIfMatches(id: String) =
        selectionStore.update { if (it.lastUsedModelId == id) it.copy(lastUsedModelId = null) else it }

}
