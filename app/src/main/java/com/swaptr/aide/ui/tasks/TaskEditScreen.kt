package com.swaptr.aide.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.swaptr.aide.data.task.TaskGroupEntity
import com.swaptr.aide.ui.common.AppPage

@Composable
fun TaskEditScreen(
    taskId: String?,
    groupId: String?,
    onClose: () -> Unit,
    viewModel: TaskEditViewModel = hiltViewModel(),
) {
    LaunchedEffect(taskId, groupId) { viewModel.load(taskId, groupId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val groups by viewModel.availableGroups.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onClose()
    }

    var showNewGroup by remember { mutableStateOf(false) }

    AppPage(
        title = if (state.editingId == null) "New task" else "Edit task",
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_lc_arrow_left), contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = { viewModel.save() }) {
                Icon(painterResource(R.drawable.ic_lc_check), contentDescription = "Save")
            }
        },
    ) {
        if (state.loading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@AppPage
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::setDescription,
                label = { Text("Description (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
            GroupDropdown(
                groups = groups,
                selectedId = state.groupId,
                onSelected = viewModel::setGroupId,
                onCreateNew = { showNewGroup = true },
            )
            OutlinedTextField(
                value = state.promptTemplate,
                onValueChange = viewModel::setPromptTemplate,
                label = { Text("Prompt template (must include {text})") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp),
            )
            Text(
                "Use {text} as the placeholder for the user's selected text. " +
                    "End the prompt with something like \"Result:\" so the model emits only the transformed output.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = { viewModel.save() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.editingId == null) "Create task" else "Save changes")
            }
        }
    }

    if (showNewGroup) {
        NewGroupDialog(
            onDismiss = { showNewGroup = false },
            onConfirm = { name ->
                showNewGroup = false
                viewModel.createGroupAndSelect(name)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDropdown(
    groups: List<TaskGroupEntity>,
    selectedId: String,
    onSelected: (String) -> Unit,
    onCreateNew: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = groups.firstOrNull { it.id == selectedId }?.name.orEmpty()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Group") },
            placeholder = { Text("Pick a group") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            groups.forEach { g ->
                DropdownMenuItem(
                    text = { Text(g.name) },
                    onClick = {
                        onSelected(g.id)
                        expanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("+ Create new group…") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_lc_plus), contentDescription = null) },
                onClick = {
                    expanded = false
                    onCreateNew()
                },
            )
        }
    }
}

@Composable
private fun NewGroupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New group") },
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
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
