package com.swaptr.aide.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.swaptr.aide.R
import com.swaptr.aide.ui.common.AppMenuEntry
import com.swaptr.aide.ui.common.AppMenuList
import com.swaptr.aide.ui.common.AppMenuToggle
import com.swaptr.aide.ui.common.AppPage
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun TaskDetailScreen(
    onClose: () -> Unit,
    onEdit: (String) -> Unit,
    onCloneAndEdit: (String) -> Unit,
    viewModel: TaskDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.deleted) { if (state.deleted) onClose() }

    AppPage(
        title = state.task?.name.orEmpty(),
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_lc_arrow_left), contentDescription = "Back")
            }
        },
        actions = {
            val task = state.task
            if (task != null && !task.isBuiltIn) {
                IconButton(onClick = { pendingDelete = true }) {
                    Icon(painterResource(R.drawable.ic_lc_trash), contentDescription = "Delete")
                }
            }
        },
        scrollable = false,
    ) {
        val task = state.task
        when {
            !state.loaded -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            task == null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Task not found.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> Column(modifier = Modifier.fillMaxSize()) {
                val scroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scroll),
                ) {
                    AppMenuList(
                        items = listOf(
                            AppMenuEntry(
                                title = "Enabled",
                                subtitle = if (!task.isHidden) "Available in keyboard chip strip"
                                else "Hidden from keyboard chip strip",
                                toggle = AppMenuToggle(
                                    checked = !task.isHidden,
                                    onCheckedChange = { viewModel.setEnabled(it) },
                                ),
                            ),
                        ),
                    )
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

                    Section(label = "Prompt") {
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { onEdit(task.id) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(painterResource(R.drawable.ic_lc_pencil), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Edit")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val newId = viewModel.cloneAndGetId()
                                onCloneAndEdit(newId)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(painterResource(R.drawable.ic_lc_copy), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Clone")
                    }
                }
            }
        }
    }

    if (pendingDelete) {
        val name = state.task?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text("Delete task?") },
            text = { Text("\"$name\" will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = false
                    viewModel.delete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) { Text("Cancel") }
            },
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
