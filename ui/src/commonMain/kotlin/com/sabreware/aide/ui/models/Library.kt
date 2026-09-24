package com.sabreware.aide.ui.models

import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.state.valueOrNull
import com.sabreware.aide.core.domain.browse.BrowseSpec
import com.sabreware.aide.core.domain.browse.Facet
import com.sabreware.aide.core.domain.browse.Grouping
import com.sabreware.aide.core.domain.connection.ConnectionKind
import com.sabreware.aide.core.domain.connection.ProviderInfo
import com.sabreware.aide.core.domain.label.LabelSubject
import com.sabreware.aide.core.domain.label.Labels
import com.sabreware.aide.core.domain.label.tagFacet
import com.sabreware.aide.core.domain.model.ChatCapabilities
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ModelDescriptor
import com.sabreware.aide.core.domain.model.ModelSpec
import com.sabreware.aide.core.domain.model.ModelSummary
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.speech.CloudSpeechModelSpec
import com.sabreware.aide.core.domain.speech.SpeechAssetSummary
import org.jetbrains.compose.resources.DrawableResource

/**
 * Every model the app can offer, whatever it is and wherever it runs, as ONE kind of row: a chat model, a
 * downloadable voice, a cloud speech or image model, a speech engine built into the host. The Models page
 * searches, filters, selects and acts on this list with the shared browse kit; what differs by kind lives in
 * [payload], and only a row's renderer looks at it.
 */
data class LibraryItem(
    val id: String,
    /** The name shown — the user's alias when they gave one. */
    val name: String,
    /** The model's own name, when [name] is an alias. */
    val originalName: String?,
    val group: ModalityGroup,
    val provider: ProviderId,
    val source: ProviderInfo,
    val tags: List<String>,
    val pinned: Boolean,
    /** The user's model: picked, in use, installed on this device, or pinned. What the page shows unsearched. */
    val mine: Boolean,
    /** The model a role is using right now (chat's, dictation's, speech's or image's). */
    val active: Boolean,
    val payload: Payload,
) {
    val subject: LabelSubject get() = LabelSubject.model(id)
    val kind: ConnectionKind get() = source.kind

    sealed interface Payload {
        data class Chat(val summary: ModelSummary) : Payload
        data class Voice(val summary: SpeechAssetSummary) : Payload
        /** Nothing to download: a cloud speech or image model, or one built into the host. */
        data class Ready(val spec: ModelDescriptor) : Payload
    }

    /** A chat model's capabilities; null for every other kind. */
    val capabilities: ChatCapabilities? get() = ((payload as? Payload.Chat)?.summary?.spec as? ChatModelSpec)?.capabilities

    /** Weights still to fetch before it can be used. */
    val needsDownload: Boolean
        get() = when (val p = payload) {
            is Payload.Chat -> p.summary.spec.requiresDownload && !p.summary.isDownloaded
            is Payload.Voice -> !p.summary.isDownloaded
            is Payload.Ready -> false
        }
}

/**
 * The library from the Models state, the user's labels and the provider directory. Pure: the page remembers
 * it by its inputs, so a download tick rebuilds it once, not per frame.
 */
fun buildLibrary(
    state: ModelsUiState,
    labels: Labels,
    activeChatId: String?,
    infoOf: (ProviderId) -> ProviderInfo,
): List<LibraryItem> {
    fun item(
        id: String,
        ownName: String,
        group: ModalityGroup,
        provider: ProviderId,
        mine: Boolean,
        active: Boolean,
        payload: LibraryItem.Payload,
        alias: String? = labels[LabelSubject.model(id)].alias,
        originalName: String? = alias?.let { ownName },
    ): LibraryItem {
        val label = labels[LabelSubject.model(id)]
        return LibraryItem(
            id = id,
            name = alias ?: ownName,
            originalName = originalName,
            group = group,
            provider = provider,
            source = infoOf(provider),
            tags = label.tags,
            pinned = label.pinned,
            mine = mine || label.pinned,
            active = active,
            payload = payload,
        )
    }
    val chat = state.rows.valueOrNull.orEmpty().map { summary ->
        val local = ProviderIdKind.isOnDevice(summary.spec.provider)
        item(
            id = summary.spec.id,
            ownName = summary.originalName ?: summary.spec.displayName,
            group = ModalityGroup.LANGUAGE,
            provider = summary.spec.provider,
            mine = summary.isInUse || (local && summary.isDownloaded) || summary.spec.id == activeChatId,
            active = summary.spec.id == activeChatId,
            payload = LibraryItem.Payload.Chat(summary),
            // The registry already applied the alias to a chat model's spec.
            alias = summary.originalName?.let { summary.spec.displayName },
            originalName = summary.originalName,
        )
    }
    val voices = state.voiceRows.valueOrNull.orEmpty().filter { it.spec.modality != Modality.Vad }.map { summary ->
        item(
            id = summary.spec.id,
            ownName = summary.spec.displayName,
            group = summary.spec.modality.speechGroup(),
            provider = summary.spec.provider,
            mine = summary.isActive && summary.isDownloaded,
            active = summary.isActive,
            payload = LibraryItem.Payload.Voice(summary),
        )
    }
    val ready = (state.cloudRows + state.builtInRows).map { spec ->
        val active = state.activeByModality[spec.modality] == spec.id
        item(
            id = spec.id,
            ownName = spec.displayName,
            group = spec.modality.group(),
            provider = spec.provider,
            mine = active,
            active = active,
            payload = LibraryItem.Payload.Ready(spec),
        )
    }
    return chat + voices + ready
}

/** Built-in providers are on-device (their locality is the directory's to say, but a built-in never moves). */
internal object ProviderIdKind {
    fun isOnDevice(id: ProviderId): Boolean = com.sabreware.aide.core.domain.model.ProviderCatalog.isBuiltIn(id)
}

/** Facet ids the Models surfaces preselect. */
object LibraryFacets {
    const val LOCATION = "location"
    const val SOURCE = "source"
    const val TYPE = "type"
    const val CAPABILITY = "can"
    const val CONTEXT = "context"
    const val SIZE = "size"
    const val STATUS = "status"
}

private const val PINNED = "Pinned"

/**
 * The Models library as a browse spec: searchable by name, own name, source and tags; filterable by where it
 * runs, what serves it, what it does, what it can do, how much it reads at once, how big it is, whether it is
 * in use or downloaded, and how the user tagged it; grouped Pinned first, then by what it does. Rebuilt when
 * the labels or the names change (the Tag facet's options are the user's
 * vocabulary; [sourceName] names a provider id).
 */
fun librarySpec(labels: Labels, sourceName: (String) -> String): BrowseSpec<LibraryItem> = BrowseSpec(
    key = { it.id },
    text = { listOfNotNull(it.name, it.originalName, it.source.name, it.source.vendorName) + it.tags },
    facets = listOf(
        Facet(
            id = LibraryFacets.LOCATION,
            label = "Where it runs",
            valuesOf = { listOf(it.kind.name) },
            optionLabel = { ConnectionKind.valueOf(it).label },
            options = ConnectionKind.entries.map { it.name },
        ),
        Facet(
            id = LibraryFacets.TYPE,
            label = "Used for",
            valuesOf = { listOf(it.group.name) },
            optionLabel = { ModalityGroup.valueOf(it).label },
            options = ModalityGroup.entries.map { it.name },
        ),
        Facet(
            id = LibraryFacets.SOURCE,
            label = "Connection",
            valuesOf = { listOf(it.provider.value) },
            optionLabel = sourceName,
        ),
        Facet(
            id = LibraryFacets.CAPABILITY,
            label = "Capabilities",
            valuesOf = { item -> item.capabilities?.let(::capabilityTags).orEmpty() },
            options = CAPABILITIES,
        ),
        contextFacet(),
        sizeFacet(),
        statusFacet(),
        labels.tagFacet { it.subject },
    ),
    order = compareBy<LibraryItem>({ !it.active }, { it.group.ordinal }, { it.name.lowercase() }),
    group = Grouping(
        of = { if (it.pinned) PINNED else it.group.label },
        rank = { title -> if (title == PINNED) -1 else ModalityGroup.entries.indexOfFirst { it.label == title } },
    ),
)

/** A leading glyph for where a model runs. */
fun ConnectionKind.iconRes(): DrawableResource = when (this) {
    ConnectionKind.OnDevice -> Res.drawable.ic_lc_smartphone
    ConnectionKind.SelfHosted -> Res.drawable.ic_lc_server
    ConnectionKind.Cloud -> Res.drawable.ic_lc_cloud
}

/** One subtitle line for a library row: the source, then what distinguishes it — never more than a line. */
fun LibraryItem.subtitle(): String = listOfNotNull(
    originalName,
    when {
        kind != ConnectionKind.OnDevice -> source.name
        payload is LibraryItem.Payload.Ready -> "Built in"
        (payload as? LibraryItem.Payload.Chat)?.summary?.isLoadedInEngine == true -> "On-device · In memory"
        else -> "On-device"
    },
    (payload as? LibraryItem.Payload.Ready)?.spec?.let { (it as? CloudSpeechModelSpec)?.blurb ?: (it as? ModelSpec)?.description },
    tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
).joinToString(" · ")
