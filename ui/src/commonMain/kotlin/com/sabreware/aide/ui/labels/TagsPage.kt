package com.sabreware.aide.ui.labels

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.HeaderAction
import com.sabreware.aide.core.designsystem.browse.BrowseNoMatches
import com.sabreware.aide.core.designsystem.browse.rememberBrowseResult
import com.sabreware.aide.core.designsystem.browse.rememberBrowseState
import com.sabreware.aide.core.designsystem.browse.selectHeaderAction
import com.sabreware.aide.core.domain.browse.BrowseSpec
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sabreware.aide.core.designsystem.AppListItem
import com.sabreware.aide.core.designsystem.appMenuSection
import com.sabreware.aide.core.designsystem.AppMenuSheetHeader
import com.sabreware.aide.core.designsystem.PageScaffold
import com.sabreware.aide.core.designsystem.Placeholder
import com.sabreware.aide.core.designsystem.PlaceholderAction
import com.sabreware.aide.core.designsystem.ScrollOwner
import com.sabreware.aide.core.designsystem.TextInputDialog
import com.sabreware.aide.core.designsystem.browse.ActionScope
import com.sabreware.aide.core.designsystem.browse.CollectionAction
import com.sabreware.aide.core.designsystem.browse.collectionBar
import com.sabreware.aide.core.designsystem.browse.Confirmation
import com.sabreware.aide.core.designsystem.browse.collectionHeader
import com.sabreware.aide.core.designsystem.browse.rememberActionRunner
import com.sabreware.aide.core.designsystem.browse.rememberSelectionState
import com.sabreware.aide.core.designsystem.resources.*
import org.koin.compose.viewmodel.koinViewModel

/**
 * The tag vocabulary: the user's tags, each with how many things carry it — create, rename (everywhere at
 * once), delete (from everything), one at a time from a tag's sheet (a tap) or its long-press, or several at
 * once in selection mode — then the [automatic] tags the app derives from what things are, which are
 * read-only; tapping one hands it to [onOpenAutomatic]. Host-agnostic — a
 * page of the models flow in both the app and the chat sheet.
 */
@Composable
fun TagsPage(automatic: List<AutoTagGroup> = emptyList(), onOpenAutomatic: (AutoTag) -> Unit = {}) {
    val vm: LabelsViewModel = koinViewModel()
    val editor = rememberLabelEditor(vm)
    val labels by vm.labels.collectAsStateWithLifecycle()
    val usage = labels.tagUsage
    val tags = labels.tags
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    val selection = rememberSelectionState()
    // A tag deleted or renamed while selected leaves the selection with it.
    LaunchedEffect(tags) { selection.retain(tags) }
    val runner = rememberActionRunner(
        listOf(
            CollectionAction<String>(id = "rename", label = "Rename", iconRes = Res.drawable.ic_lc_pencil, scope = ActionScope.One, leavesSheet = true) {
                renaming = it.single()
            },
            CollectionAction(id = "select", label = "Select", iconRes = Res.drawable.ic_lc_list_checks, scope = ActionScope.One) {
                selection.enter(it.single())
            },
            CollectionAction(
                id = "delete",
                label = "Delete",
                iconRes = Res.drawable.ic_lc_trash,
                destructive = true,
                leavesSheet = true,
                confirm = { targets ->
                    Confirmation(
                        title = if (targets.size == 1) "Delete ${targets.single()}?" else "Delete ${targets.size} tags?",
                        message = if (targets.size == 1) "Removes the tag from everything that has it."
                        else "Removes these tags from everything that has them.",
                    )
                },
            ) { targets ->
                vm.deleteTags(targets)
                selection.exit()
            },
        ),
    )
    val browse = rememberBrowseState()
    val result = rememberBrowseResult(tags, TagSpec, browse)
    val shown = result.items
    val tokens = browse.query.tokens
    val shownAutomatic = automatic.map { group ->
        group.copy(tags = group.tags.filter { tag -> tokens.all { it in tag.label.lowercase() } })
    }.filter { it.tags.isNotEmpty() }
    val header = collectionHeader(
        selection, runner, shown, key = { it },
        normal = collectionBar(
            title = "Tags",
            browse = browse,
            placeholder = "Search tags",
            actions = listOf(HeaderAction(Res.drawable.ic_lc_plus, "New tag", onClick = { editor.newTag() })),
            select = selectHeaderAction(selection, enabled = tags.size > 1),
            searchable = tags.isNotEmpty() || automatic.isNotEmpty(),
        ),
    )

    PageScaffold(
        title = header.title.orEmpty(),
        leadingAction = header.leadingAction,
        trailingActions = header.trailingActions,
        titleContent = header.titleContent,
        subtitle = header.subtitle,
        scroll = ScrollOwner.Content,
    ) { contentModifier ->
        LazyColumn(contentModifier.fillMaxSize()) {
            if (tokens.isNotEmpty() && shown.isEmpty() && shownAutomatic.isEmpty()) {
                item(key = "none") { BrowseNoMatches(browse, result) }
            } else if (tags.isEmpty() && tokens.isEmpty()) {
                item(key = "empty") {
                    Placeholder(
                        modifier = Modifier.fillMaxWidth(),
                        iconRes = Res.drawable.ic_lc_tag,
                        title = "No tags yet",
                        subtitle = "Tag models, connections and chats to find and filter them.",
                        actions = listOf(PlaceholderAction(label = "New tag", iconRes = Res.drawable.ic_lc_plus, onClick = { editor.newTag() })),
                    )
                }
            } else if (shown.isNotEmpty()) {
                appMenuSection(shown, key = { "tag:$it" }, title = "Your tags".takeIf { shownAutomatic.isNotEmpty() }) { tag ->
                    val count = usage[tag] ?: 0
                    val selecting = selection.active
                    AppListItem(
                        headline = tag,
                        supportingText = itemCount(count),
                        leadingIconRes = Res.drawable.ic_lc_tag,
                        trailing = if (selecting) ({ Checkbox(checked = tag in selection, onCheckedChange = null) }) else null,
                        selected = selecting && tag in selection,
                        onClick = { if (selecting) selection.toggle(tag) else open = tag },
                        contextActions = if (selecting) null else runner.menuFor(tag),
                        contextHeader = AppMenuSheetHeader(tag),
                    )
                }
            }
            // Read-only, and not part of a selection: the app keeps these in step with the things themselves.
            if (!selection.active) {
                shownAutomatic.forEach { group ->
                    appMenuSection(group.tags, key = { "auto:${it.facet}:${it.option}" }, title = group.title) { tag ->
                        AppListItem(
                            headline = tag.label,
                            supportingText = itemCount(tag.count),
                            leadingIconRes = Res.drawable.ic_lc_tag,
                            onClick = { onOpenAutomatic(tag) },
                        )
                    }
                }
            }
        }
    }

    // A tag's sheet: what can be done to it, then how many things carry it. Closes when the tag goes away.
    open?.takeIf { it in tags }?.let { tag ->
        AppDialog(onDismiss = { open = null }, title = tag) { controller ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Plain rows: two actions need no rail. Select belongs to the list, not to one tag; Rename and
                // Delete close this sheet before their dialog opens.
                AppMenu(items = runner.entriesFor(tag, except = setOf("select"), dismiss = controller::close))
                Text(
                    "Used by ${itemCount(usage[tag] ?: 0)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
    }

    renaming?.let { tag ->
        TextInputDialog(
            title = "Rename tag",
            initial = tag,
            confirmLabel = "Save",
            label = "Tag",
            onConfirm = { vm.renameTag(tag, it); if (open == tag) open = it },
            onDismiss = { renaming = null },
        )
    }
}

private fun itemCount(count: Int): String = if (count == 1) "1 item" else "$count items"

/** Tags search by name; they are already listed in the vocabulary's order. */
private val TagSpec = BrowseSpec<String>(key = { it }, text = { listOf(it) })
