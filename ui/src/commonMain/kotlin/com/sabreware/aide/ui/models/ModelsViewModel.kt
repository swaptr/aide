package com.sabreware.aide.ui.models

import com.sabreware.aide.core.domain.connection.ProviderDirectory
import com.sabreware.aide.core.domain.connection.ProviderInfo
import com.sabreware.aide.core.domain.model.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.sabreware.aide.core.domain.model.ModelImporter
import com.sabreware.aide.core.designsystem.state.UiState
import com.sabreware.aide.core.designsystem.state.stateInUi
import com.sabreware.aide.core.designsystem.state.stateInUiCached
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabreware.aide.core.domain.device.DeviceInfo
import com.sabreware.aide.core.domain.image.ImageModelCatalog
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ModelDescriptor
import com.sabreware.aide.core.domain.model.ModelImportConfig
import com.sabreware.aide.core.domain.model.ModelImportRepository
import com.sabreware.aide.core.domain.model.ModelMetadata
import com.sabreware.aide.core.domain.model.ModelRegistryRepository
import com.sabreware.aide.core.domain.model.ModelSelectionStore
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.ModelSpec
import com.sabreware.aide.core.domain.model.ModelSummary
import com.sabreware.aide.core.domain.model.SamplerOverride
import com.sabreware.aide.core.domain.model.SamplerOverridesStore
import com.sabreware.aide.core.domain.model.overrides
import com.sabreware.aide.core.domain.model.ModelSelection
import com.sabreware.aide.core.domain.model.effectiveModelId
import com.sabreware.aide.core.domain.model.selections
import com.sabreware.aide.core.common.persist.DocState
import com.sabreware.aide.core.domain.model.setActiveModelFor
import com.sabreware.aide.core.domain.permission.AppPermission
import com.sabreware.aide.core.domain.permission.RuntimePermissionGate
import com.sabreware.aide.core.domain.speech.BuiltInSpeechModel
import com.sabreware.aide.core.domain.speech.CloudSpeechCatalog
import com.sabreware.aide.core.domain.speech.SpeechProviderRegistry
import com.sabreware.aide.core.domain.speech.SpeechAssetRepository
import com.sabreware.aide.core.domain.speech.SpeechAssetSpec
import com.sabreware.aide.core.domain.speech.SpeechAssetSummary
import com.sabreware.aide.core.domain.usecase.CancelDownloadUseCase
import com.sabreware.aide.core.domain.usecase.DeleteModelUseCase
import com.sabreware.aide.core.domain.usecase.DownloadModelUseCase
import com.sabreware.aide.core.domain.usecase.LoadModelUseCase
import com.sabreware.aide.core.domain.usecase.ObserveModelsUseCase
import com.sabreware.aide.core.domain.usecase.PauseDownloadUseCase
import com.sabreware.aide.core.domain.usecase.UnloadModelUseCase
import com.sabreware.aide.core.domain.util.AideLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ModelsViewModel(
    private val deviceInfo: DeviceInfo,
    observeModels: ObserveModelsUseCase,
    private val downloadModel: DownloadModelUseCase,
    private val pauseDownload: PauseDownloadUseCase,
    private val cancelDownload: CancelDownloadUseCase,
    private val deleteModel: DeleteModelUseCase,
    private val loadModel: LoadModelUseCase,
    private val unloadModel: UnloadModelUseCase,
    private val selection: ModelSelectionStore,
    private val samplerOverridesStore: SamplerOverridesStore,
    private val speechAssets: SpeechAssetRepository,
    private val registry: ModelRegistryRepository,
    private val gate: RuntimePermissionGate,
    /** Reading which models have been imported — every target answers this, even if the answer is none. */
    private val importRepo: ModelImportRepository,
    /**
     * Performing an import. Null on a target that cannot.
     *
     * Absence by omission, not by stub: desktop used to bind an `import()` that returned
     * `Result.failure(UnsupportedOperationException)` — a capability present in the graph that refuses at
     * the last moment, which is exactly what the contribution rule replaced. [canImportModels] is how the
     * UI knows not to offer it.
     */
    private val importer: ModelImporter?,
    /** The cloud speech and image models the add-model page offers on its cloud tabs — ports, so this stays in `:ui`. */
    cloudSpeech: CloudSpeechCatalog,
    imageModels: ImageModelCatalog,
    /** Whatever speech providers this application contributed — the source of the host's built-in models. */
    speechProviders: SpeechProviderRegistry,
    /** Who serves each model, by the name the user knows it by. */
    private val directory: ProviderDirectory,
) : ViewModel() {

    // Requested lazily — only when a download actually starts (the download FGS posts a progress
    // notification), never on screen-open. Shows the app's custom rationale dialog via [gate]; the
    // download proceeds either way. isGranted folds the SDK gate (implicitly granted below API 33).
    private suspend fun ensureNotificationPermission() {
        if (!gate.isGranted(AppPermission.NOTIFICATIONS)) gate.ensure(AppPermission.NOTIFICATIONS)
    }

    // Seeded from the repositories' app-wide caches: once the session has resolved the lists, a re-opened
    // Models surface composes straight into Ready — no skeleton frame — while the shared upstreams refresh.
    private val rows: StateFlow<UiState<List<ModelSummary>>> =
        observeModels().stateInUiCached(viewModelScope)
    private val voiceRows: StateFlow<UiState<List<SpeechAssetSummary>>> =
        speechAssets.assets.stateInUiCached(viewModelScope)

    private val _loadingModelId = MutableStateFlow<String?>(null)
    private val _errorMessage = MutableStateFlow<String?>(null)

    // Every connection's curated speech and image rows: live, since connections come and go.
    private val cloudRows: StateFlow<List<ModelDescriptor>> =
        combine(cloudSpeech.models, imageModels.models) { speech, image -> speech.orEmpty() + image.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), cloudSpeech.all + imageModels.all)
    private val builtInRows: List<BuiltInSpeechModel> = speechProviders.builtInModels

    /** Each connection's name, kind and tags, by provider id — what rows paint a model's source with. */
    val sources: StateFlow<Map<String, ProviderInfo>?> = directory.connections

    /** [id]'s name and kind — an on-device engine's, or the connection's. */
    fun infoOf(id: ProviderId): ProviderInfo = directory.infoOf(id)

    init {
        // Whether this open painted from the app-wide caches or had to compute them — the skeleton frame a
        // cold first open shows. Pairs with the repositories' "first snapshot in N ms" lines.
        AideLog.d(
            "Models",
            "surface opened: models ${if (rows.value is UiState.Ready) "warm" else "cold"}, " +
                "voices ${if (voiceRows.value is UiState.Ready) "warm" else "cold"}",
        )
    }

    // The active pick per non-chat modality — the one slot a cloud id or an on-device asset id shares.
    // Seeded from the selection document (a startup document, so in memory by now): an empty seed let the
    // in-use list draw one frame without its active voice/image rows and then insert them.
    private val activeByModality: StateFlow<Map<Modality, String?>> =
        selection.selections.map(::activeMap)
            .stateInUi(
                viewModelScope,
                (selection.state.value as? DocState.Ready)?.value?.let(::activeMap) ?: emptyMap(),
            ) { emptyMap() }

    private fun activeMap(sel: ModelSelection): Map<Modality, String?> =
        ACTIVE_MODALITIES.associateWith { sel.activeFor(it) }

    private val transient: Flow<Pair<String?, String?>> = combine(_loadingModelId, _errorMessage) { loading, error -> loading to error }

    val uiState: StateFlow<ModelsUiState> = combine(
        rows, voiceRows, activeByModality, cloudRows, transient,
    ) { rows, voiceRows, active, cloud, (loading, error) ->
        ModelsUiState(rows, voiceRows, cloud, builtInRows, active, deviceInfo.totalRamGb, loading, error)
    }.stateInUi(
        viewModelScope,
        ModelsUiState(
            rows = rows.value,
            voiceRows = voiceRows.value,
            cloudRows = cloudRows.value,
            builtInRows = builtInRows,
            activeByModality = activeByModality.value,
            deviceTotalRamGb = deviceInfo.totalRamGb,
        ),
    ) { t ->
        ModelsUiState(
            rows = UiState.Failed(t.message ?: "Could not load models."),
            cloudRows = cloudRows.value,
            deviceTotalRamGb = deviceInfo.totalRamGb,
        )
    }

    /** Spec by id, for the model detail sheet. */
    fun findSpec(id: String): ModelSpec? = registry.findSpec(id)

    /** The active model id (what chat is using), so the in-use list can highlight it. */
    val activeModelId: StateFlow<String?> =
        registry.effectiveLastUsedModelIdFlow
            // Seeded by the same rule over the cached snapshot, so the check mark is on the first frame.
            .stateInUi(
                viewModelScope,
                registry.models.value?.let { effectiveModelId(registry.lastUsedModelIdFlow.value, it) },
            ) { null }

    /**
     * Make [spec] the active model for chat. Intrinsic — chat observes the registry's effective last-used
     * model and switches reactively (and closes any stale session), so no per-host callback is needed.
     */
    fun select(spec: ModelSpec) {
        viewModelScope.launch { runCatching { registry.recordSelected(spec) } }
    }

    /** Provider-served metadata (Ollama /api/show → models.dev → spec fallback) for [spec]. */
    suspend fun modelMetadata(spec: ChatModelSpec): ModelMetadata = registry.modelMetadata(spec)

    /** What the detail page paints before [modelMetadata] answers — see [ModelRegistryRepository.metadataSnapshot]. */
    fun metadataSnapshot(spec: ChatModelSpec): ModelMetadata = registry.metadataSnapshot(spec)

    /** Per-model sampler overrides (modelId → override), reactive for the sampler sheet. */
    val samplerOverrides: StateFlow<Map<String, SamplerOverride>> =
        samplerOverridesStore.overrides
            .map { it.byModel }
            .stateInUi(
                viewModelScope,
                (samplerOverridesStore.state.value as? DocState.Ready)?.value?.byModel ?: emptyMap(),
            ) { emptyMap() }

    /** Upsert (or remove when empty) a model's sampler override, atomically against the document file. */
    fun saveSamplerOverride(modelId: String, override: SamplerOverride) {
        viewModelScope.launch { samplerOverridesStore.update { it.with(modelId, override) } }
    }

    private val _importProgress = MutableStateFlow<Float?>(null)
    val importProgress: StateFlow<Float?> = _importProgress.asStateFlow()

    /** Whether this platform can import a local model file at all — drives the affordance, not a catch. */
    val canImportModels: Boolean get() = importer != null

    /** Copy + register a BYO local model; the registry reactively shows it when the copy finishes. */
    fun importModel(sourceUri: String, config: ModelImportConfig, onDone: () -> Unit) {
        val importer = importer ?: return
        viewModelScope.launch {
            _importProgress.value = 0f
            val result = importer.import(sourceUri, config) { p -> _importProgress.value = p }
            _importProgress.value = null
            if (result.isFailure) {
                _errorMessage.value = result.exceptionOrNull()?.message ?: "Import failed"
            } else {
                onDone()
            }
        }
    }

    fun startOrResumeDownload(spec: ModelSpec) {
        viewModelScope.launch {
            ensureNotificationPermission()
            downloadModel(spec)
            registry.selectWhenDownloaded(spec)
        }
    }
    fun pauseDownload(spec: ModelSpec) = pauseDownload.invoke(spec)
    fun cancelDownload(spec: ModelSpec) = cancelDownload.invoke(spec)

    fun delete(spec: ModelSpec) {
        viewModelScope.launch {
            // Imported models aren't downloads — route their delete to the import repo (file + entry). Read the
            // list at the moment of the delete: a replay value of an uncollected WhileSubscribed state is its
            // empty seed, which sent every imported model down the download path and left its entry behind.
            val imported = importRepo.observe().first().any { it.id == spec.id }
            if (imported) importRepo.remove(spec.id) else deleteModel(spec)
        }
    }

    fun load(spec: ModelSpec) {
        if (_loadingModelId.value != null) return
        _loadingModelId.value = spec.id
        _errorMessage.value = null
        viewModelScope.launch {
            val result = runCatching { loadModel(spec) }
            result.onFailure { t ->
                com.sabreware.aide.core.domain.util.AideLog.e("Aide", "load(${spec.id}) failed", t)
                _loadingModelId.value = null
                _errorMessage.value = "Load failed: ${t.message ?: t::class.simpleName}"
            }.onSuccess {
                _loadingModelId.value = null
            }
        }
    }

    fun unload(spec: ModelSpec) {
        viewModelScope.launch { unloadModel(spec) }
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    fun startOrResumeVoice(spec: SpeechAssetSpec) {
        viewModelScope.launch {
            ensureNotificationPermission()
            speechAssets.download(spec)
        }
    }

    fun pauseVoice(spec: SpeechAssetSpec) {
        speechAssets.pauseDownload(spec.id)
    }

    fun cancelVoice(spec: SpeechAssetSpec) {
        speechAssets.cancelDownload(spec)
    }

    fun deleteVoice(spec: SpeechAssetSpec) {
        speechAssets.cancelDownload(spec)
    }

    /**
     * Make [spec] the active pick for its modality — a downloaded speech asset, a cloud speech model or
     * an image model alike. Intrinsic: the engines re-read the active id per call (the next mic tap
     * loads the new bundle, the next `GenerateImage` draws with the new model), and in Auto the speech
     * ladder prefers the vendor that owns a cloud pick.
     */
    fun setActiveFor(spec: ModelDescriptor) {
        viewModelScope.launch {
            when (spec.modality) {
                Modality.Asr, Modality.Tts, Modality.Image -> selection.setActiveModelFor(spec.modality, spec.id)
                Modality.Chat -> (spec as? ModelSpec)?.let { registry.recordSelected(it) }
                else -> Unit
            }
        }
    }

    /**
     * Clear the active pick for [spec]'s modality, if [spec] is the one holding it. The guard runs inside
     * the store's atomic update, not against the shared state's replay value: that is `WhileSubscribed`,
     * so after the list has been off screen for a few seconds its `.value` is the seed and the guard would
     * silently do nothing.
     */
    fun clearActiveFor(spec: ModelDescriptor) {
        viewModelScope.launch {
            selection.update { if (it.activeFor(spec.modality) == spec.id) it.withActive(spec.modality, null) else it }
        }
    }

    private companion object {
        val ACTIVE_MODALITIES = listOf(Modality.Asr, Modality.Tts, Modality.Image)
    }
}
