package com.swaptr.aide.ui.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.swaptr.aide.R
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swaptr.aide.domain.task.Task
import com.swaptr.aide.ui.common.AppMenuEntry
import com.swaptr.aide.ui.common.AppMenuList
import com.swaptr.aide.ui.common.AppMenuTrailingSwitch
import com.swaptr.aide.ui.common.AppPage
import com.swaptr.aide.ui.common.SwipeableTabbedContent

@Composable
fun TaskListScreen(
    onOpenDrawer: () -> Unit,
    onAddNew: (groupId: String?) -> Unit,
    onOpenTask: (String) -> Unit,
    viewModel: TaskListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showNewGroup by remember { mutableStateOf(false) }
    var selectedGroupId by remember { mutableStateOf<String?>(null) }
    val activeGroupId: String? = state.sections.firstOrNull { it.group.id == selectedGroupId }?.group?.id
        ?: state.sections.firstOrNull()?.group?.id

    LaunchedEffect(state.transientError) {
        val msg = state.transientError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearTransientError()
    }

    AppPage(
        title = "Keyboard Tasks",
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(painterResource(R.drawable.ic_lc_menu), contentDescription = "Menu")
            }
        },
        actions = {
            IconButton(onClick = { showNewGroup = true }) {
                Icon(
                    painterResource(R.drawable.ic_lc_folder_plus),
                    contentDescription = "New group",
                )
            }
            IconButton(onClick = { onAddNew(activeGroupId) }) {
                Icon(painterResource(R.drawable.ic_lc_plus), contentDescription = "New task")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        scrollable = false,
    ) {
        when {
            !state.loaded -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.sections.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No groups yet. Tap the folder icon to create one.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
                )
            }
        }
    }

    if (showNewGroup) {
        GroupNameDialog(
            title = "New group",
            initial = "",
            onDismiss = { showNewGroup = false },
            onConfirm = { name ->
                showNewGroup = false
                viewModel.createGroup(name)
            },
        )
    }
}

@Composable
private fun TaskListBody(
    sections: List<TaskGroupSection>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    onOpenTask: (String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        SwipeableTabbedContent(
            tabs = sections.map { it.group.name },
            selectedIndex = selectedIndex,
            onSelectIndex = onSelectIndex,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val section = sections[page]
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                if (section.tasks.isEmpty()) {
                    item(key = "empty-${section.group.id}") {
                        Text(
                            "No tasks. Tap + to add one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    items(section.tasks, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            onOpenTask = onOpenTask,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: Task,
    onOpenTask: (String) -> Unit,
) {
    AppMenuList(
        items = listOf(
            AppMenuEntry(
                key = task.id,
                title = task.name,
                subtitle = task.description.takeIf { it.isNotBlank() },
                onClick = { onOpenTask(task.id) },
                trailing = {
                    AppMenuTrailingSwitch(
                        checked = !task.isHidden,
                        onCheckedChange = null,
                    )
                },
            ),
        ),
    )
}

@Composable
private fun GroupNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.trim().isNotEmpty(),
                onClick = { onConfirm(name.trim()) },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
