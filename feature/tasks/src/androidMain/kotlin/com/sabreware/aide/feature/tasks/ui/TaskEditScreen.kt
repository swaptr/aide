package com.sabreware.aide.feature.tasks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.AppNotice
import com.sabreware.aide.core.designsystem.AppPage
import com.sabreware.aide.core.designsystem.AppTextField
import com.sabreware.aide.core.designsystem.TextInputDialog
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.navigation.navigator
import com.sabreware.aide.feature.tasks.domain.TaskGroup
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Task create/edit as a full nav destination (Route.TaskEdit). The app bar owns the title/back;
 * saving happens via the inline primary button and pops back to wherever we came from (the list, or
 * the detail screen which then live-updates). "Create new group" opens a [TextInputDialog] dialog.
 *
 * The view-model is scoped to this nav entry and reads the target id + prefilled group from the route
 * via `SavedStateHandle`, so the in-progress edits survive recreation.
 */
@Composable
fun TaskEditScreen(
    viewModel: TaskEditViewModel = koinViewModel(),
) {
    val nav = navigator()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val groups by viewModel.availableGroups.collectAsStateWithLifecycle()
    var showNewGroup by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.savedEvents.collect { nav.goBack() } }

    AppPage(
        title = if (state.isEditing) "Edit task" else "New task",
    ) {
        if (state.loading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = "Name",
                    singleLine = true,
                    errorText = state.nameError,
                )
                AppTextField(
                    value = state.description,
                    onValueChange = viewModel::setDescription,
                    label = "Description (optional)",
                )
                GroupDropdown(
                    groups = groups,
                    selectedId = state.groupId,
                    onSelected = viewModel::setGroupId,
                    onCreateNew = { showNewGroup = true },
                    errorText = state.groupError,
                )
                AppTextField(
                    value = state.promptTemplate,
                    onValueChange = viewModel::setPromptTemplate,
                    label = "Instructions",
                    errorText = state.promptError,
                    helperText = "Use {text} where your typed text goes.",
                    modifier = Modifier.heightIn(min = 180.dp, max = 320.dp),
                )
                AppNotice(state.error)
                Button(
                    onClick = { viewModel.save() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.editingId == null) "Create task" else "Save changes")
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
            onConfirm = { viewModel.createGroupAndSelect(it) },
            onDismiss = { showNewGroup = false },
        )
    }
}

/**
 * Group selector. The read-only [AppTextField] is display-only — a transparent overlay makes the whole
 * field tap to open a bottom-sheet picker (groups + "Create new group"), matching the app's other
 * sheet-based menus instead of an anchored dropdown.
 */
@Composable
private fun GroupDropdown(
    groups: List<TaskGroup>,
    selectedId: String,
    onSelected: (String) -> Unit,
    onCreateNew: () -> Unit,
    errorText: String? = null,
) {
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val selectedName = groups.firstOrNull { it.id == selectedId }?.name.orEmpty()
    Box {
        AppTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = "Group",
            placeholder = "Pick a group",
            errorText = errorText,
            trailingIcon = {
                Icon(painterResource(Res.drawable.ic_lc_chevron_down), contentDescription = null)
            },
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { sheetOpen = true },
        )
    }
    if (sheetOpen) {
        AppDialog(onDismiss = { sheetOpen = false }, title = "Group") { controller ->
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppMenu(
                    items = groups.map { g ->
                        AppMenuEntry(
                            title = g.name,
                            selected = g.id == selectedId,
                            onClick = { controller.close(andThen = { onSelected(g.id) }) },
                        )
                    },
                    groupPadding = PaddingValues(0.dp),
                    emptyMessage = "No groups yet",
                )
                AppMenu(
                    items = listOf(
                        AppMenuEntry(
                            title = "Create new group",
                            leadingIconRes = Res.drawable.ic_lc_plus,
                            onClick = { controller.close(andThen = onCreateNew) },
                        ),
                    ),
                    groupPadding = PaddingValues(0.dp),
                )
            }
        }
    }
}
