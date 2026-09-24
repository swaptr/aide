package com.sabreware.aide.core.designsystem.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.backhandler.BackHandler
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuAction
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.AppMenuLayout
import com.sabreware.aide.core.designsystem.ConfirmDialog
import com.sabreware.aide.core.designsystem.DeleteConfirmDialog
import com.sabreware.aide.core.designsystem.HeaderAction
import com.sabreware.aide.core.designsystem.resources.*
import org.jetbrains.compose.resources.DrawableResource

// -------------------------------------------------------------------------------------------------------
// Acting on a collection: one action definition serves a single row (its long-press sheet, a detail page's
// menu) AND a multi-selection (the header in selection mode). Chats, models, connections and connectors all
// declare their actions this way, so rename / star / pin / tag / archive / refresh / delete look and behave
// the same everywhere, with confirmation for anything destructive built in.
// -------------------------------------------------------------------------------------------------------

/** Where an action is offered: on one item, on a selection of several, or both. */
enum class ActionScope { One, Many, Both;

    val single: Boolean get() = this != Many
    val bulk: Boolean get() = this != One
}

/** What to ask before an action runs. [destructive] colours the confirm button as an error. */
data class Confirmation(
    val title: String,
    val message: String? = null,
    val confirmLabel: String = "Delete",
    val destructive: Boolean = true,
)

/**
 * Something that can be done to items of type [T]. Everything is a function of the TARGETS — one item from a
 * row, several from a selection — so a label can read "Star" or "Unstar" by what the targets are, and an
 * action can hide itself where it means nothing ([available]).
 */
class CollectionAction<T>(
    val id: String,
    val label: (List<T>) -> String,
    val iconRes: (List<T>) -> DrawableResource,
    val scope: ActionScope = ActionScope.Both,
    val destructive: Boolean = false,
    val available: (List<T>) -> Boolean = { true },
    /** Asked first when non-null; the action runs only on confirm. */
    val confirm: ((List<T>) -> Confirmation)? = null,
    /**
     * Navigates away or replaces the page. A sheet showing this action closes first, with its animation, then
     * runs it (see [SheetDismiss]); anywhere else it simply runs. An action that stays in place (a toggle, a
     * dialog over the sheet) leaves this false.
     */
    val leavesSheet: Boolean = false,
    val perform: (List<T>) -> Unit,
) {
    constructor(
        id: String,
        label: String,
        iconRes: DrawableResource,
        scope: ActionScope = ActionScope.Both,
        destructive: Boolean = false,
        available: (List<T>) -> Boolean = { true },
        confirm: ((List<T>) -> Confirmation)? = null,
        leavesSheet: Boolean = false,
        perform: (List<T>) -> Unit,
    ) : this(id, { label }, { iconRes }, scope, destructive, available, confirm, leavesSheet, perform)
}

/**
 * How a sheet hosting an action's entry closes itself before a [CollectionAction.leavesSheet] action runs:
 * animate out, then invoke the block — `AppDialogController.close(andThen)` has exactly this shape. The page
 * behind must not move while the sheet is still on it: Material's guidance is to hide the sheet and act once
 * the hide completes.
 */
typealias SheetDismiss = (andThen: () -> Unit) -> Unit

/** Toggle-shaped action ("Star" / "Unstar"): [isOn] is true when EVERY target is on, so it turns them all off. */
fun <T> toggleAction(
    id: String,
    on: String,
    off: String,
    iconRes: DrawableResource,
    isOn: (T) -> Boolean,
    scope: ActionScope = ActionScope.Both,
    available: (List<T>) -> Boolean = { true },
    set: (List<T>, Boolean) -> Unit,
): CollectionAction<T> = CollectionAction(
    id = id,
    label = { targets -> if (targets.isNotEmpty() && targets.all(isOn)) off else on },
    iconRes = { iconRes },
    scope = scope,
    available = available,
) { targets -> set(targets, !(targets.isNotEmpty() && targets.all(isOn))) }

/** Which items are selected, by key, and whether selection mode is on. Saved with the host. */
@Stable
class SelectionState internal constructor(active: Boolean, keys: Set<String>) {
    var active: Boolean by mutableStateOf(active)
        private set
    var keys: Set<String> by mutableStateOf(keys)
        private set

    val count: Int get() = keys.size

    fun enter(first: String? = null) {
        active = true
        keys = setOfNotNull(first)
    }

    fun exit() {
        active = false
        keys = emptySet()
    }

    fun toggle(key: String) { keys = if (key in keys) keys - key else keys + key }

    operator fun contains(key: String): Boolean = key in keys

    /** Selects every key in [all], or clears the selection if all are already selected. */
    fun toggleAll(all: Collection<String>) { keys = if (keys.containsAll(all) && all.isNotEmpty()) emptySet() else all.toSet() }

    /** Drops keys that no longer exist (an item deleted, or filtered out, while selected). */
    fun retain(existing: Collection<String>) {
        val kept = keys.intersect(existing.toSet())
        if (kept.size != keys.size) keys = kept
    }
}

@Composable
fun rememberSelectionState(): SelectionState =
    rememberSaveable(saver = SelectionSaver) { SelectionState(false, emptySet()) }

private val SelectionSaver: Saver<SelectionState, List<String>> = Saver(
    save = { listOf(if (it.active) "1" else "0") + it.keys },
    restore = { SelectionState(it.firstOrNull() == "1", it.drop(1).toSet()) },
)

/**
 * Runs [CollectionAction]s for one surface: asks for confirmation where an action wants it, and knows how to
 * present the same actions as a row's long-press sheet, a detail page's header menu, or selection-mode
 * header buttons. Drawn by [rememberActionRunner], which also hosts the confirm dialog.
 */
@Stable
class ActionRunner<T> internal constructor(private val actions: () -> List<CollectionAction<T>>) {
    internal var pending: Pending<T>? by mutableStateOf(null)

    internal class Pending<T>(val action: CollectionAction<T>, val targets: List<T>, val dismiss: SheetDismiss?)

    /**
     * Runs [action] on [targets], via its confirmation if it has one. [dismiss] is the hosting sheet's close,
     * when the call comes from inside one: a [CollectionAction.leavesSheet] action closes the sheet first
     * (after any confirmation, which stays over the sheet) and runs once it is gone.
     */
    fun run(action: CollectionAction<T>, targets: List<T>, dismiss: SheetDismiss? = null) {
        if (targets.isEmpty() || !action.available(targets)) return
        if (action.confirm != null) pending = Pending(action, targets, dismiss) else perform(action, targets, dismiss)
    }

    internal fun perform(action: CollectionAction<T>, targets: List<T>, dismiss: SheetDismiss?) {
        if (action.leavesSheet && dismiss != null) dismiss { action.perform(targets) } else action.perform(targets)
    }

    /**
     * The actions for one [item], as a sheet's rows. A long-press sheet closes itself before running a row, so
     * [dismiss] is only for rows drawn inside ANOTHER sheet that a leaving action must close too.
     */
    fun menuFor(item: T, dismiss: SheetDismiss? = null): List<AppMenuAction> {
        val targets = listOf(item)
        return actions().filter { it.scope.single && it.available(targets) }.map { action ->
            AppMenuAction(label = action.label(targets), iconRes = action.iconRes(targets), destructive = action.destructive) {
                run(action, targets, dismiss)
            }
        }
    }

    /**
     * The actions for one [item] as menu entries (tiles or rows), leaving out [except] (ids shown elsewhere).
     * [dismiss] is the hosting sheet's close, when drawn inside one (see [run]).
     */
    fun entriesFor(item: T, except: Set<String> = emptySet(), dismiss: SheetDismiss? = null): List<AppMenuEntry> {
        val targets = listOf(item)
        return actions().filter { it.scope.single && it.id !in except && it.available(targets) }.map { action ->
            AppMenuEntry(
                key = action.id,
                title = action.label(targets),
                leadingIconRes = action.iconRes(targets),
                destructive = action.destructive,
                onClick = { run(action, targets, dismiss) },
            )
        }
    }

    /**
     * The actions for one [item] as header buttons, leaving out [except] — a page or tab about that item puts
     * them in its header, where past the button budget they fold into More.
     */
    fun headerActionsFor(item: T, except: Set<String> = emptySet(), dismiss: SheetDismiss? = null): List<HeaderAction> {
        val targets = listOf(item)
        return actions().filter { it.scope.single && it.id !in except && it.available(targets) }.map { action ->
            HeaderAction(
                iconRes = action.iconRes(targets),
                label = action.label(targets),
                destructive = action.destructive,
                onClick = { run(action, targets, dismiss) },
            )
        }
    }

    /** The actions for a selection, as header buttons — disabled while nothing is selected. */
    fun headerActionsFor(selected: List<T>): List<HeaderAction> =
        actions().filter { it.scope.bulk && (selected.isEmpty() || it.available(selected)) }.map { action ->
            HeaderAction(
                iconRes = action.iconRes(selected),
                label = action.label(selected),
                enabled = selected.isNotEmpty(),
                destructive = action.destructive,
                onClick = { run(action, selected) },
            )
        }
}

/** An [ActionRunner] over [actions] (re-read on every use, so labels track the items), with its dialog. */
@Composable
fun <T> rememberActionRunner(actions: List<CollectionAction<T>>): ActionRunner<T> {
    var current by remember { mutableStateOf(actions) }
    current = actions
    val runner = remember { ActionRunner { current } }
    runner.pending?.let { pending ->
        val action = pending.action
        val targets = pending.targets
        val confirmation = action.confirm!!(targets)
        val dismiss = { runner.pending = null }
        if (confirmation.destructive) {
            DeleteConfirmDialog(
                title = confirmation.title,
                message = confirmation.message,
                confirmLabel = confirmation.confirmLabel,
                onConfirm = { runner.perform(action, targets, pending.dismiss) },
                onDismiss = dismiss,
            )
        } else {
            ConfirmDialog(
                title = confirmation.title,
                message = confirmation.message,
                confirmLabel = confirmation.confirmLabel,
                onConfirm = { runner.perform(action, targets, pending.dismiss) },
                onDismiss = dismiss,
            )
        }
    }
    return runner
}

/**
 * The header of a page in selection mode, derived rather than hand-built: a Done button, "N selected", a
 * select-all toggle and the bulk actions. [normal] is what the page shows otherwise. System back leaves
 * selection mode first, like the Done button.
 */
data class CollectionHeader(
    val title: String?,
    val leadingAction: HeaderAction?,
    val trailingActions: List<HeaderAction>?,
    /** Drawn in the band instead of [title] — the search field while searching. */
    val titleContent: (@Composable () -> Unit)? = null,
    /** What the list is narrowed to ("Under 4B · Cloud"), under the title while filters apply outside search. */
    val subtitle: String? = null,
)

@Composable
fun <T> collectionHeader(
    selection: SelectionState,
    runner: ActionRunner<T>,
    visible: List<T>,
    key: (T) -> String,
    normal: CollectionHeader,
): CollectionHeader {
    BackHandler(enabled = selection.active) { selection.exit() }
    if (!selection.active) return normal
    val selected = visible.filter { key(it) in selection }
    val allKeys = visible.map(key)
    return CollectionHeader(
        title = if (selected.isEmpty()) "Select" else "${selected.size} selected",
        leadingAction = HeaderAction(Res.drawable.ic_lc_x, "Done", onClick = selection::exit),
        // The bulk actions first: past the header's button budget the rest fold into More, and "Select all"
        // is the one that reads fine there.
        trailingActions = runner.headerActionsFor(selected) + HeaderAction(
            Res.drawable.ic_lc_list_checks,
            if (allKeys.isNotEmpty() && selection.keys.containsAll(allKeys)) "Select none" else "Select all",
            enabled = allKeys.isNotEmpty(),
            onClick = { selection.toggleAll(allKeys) },
        ),
    )
}

/** The header action that enters selection mode — offered only when there is something to select. */
fun selectHeaderAction(selection: SelectionState, enabled: Boolean): HeaderAction =
    HeaderAction(Res.drawable.ic_lc_list_checks, "Select", enabled = enabled, onClick = { selection.enter() })

/**
 * One item's actions as a strip of tiles ([AppMenuLayout.actions]) — a sheet's toolbar, drawn from the SAME
 * definitions its row's long-press sheet uses, so the two never disagree. Nothing hides in a menu. [except] leaves out actions the sheet shows elsewhere
 * (Pin in the header). A grid inside a sheet passes the sheet's close as [dismiss], so an action that
 * [leaves][CollectionAction.leavesSheet] it animates the sheet out before it runs.
 */
@Composable
fun <T> ActionRail(
    runner: ActionRunner<T>,
    item: T,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    except: Set<String> = emptySet(),
    dismiss: SheetDismiss? = null,
) {
    val entries = runner.entriesFor(item, except, dismiss)
    if (entries.isNotEmpty()) AppMenu(items = entries, layout = AppMenuLayout.actions(), modifier = modifier)
}
