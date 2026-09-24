package com.sabreware.aide.ui.settings.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppListItem
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.AppMenuSectionTitle
import com.sabreware.aide.core.designsystem.AppMenuSheetHeader
import com.sabreware.aide.core.designsystem.AppMenuTrailingSwitch
import com.sabreware.aide.core.designsystem.HeaderAction
import com.sabreware.aide.core.designsystem.browse.ActionRail
import com.sabreware.aide.core.designsystem.browse.ActionRunner
import com.sabreware.aide.core.designsystem.browse.ActionScope
import com.sabreware.aide.core.designsystem.browse.CollectionAction
import com.sabreware.aide.core.designsystem.browse.Confirmation
import com.sabreware.aide.core.designsystem.browse.SelectionState
import com.sabreware.aide.core.designsystem.browse.toggleAction
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.domain.browse.BrowseSpec
import com.sabreware.aide.core.domain.browse.Facet
import com.sabreware.aide.core.domain.label.LabelSubject
import com.sabreware.aide.core.domain.label.Labels
import com.sabreware.aide.core.domain.label.tagFacet
import com.sabreware.aide.ui.labels.LabelEditor
import com.sabreware.aide.ui.labels.labelActions
import com.sabreware.aide.ui.models.DetailRow

// -------------------------------------------------------------------------------------------------------
// Installed connectors on the shared collection kit: the same search, filters, selection and actions every
// other collection (models, connections, chats) offers — named, tagged and pinned through the label system.
// -------------------------------------------------------------------------------------------------------

private typealias Server = McpSettingsViewModel.ServerRow

/** How a connector is shown: the user's name for it, else its host. */
internal fun Server.displayName(labels: Labels): String =
    labels.nameOf(LabelSubject.connector(url), url.substringAfter("://").substringBefore('/'))

private enum class ConnectorStatus(val label: String) { Connected("Connected"), Disconnected("Disconnected"), Off("Off") }

private fun Server.status(): ConnectorStatus = when {
    !enabled -> ConnectorStatus.Off
    connected -> ConnectorStatus.Connected
    else -> ConnectorStatus.Disconnected
}

internal fun connectorSpec(labels: Labels): BrowseSpec<Server> = BrowseSpec(
    key = { it.url },
    text = { listOf(it.displayName(labels), it.url) + it.toolNames + labels[LabelSubject.connector(it.url)].tags },
    facets = listOf(
        Facet(
            id = "status",
            label = "Status",
            valuesOf = { listOf(it.status().name) },
            optionLabel = { ConnectorStatus.valueOf(it).label },
            options = ConnectorStatus.entries.map { it.name },
        ),
        labels.tagFacet { LabelSubject.connector(it.url) },
    ),
    order = compareBy({ !labels[LabelSubject.connector(it.url)].pinned }, { it.displayName(labels).lowercase() }),
)

/** Reconnect, Turn on/off, Rename, Tags, Pin, Select, Remove — for one connector or a selection. */
internal fun connectorActions(
    vm: McpSettingsViewModel,
    editor: LabelEditor,
    labels: () -> Labels,
    selection: SelectionState?,
): List<CollectionAction<Server>> = listOf(
    CollectionAction<Server>(
        id = "reconnect",
        label = "Reconnect",
        iconRes = Res.drawable.ic_lc_rotate_cw,
        available = { targets -> targets.all { it.enabled && !it.connected } },
    ) { targets -> targets.forEach { vm.reconnect(it.url) } },
    toggleAction(id = "enabled", on = "Turn on", off = "Turn off", iconRes = Res.drawable.ic_lc_plug, isOn = { it: Server -> it.enabled }) { targets, on ->
        targets.forEach { vm.setEnabled(it.url, on) }
    },
) + labelActions<Server>(
    editor = editor,
    labels = labels,
    subject = { LabelSubject.connector(it.url) },
    name = { it.displayName(labels()) },
    original = { it.url.substringAfter("://").substringBefore('/') },
) + listOfNotNull(
    selection?.let { sel ->
        CollectionAction<Server>(id = "select", label = "Select", iconRes = Res.drawable.ic_lc_list_checks, scope = ActionScope.One) {
            sel.enter(it.single().url)
        }
    },
    CollectionAction(
        id = "remove",
        label = "Remove",
        iconRes = Res.drawable.ic_lc_trash,
        destructive = true,
        leavesSheet = true,
        confirm = { targets ->
            Confirmation(
                title = if (targets.size == 1) "Remove ${targets.single().displayName(labels())}?" else "Remove ${targets.size} connectors?",
                message = "Its tools stop being available to chats.",
                confirmLabel = "Remove",
            )
        },
    ) { targets ->
        targets.forEach { vm.removeServer(it.url) }
        editor.viewModel.forget(targets.map { LabelSubject.connector(it.url) })
        selection?.exit()
    },
)

/**
 * One installed connector: its name, then status and tool count (and tags), with the on/off switch in
 * place — or a checkbox while selecting. A tap opens its sheet; a long-press offers the same actions.
 */
@Composable
internal fun ConnectorServerRow(
    row: Server,
    labels: Labels,
    runner: ActionRunner<Server>,
    selection: SelectionState?,
    onToggle: (String, Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val selecting = selection?.active == true
    val name = row.displayName(labels)
    val tags = labels[LabelSubject.connector(row.url)].tags
    AppListItem(
        headline = name,
        supportingText = (listOf(row.statusLine()) + tags.map { "#$it" }).joinToString(" · "),
        leadingIconRes = if (labels[LabelSubject.connector(row.url)].pinned) Res.drawable.ic_lc_pin else Res.drawable.ic_mcp,
        trailing = if (selecting) {
            { Checkbox(checked = row.url in selection!!, onCheckedChange = null) }
        } else {
            { AppMenuTrailingSwitch(checked = row.enabled, onCheckedChange = { onToggle(row.url, it) }) }
        },
        selected = selecting && row.url in selection!!,
        onClick = if (selecting) ({ selection!!.toggle(row.url) }) else onOpen,
        contextActions = if (selecting) null else runner.menuFor(row),
        contextHeader = AppMenuSheetHeader(name),
    )
}

/** The status in a few words: its tool count when connected. */
private fun Server.statusLine(): String = when (status()) {
    ConnectorStatus.Connected -> toolCount(toolNames.size)
    ConnectorStatus.Disconnected -> ConnectorStatus.Disconnected.label
    ConnectorStatus.Off -> ConnectorStatus.Off.label
}

private fun toolCount(n: Int): String = if (n == 1) "1 tool" else "$n tools"

/**
 * One connector's sheet: its name with the on/off toggle in the header, every action on it in one rail (the
 * same list its long-press sheet shows), then its facts — status, tools and address.
 */
@Composable
internal fun ConnectorSheet(
    row: Server,
    labels: Labels,
    runner: ActionRunner<Server>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismiss = onDismiss,
        title = row.displayName(labels),
        trailingActions = listOf(
            HeaderAction(
                iconRes = if (row.enabled) Res.drawable.ic_lc_unlink else Res.drawable.ic_lc_plug,
                label = if (row.enabled) "Turn off" else "Turn on",
                onClick = { onToggle(row.url, !row.enabled) },
            ),
        ),
    ) { controller ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // On/off is in the header; Select belongs to the list, not to one connector. Remove closes the sheet.
            ActionRail(runner, row, except = setOf("enabled", "select"), dismiss = controller::close)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DetailRow("Status", row.status().label)
                if (row.connected) DetailRow("Tools", toolCount(row.toolNames.size))
                DetailRow("Address", row.url)
                val tags = labels[LabelSubject.connector(row.url)].tags
                if (tags.isNotEmpty()) DetailRow("Tags", tags.joinToString(" ") { "#$it" })
            }
            if (row.toolNames.isNotEmpty()) {
                AppMenuSectionTitle("Tools")
                AppMenu(items = row.toolNames.map { AppMenuEntry(key = it, title = it, leadingIconRes = Res.drawable.ic_lc_wrench) })
            }
        }
    }
}
