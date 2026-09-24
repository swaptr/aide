package com.sabreware.aide.ui.models

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sabreware.aide.core.domain.browse.Facet
import com.sabreware.aide.core.domain.connection.ConnectionKind
import com.sabreware.aide.core.domain.label.TAG_FACET
import com.sabreware.aide.core.domain.model.ChatCapabilities
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.ui.labels.AutoTagGroup
import com.sabreware.aide.ui.labels.LabelsViewModel
import com.sabreware.aide.ui.labels.automaticTags
import org.koin.compose.viewmodel.koinViewModel

// -------------------------------------------------------------------------------------------------------
// Automatic tags: what a model IS, read from its metadata rather than typed by the user. They are the
// library's own facets (where it runs, what it is used for, what it can do, how much it reads at once, how
// big it is, whether it is in use or downloaded), so the Tags page lists exactly the filters the Models page
// offers, with the same names and the same counts: one definition, never a second vocabulary to keep in step. The dimensions follow the ones model catalogs
// filter by: OpenRouter (modality, context length, tools, reasoning, structured outputs), Ollama (vision,
// tools, thinking, cloud), Hugging Face (parameter size).
// -------------------------------------------------------------------------------------------------------

/** What a chat model can do, in the order the filter lists them. */
internal val CAPABILITIES = listOf("Vision", "Tools", "Thinking", "Structured data", "Audio", "Documents")

internal fun capabilityTags(caps: ChatCapabilities): List<String> = buildList {
    if (caps.visionIn) add("Vision")
    if (caps.toolsLocal) add("Tools")
    if (caps.thinking != ChatCapabilities.ThinkingMode.None) add("Thinking")
    if (caps.structuredOutput != ChatCapabilities.StructuredOutput.None) add("Structured data")
    if (caps.audioIn) add("Audio")
    if (caps.documentIn) add("Documents")
}

/**
 * How much a chat model reads at once. The tiers are cumulative ("at least"), because the question is
 * always "will it fit": a 1M model is also a 128K+ and a 32K+ one, so choosing 128K+ finds every model that
 * holds a long document. Thresholds are decimal so a 131,072 or 1,048,576 window lands in its tier.
 */
enum class ContextTier(val label: String, val minTokens: Int) {
    K32("32K+", 32_000),
    K128("128K+", 128_000),
    M1("1M+", 1_000_000),
}

internal fun contextTags(caps: ChatCapabilities): List<String> =
    ContextTier.entries.filter { caps.maxContext >= it.minTokens }.map { it.name }

internal fun contextFacet(): Facet<LibraryItem> = Facet(
    id = LibraryFacets.CONTEXT,
    label = "Context",
    valuesOf = { item -> item.capabilities?.let(::contextTags).orEmpty() },
    optionLabel = { ContextTier.valueOf(it).label },
    options = ContextTier.entries.map { it.name },
)

/**
 * How big a chat model is, by parameter count — the axis Hugging Face and LM Studio sort local models by,
 * since it predicts both quality and whether it runs on this device. Read from the variant label when the
 * catalog gives one, else from the name ("gemma4:31b", "Qwen2.5-1.5B"); a model that states neither (most
 * cloud models) carries no size rather than a guessed one.
 */
enum class SizeTier(val label: String, val belowBillions: Double) {
    Small("Under 4B", 4.0),
    Medium("4B to 15B", 15.0),
    Large("15B+", Double.MAX_VALUE),
}

private val PARAMS = Regex("""(?i)(?<![a-z0-9.])e?(\d+(?:\.\d+)?)b(?![a-z0-9])""")

/** Billions of parameters stated in [text], or null. */
internal fun parameterBillions(text: String): Double? = PARAMS.find(text)?.groupValues?.get(1)?.toDoubleOrNull()

internal fun LibraryItem.sizeTier(): SizeTier? {
    val spec = (payload as? LibraryItem.Payload.Chat)?.summary?.spec ?: return null
    val billions = parameterBillions(spec.params) ?: parameterBillions(spec.displayName) ?: parameterBillions(spec.id) ?: return null
    return SizeTier.entries.first { billions < it.belowBillions }
}

internal fun sizeFacet(): Facet<LibraryItem> = Facet(
    id = LibraryFacets.SIZE,
    label = "Size",
    valuesOf = { item -> listOfNotNull(item.sizeTier()?.name) },
    optionLabel = { SizeTier.valueOf(it).label },
    options = SizeTier.entries.map { it.name },
)

/** Where a model stands with the user: in use by a role, its weights on this device, or still to fetch. */
enum class ModelStatus(val label: String) { InUse("In use"), Downloaded("Downloaded"), NeedsDownload("Not downloaded") }

internal fun LibraryItem.statuses(): List<ModelStatus> = buildList {
    if (active) add(ModelStatus.InUse)
    // Weights that live on this device: downloaded, imported, or not fetched yet. Built-in engines have none.
    if (kind == ConnectionKind.OnDevice && payload !is LibraryItem.Payload.Ready) add(if (needsDownload) ModelStatus.NeedsDownload else ModelStatus.Downloaded)
}

internal fun statusFacet(): Facet<LibraryItem> = Facet(
    id = LibraryFacets.STATUS,
    label = "Status",
    valuesOf = { item -> item.statuses().map { it.name } },
    optionLabel = { ModelStatus.valueOf(it).label },
    options = ModelStatus.entries.map { it.name },
)

/**
 * The models' automatic tags for the Tags page: every library facet but the user's tags and the connection
 * (a connection is where a model comes from, not what it is), counted over everything the app can offer —
 * the pool the Models page filters once a filter is chosen.
 */
@Composable
fun rememberModelAutoTags(): List<AutoTagGroup> {
    val vm: ModelsViewModel = koinViewModel()
    val labelsVm: LabelsViewModel = koinViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val labels by labelsVm.labels.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    return remember(state, labels, sources) {
        val library = buildLibrary(state, labels, activeChatId = null, vm::infoOf)
        librarySpec(labels) { vm.infoOf(ProviderId(it)).name }
            .automaticTags(library, except = setOf(TAG_FACET, LibraryFacets.SOURCE))
    }
}
