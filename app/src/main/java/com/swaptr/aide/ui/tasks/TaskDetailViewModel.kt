package com.swaptr.aide.ui.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.swaptr.aide.data.task.TaskRepository
import com.swaptr.aide.domain.task.Task
import com.swaptr.aide.domain.task.toDomain
import com.swaptr.aide.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TaskDetailUiState(
    val task: Task? = null,
    val groupName: String = "",
    val loaded: Boolean = false,
    val deleted: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    private val tasks: TaskRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val taskId: String = savedStateHandle.toRoute<Route.TaskDetail>().taskId

    private val _deleted = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TaskDetailUiState> = combine(
        tasks.observe(taskId).map { it?.toDomain() },
        tasks.observeAllGroups(),
        _deleted,
        _error,
    ) { task, groups, deleted, err ->
        TaskDetailUiState(
            task = task,
            groupName = task?.let { t -> groups.firstOrNull { it.id == t.groupId }?.name.orEmpty() }
                .orEmpty(),
            loaded = true,
            deleted = deleted,
            error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskDetailUiState())

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { tasks.setHidden(taskId, !enabled) }
    }

    suspend fun cloneAndGetId(): String = tasks.clone(taskId).id

    fun delete() {
        viewModelScope.launch {
            try {
                tasks.delete(taskId)
                _deleted.value = true
            } catch (t: Throwable) {
                _error.value = t.message ?: "Could not delete task."
            }
        }
    }

    fun clearError() { _error.value = null }
}
