package com.sabreware.aide.feature.tasks.ui

import com.sabreware.aide.core.designsystem.state.stateInUi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabreware.aide.feature.tasks.domain.Task
import com.sabreware.aide.feature.tasks.domain.TaskGroup
import com.sabreware.aide.feature.tasks.domain.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch



// loaded distinguishes "first DB read in flight" from "DB empty" so spinner shows pre-seed.
data class TaskListUiState(
    val sections: List<TaskGroupSection> = emptyList(),
    val loaded: Boolean = false,
    val transientError: String? = null,
)

data class TaskGroupSection(
    val group: TaskGroup,
    val tasks: List<Task>,
)

class TaskListViewModel(
    private val tasks: TaskRepository,
) : ViewModel() {

    // Surfaced through [TaskListUiState.transientError] only — no separate public flow.
    private val _transientError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TaskListUiState> = combine(
        tasks.observeGroups(),
        tasks.observeAll(),
        _transientError,
    ) { groups, allTasks, err ->
        val byGroupId = allTasks.groupBy { it.groupId }
        val sections = groups.map { g ->
            TaskGroupSection(
                group = g,
                tasks = byGroupId[g.id].orEmpty()
                    .sortedWith(compareBy({ it.isHidden }, { it.name })),
            )
        }
        TaskListUiState(sections = sections, loaded = true, transientError = err)
        // Room-backed, so a query failure is real and worth showing. Without this the throw cancelled the
        // sharing coroutine and `loaded` stayed false — a spinner with no end and no explanation.
    }.stateInUi(viewModelScope, TaskListUiState()) { error ->
        TaskListUiState(loaded = true, transientError = error.message ?: "Could not load tasks.")
    }

    fun createGroup(name: String, onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val group = tasks.createGroup(name)
                onCreated(group.id)
            } catch (t: Throwable) {
                _transientError.value = t.message ?: "Could not create group."
            }
        }
    }

    fun renameGroup(id: String, newName: String) {
        viewModelScope.launch {
            try {
                tasks.renameGroup(id, newName)
            } catch (t: Throwable) {
                _transientError.value = t.message ?: "Could not rename group."
            }
        }
    }

    fun deleteGroup(id: String) {
        viewModelScope.launch {
            try {
                tasks.deleteGroup(id)
            } catch (t: Throwable) {
                _transientError.value = t.message ?: "Could not delete group."
            }
        }
    }

    /** Shows or hides a task on the keyboard. */
    fun setShown(id: String, shown: Boolean) {
        viewModelScope.launch {
            try {
                tasks.setHidden(id, !shown)
            } catch (t: Throwable) {
                _transientError.value = t.message ?: "Could not update task."
            }
        }
    }

    fun clearTransientError() {
        _transientError.value = null
    }
}
