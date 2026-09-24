package com.sabreware.aide.feature.tasks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.AppMenuLayout
import com.sabreware.aide.core.designsystem.SkeletonListRow
import com.sabreware.aide.core.designsystem.rememberSkeletonShimmer
import com.sabreware.aide.core.designsystem.AppMenuToggle
import com.sabreware.aide.core.designsystem.AppNotice
import com.sabreware.aide.core.designsystem.AppPage
import com.sabreware.aide.core.designsystem.DeleteConfirmDialog
import com.sabreware.aide.core.designsystem.Placeholder
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.navigation.navigator
import org.koin.compose.viewmodel.koinViewModel

/**
 * Task detail as a full nav destination (Route.TaskDetail). The screen's app bar owns the title
 * (the loaded task name) and the back arrow; the body scrolls via [AppPage]. A rail under the header holds
 * its actions: Edit and Duplicate navigate out to the editor; Delete asks first in a [DeleteConfirmDialog].
 *
 * The view-model is scoped to this nav entry and reads the task id from the route via
 * `SavedStateHandle`, so the detail stays live-updating while the editor sits on top of it.
 */
@Composable
fun TaskDetailScreen(
    onEdit: () -> Unit,
    onCloned: (String) -> Unit,
    viewModel: TaskDetailViewModel = koinViewModel(),
) {
    val nav = navigator()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.closeEvents.collect { nav.goBack() } }

    AppPage(
        title = state.task?.name.orEmpty(),
    ) {
        val task = state.task
        when {
            !state.loaded -> {
                val shimmer = rememberSkeletonShimmer()
                repeat(3) { SkeletonListRow(shimmer, lines = 2, index = it) }
            }

            task == null -> Placeholder(
                title = "Task unavailable",
                subtitle = state.error ?: "This task could not be found.",
            )

            else -> {
                // Every action on this task in one rail under the header; its facts below.
                AppMenu(
                    items = listOfNotNull(
                        AppMenuEntry(key = "edit", title = "Edit", leadingIconRes = Res.drawable.ic_lc_pencil, onClick = onEdit),
                        AppMenuEntry(
                            key = "duplicate",
                            title = "Duplicate",
                            leadingIconRes = Res.drawable.ic_lc_copy,
                            onClick = { viewModel.cloneAndEdit(onCloned) },
                        ),
                        AppMenuEntry(
                            key = "delete",
                            title = "Delete",
                            leadingIconRes = Res.drawable.ic_lc_trash,
                            destructive = true,
                            onClick = { showDelete = true },
                        ).takeIf { !task.isBuiltIn },
                    ),
                    layout = AppMenuLayout.Rail(),
                )
                // Surfaces async action failures on this screen (e.g. a failed clone).
                AppNotice(state.error, modifier = Modifier.padding(horizontal = 20.dp))
                // remember: a stable entry list lets the menu row skip when unrelated state
                // (error banner, group name) changes.
                val enabledItems = remember(task.isHidden, viewModel) {
                    listOf(
                        AppMenuEntry(
                            title = "Shown on the keyboard",
                            toggle = AppMenuToggle(
                                checked = !task.isHidden,
                                onCheckedChange = { viewModel.setEnabled(it) },
                            ),
                        ),
                    )
                }
                AppMenu(items = enabledItems)
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (task.description.isNotBlank()) {
                        Section(label = "Description") {
                            Text(task.description, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    if (state.groupName.isNotBlank()) {
                        Section(label = "Group") {
                            Text(state.groupName, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Section(label = "Instructions") {
                        SelectionContainer {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(12.dp),
                                    )
                                    .padding(12.dp),
                            ) {
                                Text(
                                    text = task.promptTemplate,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDelete) {
        DeleteConfirmDialog(
            title = "Delete task?",
            message = "\"${state.task?.name.orEmpty()}\" will be removed. This can't be undone.",
            onDismiss = { showDelete = false },
            onConfirm = { viewModel.delete() },
        )
    }
}

@Composable
private fun Section(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}
