package com.sabreware.aide.ui.labels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.HeaderAction
import com.sabreware.aide.core.designsystem.TextInputDialog
import com.sabreware.aide.core.designsystem.browse.ActionScope
import com.sabreware.aide.core.designsystem.browse.CollectionAction
import com.sabreware.aide.core.designsystem.browse.toggleAction
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.domain.label.LabelSubject
import com.sabreware.aide.core.domain.label.Labels
import org.koin.compose.viewmodel.koinViewModel

/**
 * The rename and tag editors, for any subject — one host per surface, opened by [rename] / [editTags] and
 * drawn by [rememberLabelEditor]. A page that lists models, connections or anything else labelled gets the
 * same dialogs with the same behaviour by calling two functions.
 */
@Stable
class LabelEditor internal constructor(val viewModel: LabelsViewModel) {
    internal sealed interface Edit {
        data class Rename(val subject: LabelSubject, val current: String, val original: String?) : Edit
        data class Tags(val subjects: List<LabelSubject>, val name: String) : Edit
        data class NewTag(val assignTo: List<LabelSubject>, val name: String = "") : Edit
    }

    internal var pending: Edit? by mutableStateOf(null)

    /** Rename [subject], currently shown as [current]; [original] is its own name, which clears the alias. */
    fun rename(subject: LabelSubject, current: String, original: String?) { pending = Edit.Rename(subject, current, original) }

    /** Choose the tags of [subjects] (one row, or a whole selection); [name] titles the sheet. */
    fun editTags(subjects: List<LabelSubject>, name: String) { pending = Edit.Tags(subjects, name) }

    /** Create a tag, optionally assigning it to [assignTo] at once. */
    fun newTag(assignTo: List<LabelSubject> = emptyList()) { pending = Edit.NewTag(assignTo) }
}

/** A [LabelEditor] whose dialogs this composition draws. */
@Composable
fun rememberLabelEditor(viewModel: LabelsViewModel = koinViewModel()): LabelEditor {
    val editor = remember(viewModel) { LabelEditor(viewModel) }
    val labels by viewModel.labels.collectAsStateWithLifecycle()
    when (val edit = editor.pending) {
        null -> Unit
        is LabelEditor.Edit.Rename -> TextInputDialog(
            title = "Rename",
            initial = edit.current,
            confirmLabel = "Save",
            label = edit.original?.let { "Name (blank for $it)" } ?: "Name",
            // Blank is allowed: it restores the thing's own name.
            validate = { true },
            onConfirm = { viewModel.rename(edit.subject, it, edit.original) },
            onDismiss = { editor.pending = null },
        )
        is LabelEditor.Edit.Tags -> TagPickerSheet(
            name = edit.name,
            labels = labels,
            subjects = edit.subjects,
            onToggle = { viewModel.toggleTag(edit.subjects, it) },
            onNewTag = { editor.pending = LabelEditor.Edit.NewTag(edit.subjects, edit.name) },
            onDismiss = { editor.pending = null },
        )
        is LabelEditor.Edit.NewTag -> TextInputDialog(
            title = "New tag",
            initial = "",
            confirmLabel = "Create",
            label = "Tag",
            onConfirm = { name ->
                viewModel.createTag(name)
                if (edit.assignTo.isNotEmpty()) viewModel.toggleTag(edit.assignTo, name.trim())
            },
            onDismiss = {
                // Back to the picker it came from, so creating a tag reads as one step of tagging.
                editor.pending = edit.assignTo.takeIf { it.isNotEmpty() }?.let { LabelEditor.Edit.Tags(it, edit.name) }
            },
        )
    }
    return editor
}

/**
 * Every tag, each a toggle for [subjects], plus a way to make a new one. Applies as it is tapped. For several
 * subjects a tag reads as on only when ALL of them carry it, and tapping it then removes it from all.
 */
@Composable
private fun TagPickerSheet(
    name: String,
    labels: Labels,
    subjects: List<LabelSubject>,
    onToggle: (String) -> Unit,
    onNewTag: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismiss = onDismiss,
        title = "Tags",
        subtitle = name.takeIf { it.isNotBlank() },
        trailingActions = listOf(HeaderAction(Res.drawable.ic_lc_plus, "New tag", onClick = onNewTag)),
    ) {
        // Chosen tags are brightened, like every picker; tap again to remove.
        AppMenu(
            items = labels.tags.map { tag ->
                AppMenuEntry(
                    key = tag,
                    title = tag,
                    selected = subjects.isNotEmpty() && subjects.all { s -> labels[s].tags.any { it.equals(tag, ignoreCase = true) } },
                    onClick = { onToggle(tag) },
                )
            },
        )
    }
}

/**
 * Rename, Tags and Pin for any labelled collection — the actions every labelled row, detail page and
 * selection offers, in the same order and words. [subject] says what an item is to the label system; [name]
 * is how it is shown now (its alias, if any) and [original] its own name. Tags and Pin work on a selection;
 * Rename is one item's.
 */
fun <T> labelActions(
    editor: LabelEditor,
    labels: () -> Labels,
    subject: (T) -> LabelSubject,
    name: (T) -> String,
    original: (T) -> String?,
): List<CollectionAction<T>> = listOf(
    CollectionAction(id = "rename", label = "Rename", iconRes = Res.drawable.ic_lc_pencil, scope = ActionScope.One) { targets ->
        val item = targets.single()
        editor.rename(subject(item), name(item), original(item))
    },
    CollectionAction(id = "tags", label = "Tags", iconRes = Res.drawable.ic_lc_tag) { targets ->
        editor.editTags(targets.map(subject), if (targets.size == 1) name(targets.single()) else "${targets.size} selected")
    },
    toggleAction(
        id = "pin",
        on = "Pin",
        off = "Unpin",
        iconRes = Res.drawable.ic_lc_pin,
        isOn = { item: T -> labels()[subject(item)].pinned },
    ) { targets, pinned -> editor.viewModel.setPinned(targets.map(subject), pinned) },
)
