package com.swaptr.aide.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swaptr.aide.data.task.TaskGroupEntity
import com.swaptr.aide.data.task.TaskRepository
import com.swaptr.aide.domain.task.Task
import com.swaptr.aide.domain.task.toDomain
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch



// loaded distinguishes "first DB read in flight" from "DB empty" so spinner shows pre-seed.
data class TaskListUiState(
    val sections: List<TaskGroupSection> = emptyList(),
    val loaded: Boolean = false,
    val transientError: String? = null,
)

data class TaskGroupSection(
    val group: TaskGroupEntity,
    val tasks: List<Task>,
)

@HiltViewModel
class TaskListViewModel @Inject constructor(
    private val tasks: TaskRepository,
) : ViewModel() {

    private val _transientError = MutableStateFlow<String?>(null)
    val transientError: StateFlow<String?> = _transientError.asStateFlow()

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
                    .sortedWith(compareBy({ it.isHidden }, { it.name }))
                    .map { it.toDomain() },
            )
        }
        TaskListUiState(sections = sections, loaded = true, transientError = err)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskListUiState())

    fun createGroup(name: String) {
        viewModelScope.launch {
            try {
                tasks.createGroup(name)
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

    fun clearTransientError() {
        _transientError.value = null
    }
}
