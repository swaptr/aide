package com.sabreware.aide.ui.models

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import com.sabreware.aide.core.designsystem.AppListItem
import com.sabreware.aide.core.designsystem.appMenuSection
import com.sabreware.aide.core.designsystem.AppMenuSheetHeader
import com.sabreware.aide.core.designsystem.browse.ActionRunner
import com.sabreware.aide.core.designsystem.browse.ActionScope
import com.sabreware.aide.core.designsystem.browse.CollectionAction
import com.sabreware.aide.core.designsystem.browse.Confirmation
import com.sabreware.aide.core.designsystem.browse.SelectionState
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.domain.browse.BrowseResult
import com.sabreware.aide.core.domain.connection.ConnectionKind
import com.sabreware.aide.core.domain.label.Labels
import com.sabreware.aide.core.domain.model.ModelSpec
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.download.DownloadStatus
import com.sabreware.aide.ui.labels.LabelEditor
import com.sabreware.aide.ui.labels.labelActions

/**
 * Everything that can be done to library items — ONE list for a row's long-press sheet, the model sheet's
 * rail and a multi-selection: Use, Stop using, Download / Pause / Resume / Cancel, Rename, Tags, Pin, Sampler,
 * Load / Unload, its connection, Select, Delete. Each shows only where it means something (a cloud model has
 * nothing to download or delete; an on-device one has no connection; only a chat model has a sampler).
 */
internal fun libraryActions(
    vm: ModelsViewModel,
    editor: LabelEditor,
    labels: () -> Labels,
    selection: SelectionState?,
    onOpenConnection: (LibraryItem) -> Unit,
    onSampler: ((ChatModelSpec) -> Unit)? = null,
): List<CollectionAction<LibraryItem>> = listOf(
    CollectionAction<LibraryItem>(
        id = "use",
        label = "Use",
        iconRes = Res.drawable.ic_lc_circle_check,
        scope = ActionScope.One,
        available = { targets -> targets.single().let { !it.active && !it.needsDownload } },
    ) { targets -> use(vm, targets.single()) },
    CollectionAction(
        id = "stop",
        label = "Stop using",
        iconRes = Res.drawable.ic_lc_x,
        available = { targets -> targets.all { it.active && it.group != ModalityGroup.LANGUAGE } },
    ) { targets -> targets.forEach { item -> descriptorOf(item).let(vm::clearActiveFor) } },
    CollectionAction(
        id = "download",
        label = "Download",
        iconRes = Res.drawable.ic_lc_cloud_download,
        available = { targets -> targets.all { it.downloadStatus.let { s -> s == null || s is DownloadStatus.Idle || s is DownloadStatus.Cancelled || s is DownloadStatus.Failed } && it.needsDownload } },
    ) { targets -> targets.forEach { download(vm, it) } },
    CollectionAction(
        id = "pause",
        label = "Pause",
        iconRes = Res.drawable.ic_lc_pause,
        available = { targets -> targets.all { it.downloadStatus is DownloadStatus.InProgress || it.downloadStatus is DownloadStatus.Queued } },
    ) { targets ->
        targets.forEach { item ->
            when (val p = item.payload) {
                is LibraryItem.Payload.Chat -> vm.pauseDownload(p.summary.spec)
                is LibraryItem.Payload.Voice -> vm.pauseVoice(p.summary.spec)
                is LibraryItem.Payload.Ready -> Unit
            }
        }
    },
    CollectionAction(
        id = "resume",
        label = "Resume",
        iconRes = Res.drawable.ic_lc_play,
        available = { targets -> targets.all { it.downloadStatus is DownloadStatus.Paused } },
    ) { targets -> targets.forEach { download(vm, it) } },
    CollectionAction(
        id = "cancel",
        label = "Cancel download",
        iconRes = Res.drawable.ic_lc_x,
        available = { targets -> targets.all { it.availability == Availability.Downloading } },
    ) { targets ->
        targets.forEach { item ->
            when (val p = item.payload) {
                is LibraryItem.Payload.Chat -> vm.cancelDownload(p.summary.spec)
                is LibraryItem.Payload.Voice -> vm.cancelVoice(p.summary.spec)
                is LibraryItem.Payload.Ready -> Unit
            }
        }
    },
) + labelActions(
    editor = editor,
    labels = labels,
    subject = { it.subject },
    name = { it.name },
    original = { it.originalName ?: it.name },
) + listOfNotNull(
    onSampler?.let { open ->
        CollectionAction<LibraryItem>(
            id = "sampler",
            label = "Response style",
            iconRes = Res.drawable.ic_lc_settings,
            scope = ActionScope.One,
            available = { targets -> targets.single().payload is LibraryItem.Payload.Chat },
        ) { targets -> ((targets.single().payload as LibraryItem.Payload.Chat).summary.spec as? ChatModelSpec)?.let(open) }
    },
    CollectionAction<LibraryItem>(
        id = "load",
        label = "Keep in memory",
        iconRes = Res.drawable.ic_lc_play,
        scope = ActionScope.One,
        available = { targets ->
            val p = targets.single().payload
            p is LibraryItem.Payload.Chat && p.summary.spec.requiresDownload && p.summary.isDownloaded && !p.summary.isLoadedInEngine
        },
    ) { targets -> vm.load((targets.single().payload as LibraryItem.Payload.Chat).summary.spec) },
    CollectionAction<LibraryItem>(
        id = "unload",
        label = "Free memory",
        iconRes = Res.drawable.ic_lc_square,
        scope = ActionScope.One,
        available = { targets -> (targets.single().payload as? LibraryItem.Payload.Chat)?.summary?.isLoadedInEngine == true },
    ) { targets -> vm.unload((targets.single().payload as LibraryItem.Payload.Chat).summary.spec) },
    CollectionAction<LibraryItem>(
        id = "connection",
        label = "Connection",
        iconRes = Res.drawable.ic_lc_plug,
        scope = ActionScope.One,
        available = { targets -> targets.single().kind != ConnectionKind.OnDevice },
        // Navigates to the connection's page: the model sheet over this page closes first.
        leavesSheet = true,
    ) { onOpenConnection(it.single()) },
    selection?.let { sel ->
        CollectionAction<LibraryItem>(id = "select", label = "Select", iconRes = Res.drawable.ic_lc_list_checks, scope = ActionScope.One) {
            sel.enter(it.single().id)
        }
    },
    CollectionAction(
        id = "delete",
        label = "Delete",
        iconRes = Res.drawable.ic_lc_trash,
        destructive = true,
        available = { targets -> targets.all(::deletable) },
        confirm = { targets ->
            Confirmation(
                title = if (targets.size == 1) "Delete ${targets.single().name}?" else "Delete ${targets.size} models?",
                message = "Removes the downloaded files. You can download them again anytime.",
            )
        },
    ) { targets ->
        targets.forEach { item ->
            when (val p = item.payload) {
                is LibraryItem.Payload.Chat -> vm.delete(p.summary.spec)
                is LibraryItem.Payload.Voice -> vm.deleteVoice(p.summary.spec)
                is LibraryItem.Payload.Ready -> Unit
            }
        }
    },
)

private fun download(vm: ModelsViewModel, item: LibraryItem) {
    when (val p = item.payload) {
        is LibraryItem.Payload.Chat -> vm.startOrResumeDownload(p.summary.spec)
        is LibraryItem.Payload.Voice -> vm.startOrResumeVoice(p.summary.spec)
        is LibraryItem.Payload.Ready -> Unit
    }
}

/** The item's download state, or null for a model that is never downloaded. */
internal val LibraryItem.downloadStatus: DownloadStatus?
    get() = when (val p = payload) {
        is LibraryItem.Payload.Chat -> p.summary.downloadStatus.takeIf { p.summary.spec.requiresDownload }
        is LibraryItem.Payload.Voice -> p.summary.downloadStatus
        is LibraryItem.Payload.Ready -> null
    }

private fun deletable(item: LibraryItem): Boolean = when (val p = item.payload) {
    is LibraryItem.Payload.Chat -> p.summary.spec.requiresDownload && p.summary.isDownloaded
    is LibraryItem.Payload.Voice -> p.summary.isDownloaded
    is LibraryItem.Payload.Ready -> false
}

internal fun descriptorOf(item: LibraryItem): com.sabreware.aide.core.domain.model.ModelDescriptor = when (val p = item.payload) {
    is LibraryItem.Payload.Chat -> p.summary.spec
    is LibraryItem.Payload.Voice -> p.summary.spec
    is LibraryItem.Payload.Ready -> p.spec
}

/** Makes [item] the model its role uses: chat's pick, or the dictation / speech / image slot. */
internal fun use(vm: ModelsViewModel, item: LibraryItem) {
    when (val p = item.payload) {
        is LibraryItem.Payload.Chat -> vm.select(p.summary.spec as ModelSpec)
        is LibraryItem.Payload.Voice -> vm.setActiveFor(p.summary.spec)
        is LibraryItem.Payload.Ready -> vm.setActiveFor(p.spec)
    }
}

/**
 * Where a model stands for the user — the ONE sectioning every model list uses, so an installed model is
 * never missing from a list that covers it and every page reads the same way top to bottom: what is in use,
 * what is ready (installed, built in, or served by a connection), what is on its way, what could be added.
 */
enum class Availability(val label: String) {
    InUse("In use"),
    Installed("Installed"),
    BuiltIn("Built in"),
    Connected("Ready to use"),
    Downloading("Downloading"),
    Available("Available to download"),
}

val LibraryItem.availability: Availability
    get() {
        if (active) return Availability.InUse
        val status = when (val p = payload) {
            is LibraryItem.Payload.Chat -> p.summary.downloadStatus
            is LibraryItem.Payload.Voice -> p.summary.downloadStatus
            is LibraryItem.Payload.Ready -> return if (kind == com.sabreware.aide.core.domain.connection.ConnectionKind.OnDevice) Availability.BuiltIn else Availability.Connected
        }
        return when {
            status is com.sabreware.aide.core.domain.download.DownloadStatus.InProgress ||
                status is com.sabreware.aide.core.domain.download.DownloadStatus.Queued ||
                status is com.sabreware.aide.core.domain.download.DownloadStatus.Paused ||
                status is com.sabreware.aide.core.domain.download.DownloadStatus.Finalizing -> Availability.Downloading
            needsDownload -> Availability.Available
            kind == com.sabreware.aide.core.domain.connection.ConnectionKind.OnDevice -> Availability.Installed
            else -> Availability.Connected
        }
    }

private const val PINNED_SECTION = "Pinned"

/** Pinned first, then [Availability] order. Kept while searching, so a match still says where it stands. */
val AvailabilityGrouping: com.sabreware.aide.core.domain.browse.Grouping<LibraryItem> =
    com.sabreware.aide.core.domain.browse.Grouping(
        of = { if (it.pinned) PINNED_SECTION else it.availability.label },
        rank = { title -> if (title == PINNED_SECTION) -1 else Availability.entries.indexOfFirst { it.label == title } },
        whileSearching = true,
    )

/**
 * A browse result as list sections: a title per section (none for a single untitled one) and one
 * [LibraryRow] per item. Item-level, so a page's header rows and this share one scroll.
 */
internal fun LazyListScope.librarySections(
    result: BrowseResult<LibraryItem>,
    row: @Composable (LibraryItem) -> Unit,
) {
    // Each section one rounded block, like every menu in the app.
    result.sections.forEach { section ->
        appMenuSection(section.items, key = { it.id }, title = section.title, row = row)
    }
}

/**
 * One library item, the SAME row for every kind of model: its name, then where it stands (in use, a download's
 * progress, its size) and its source and tags. A tap opens the model sheet; a long-press offers the actions;
 * while selecting it carries a checkbox. A download shows as a small progress ring rather than a different row.
 */
@Composable
internal fun LibraryRow(
    item: LibraryItem,
    selection: SelectionState,
    runner: ActionRunner<LibraryItem>,
    loadingModelId: String?,
    onOpen: (LibraryItem) -> Unit,
) {
    val selecting = selection.active
    val status = item.downloadStatus
    AppListItem(
        headline = item.name,
        supportingText = listOfNotNull(
            "In use".takeIf { item.active },
            "Loading".takeIf { loadingModelId == item.id },
            status?.let(::downloadLine),
            // What adding it costs, while it could still be added.
            descriptorOf(item).sizeBytes?.takeIf { item.availability == Availability.Available }?.let(::humanBytes),
            item.subtitle().ifEmpty { null },
        ).joinToString(" · ").ifEmpty { null },
        leadingIconRes = if (item.pinned && !selecting) Res.drawable.ic_lc_pin else item.kind.iconRes(),
        trailing = when {
            selecting -> ({ Checkbox(checked = item.id in selection, onCheckedChange = null) })
            status is DownloadStatus.InProgress -> ({
                CircularProgressIndicator(progress = { status.progress.coerceIn(0f, 1f) }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            })
            else -> null
        },
        selected = if (selecting) item.id in selection else item.active,
        onClick = { if (selecting) selection.toggle(item.id) else onOpen(item) },
        contextActions = if (selecting) null else runner.menuFor(item),
        contextHeader = AppMenuSheetHeader(item.name),
    )
}

/** A download's state in a few words; null once it is simply on the device. */
internal fun downloadLine(status: DownloadStatus): String? = when (status) {
    is DownloadStatus.InProgress -> "Downloading ${(status.progress.coerceIn(0f, 1f) * 100).toInt()}%"
    is DownloadStatus.Queued -> "Waiting"
    is DownloadStatus.Paused -> "Paused"
    is DownloadStatus.Finalizing -> status.stage.label
    is DownloadStatus.Failed -> "Download failed"
    else -> null
}
