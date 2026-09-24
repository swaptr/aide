package com.sabreware.aide.feature.tasks.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sabreware.aide.core.designsystem.AppListItem
import com.sabreware.aide.core.designsystem.HeaderAction
import com.sabreware.aide.core.designsystem.SkeletonListRow
import com.sabreware.aide.core.designsystem.rememberSkeletonShimmer
import com.sabreware.aide.core.designsystem.AppMenuTrailingSwitch
import com.sabreware.aide.core.designsystem.AppScaffold
import com.sabreware.aide.core.designsystem.ConstrainedContent
import com.sabreware.aide.core.designsystem.Placeholder
import com.sabreware.aide.core.designsystem.PlaceholderAction
import com.sabreware.aide.core.designsystem.SwipeableTabbedContent
import com.sabreware.aide.core.designsystem.TextInputDialog
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.feature.tasks.domain.Task
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun TaskListScreen(
    onOpenTask: (String) -> Unit,
    onAddTask: (groupId: String?) -> Unit,
    openNewTask: Boolean = false,
    onOpenNewTaskConsumed: () -> Unit = {},
    viewModel: TaskListViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    val activeGroupId: String? = state.sections.firstOrNull { it.group.id == selectedGroupId }?.group?.id
        ?: state.sections.firstOrNull()?.group?.id

    var showNewGroup by rememberSaveable { mutableStateOf(false) }

    // One-shot deep-link signal (e.g. the IME's "new task" shortcut) delivered via the nav entry.
    LaunchedEffect(openNewTask) {
        if (openNewTask) {
            onAddTask(null)
            onOpenNewTaskConsumed()
        }
    }

    LaunchedEffect(state.transientError) {
        val msg = state.transientError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearTransientError()
    }

    AppScaffold(
        title = "Tasks",
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Page actions in the header, like every collection page; the groups and their tasks below.
        trailingActions = listOfNotNull(
            HeaderAction(Res.drawable.ic_lc_plus, "New task", onClick = { onAddTask(activeGroupId) })
                .takeIf { state.sections.isNotEmpty() },
            HeaderAction(Res.drawable.ic_lc_folder_plus, "New group", onClick = { showNewGroup = true }),
        ),
    ) { scaffoldModifier ->
        ConstrainedContent(scaffoldModifier) { contentModifier ->
            Column(contentModifier) {
                val bodyModifier = Modifier.fillMaxWidth().weight(1f)
                when {
                    !state.loaded -> TaskListSkeleton(bodyModifier)

                    state.sections.isEmpty() -> Placeholder(
                        modifier = bodyModifier,
                        iconRes = Res.drawable.ic_lc_folder,
                        title = "No groups yet",
                        subtitle = "Create a group to sort the tasks shown on your keyboard.",
                        actions = listOf(
                            PlaceholderAction(
                                label = "New group",
                                iconRes = Res.drawable.ic_lc_folder_plus,
                                onClick = { showNewGroup = true },
                            ),
                        ),
                    )

                    else -> {
                        val activeIndex = state.sections.indexOfFirst { it.group.id == activeGroupId }
                            .coerceAtLeast(0)
                        TaskListBody(
                            sections = state.sections,
                            selectedIndex = activeIndex,
                            onSelectIndex = { i ->
                                selectedGroupId = state.sections.getOrNull(i)?.group?.id
                            },
                            onOpenTask = onOpenTask,
                            onAddNew = onAddTask,
                            onSetShown = viewModel::setShown,
                            modifier = bodyModifier,
                        )
                    }
                }
            }
        }
    }

    if (showNewGroup) {
        TextInputDialog(
            title = "New group",
            initial = "",
            confirmLabel = "Create",
            label = "Name",
            onConfirm = { name -> viewModel.createGroup(name) { id -> selectedGroupId = id } },
            onDismiss = { showNewGroup = false },
        )
    }
}

@Composable
private fun TaskListBody(
    sections: List<TaskGroupSection>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    onOpenTask: (String) -> Unit,
    onAddNew: (groupId: String?) -> Unit,
    onSetShown: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Stable tab list while the VM state is unchanged, so the tab row can skip.
    val tabs = remember(sections) { sections.map { it.group.name } }
    SwipeableTabbedContent(
        tabs = tabs,
        selectedIndex = selectedIndex,
        onSelectIndex = onSelectIndex,
        modifier = modifier,
    ) { page ->
        val section = sections[page]
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            if (section.tasks.isEmpty()) {
                item(key = "empty-${section.group.id}") {
                    Placeholder(
                        modifier = Modifier.fillParentMaxSize(),
                        iconRes = Res.drawable.ic_lc_list_plus,
                        title = "No tasks yet",
                        subtitle = "Add a task to this group.",
                        actions = listOf(
                            PlaceholderAction(
                                label = "New task",
                                iconRes = Res.drawable.ic_lc_plus,
                                onClick = { onAddNew(section.group.id) },
                            ),
                        ),
                    )
                }
            } else {
                items(section.tasks, key = { it.id }) { task ->
                    TaskRow(task = task, onOpenTask = onOpenTask, onSetShown = onSetShown)
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: Task,
    onOpenTask: (String) -> Unit,
    onSetShown: (String, Boolean) -> Unit,
) {
    AppListItem(
        headline = task.name,
        supportingText = task.description.takeIf { it.isNotBlank() },
        onClick = { onOpenTask(task.id) },
        trailing = {
            AppMenuTrailingSwitch(
                checked = !task.isHidden,
                onCheckedChange = { onSetShown(task.id, it) },
            )
        },
    )
}

/** Skeleton rows while the first read of the task database is in flight. */
@Composable
private fun TaskListSkeleton(modifier: Modifier = Modifier) {
    val shimmer = rememberSkeletonShimmer()
    Column(modifier) { repeat(4) { SkeletonListRow(shimmer, lines = 2, index = it) } }
}
