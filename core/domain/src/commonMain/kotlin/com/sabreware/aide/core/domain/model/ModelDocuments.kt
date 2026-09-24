package com.sabreware.aide.core.domain.model

import com.sabreware.aide.core.common.persist.DocState
import com.sabreware.aide.core.common.persist.Durability
import com.sabreware.aide.core.common.persist.PersistedDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * The model documents this module owns. [all] is what `PersistedDocumentSchemaTest` checks against
 * `core/domain/schemas/documents/` — declare a new document here or it has no ledger.
 *
 * To change a document's shape: edit the class, bump its `version`, run
 * `./gradlew :core:domain:desktopTest --tests '*PersistedDocumentSchemaTest*'`, commit the new `N.json`.
 */
object ModelDocuments {
    val Selection = PersistedDocument(
        name = "model_selection",
        version = 1,
        serializer = ModelSelection.serializer(),
        default = ModelSelection(),
        durability = Durability.Intent,
        // An empty selection reads as "the user chose nothing" and puts up the call to action everywhere.
        defaultWhileUnreadable = false,
    )

    /** Per-model sampler overrides the user set in the sampler sheet. Authored data: never auto-reset. */
    val SamplerOverrides = PersistedDocument(
        name = "sampler_overrides",
        version = 1,
        // Type position: the bare name here would resolve to this property, not the class.
        serializer = serializer<SamplerOverrides>(),
        default = SamplerOverrides(),
        durability = Durability.Intent,
    )

    /** The user's imported local models (BYO `.litertlm`). The files live in `ModelStorage`; this is the list. */
    val ImportedModels = PersistedDocument(
        name = "imported_models",
        version = 1,
        serializer = serializer<ImportedModels>(),
        default = ImportedModels(),
        durability = Durability.Intent,
    )

    val all: List<PersistedDocument<*>> = listOf(Selection, SamplerOverrides, ImportedModels)
}

/**
 * The user's model choices, loaded at process start (see [ModelDocuments.Selection]).
 *
 * A named port rather than a bare `DocumentStore<ModelSelection>`: generics erase, so two document stores
 * would be one key in the container — the same reason every registry here is its own type.
 */
interface ModelSelectionStore {
    /** [DocState.Loading] for the few ms the file takes to read at process start; Ready thereafter. */
    val state: StateFlow<DocState<ModelSelection>>

    /** The current choices, waiting out the first read if it has not landed. */
    suspend fun current(): ModelSelection

    /** Atomic read-modify-write against the file (never a replay value). Unreferenced cards are pruned. */
    suspend fun update(transform: (ModelSelection) -> ModelSelection)
}

/** The Ready values of [ModelSelectionStore.state]; nothing while it is still loading. */
val ModelSelectionStore.selections: Flow<ModelSelection>
    get() = state.filterIsInstance<DocState.Ready<ModelSelection>>().map { it.value }

/** Active model id for [modality], or null when that slot is empty. Waits out the first read. */
fun ModelSelectionStore.activeModelFor(modality: Modality): Flow<String?> =
    selections.map { it.activeFor(modality) }.distinctUntilChanged()

/** Sets (id) or clears (null) the active model for [modality], atomically against any other slot write. */
suspend fun ModelSelectionStore.setActiveModelFor(modality: Modality, id: String?) =
    update { it.withActive(modality, id) }

/** The [ModelDocuments.SamplerOverrides] document: model id → the user's override. Empty overrides are dropped. */
@Serializable
data class SamplerOverrides(val byModel: Map<String, SamplerOverride> = emptyMap()) {
    /** [override] for [modelId], or its removal when [SamplerOverride.isEmpty] — never a persisted no-op. */
    fun with(modelId: String, override: SamplerOverride): SamplerOverrides =
        copy(byModel = if (override.isEmpty()) byModel - modelId else byModel + (modelId to override))
}

/** Per-model sampler overrides (see [ModelDocuments.SamplerOverrides]); a named port for the same reason as [ModelSelectionStore]. */
interface SamplerOverridesStore {
    /** [DocState.Loading] until the file's first read lands. */
    val state: StateFlow<DocState<SamplerOverrides>>

    /** The current overrides, waiting out the first read. */
    suspend fun current(): SamplerOverrides

    /** Atomic read-modify-write against the file. Returns the committed value, or null if the write failed. */
    suspend fun update(transform: (SamplerOverrides) -> SamplerOverrides): SamplerOverrides?
}

/** The [ModelDocuments.ImportedModels] document. One entry per id; an import under an existing id replaces it. */
@Serializable
data class ImportedModels(val models: List<ImportedModelEntry> = emptyList()) {
    /** [entry] replacing any entry with the same id, appended last. */
    fun upsert(entry: ImportedModelEntry): ImportedModels =
        copy(models = models.filterNot { it.id == entry.id } + entry)

    fun without(id: String): ImportedModels = copy(models = models.filterNot { it.id == id })
}

/** The imported-model list (see [ModelDocuments.ImportedModels]); a named port for the same reason as [ModelSelectionStore]. */
interface ImportedModelsStore {
    /** [DocState.Loading] until the file's first read lands. */
    val state: StateFlow<DocState<ImportedModels>>

    /** The current list, waiting out the first read. */
    suspend fun current(): ImportedModels

    /** Atomic read-modify-write against the file. Returns the committed value, or null if the write failed. */
    suspend fun update(transform: (ImportedModels) -> ImportedModels): ImportedModels?
}

/** The Ready values of [SamplerOverridesStore.state]; nothing while it is still loading. */
val SamplerOverridesStore.overrides: Flow<SamplerOverrides>
    get() = state.filterIsInstance<DocState.Ready<SamplerOverrides>>().map { it.value }

/** The Ready values of [ImportedModelsStore.state]; nothing while it is still loading. */
val ImportedModelsStore.models: Flow<List<ImportedModelEntry>>
    get() = state.filterIsInstance<DocState.Ready<ImportedModels>>().map { it.value.models }
