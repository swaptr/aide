package com.sabreware.aide.feature.tasks.ui

import com.sabreware.aide.core.designsystem.state.stateInUi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabreware.aide.feature.tasks.domain.Task
import com.sabreware.aide.feature.tasks.domain.TaskRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class TaskDetailUiState(
    val task: Task? = null,
    val groupName: String = "",
    val loaded: Boolean = false,
    val error: String? = null,
)

/**
 * Detail/delete state for one task. The id arrives with its [TaskRoute.Detail] route (the app norm —
 * see [com.sabreware.aide.ui.chat.ChatViewModel]); a successful delete is a one-shot [closeEvents] effect
 * rather than a folded flag, so the caller navigates away exactly once.
 */
class TaskDetailViewModel(
    private val tasks: TaskRepository,
    route: TaskRoute.Detail,
) : ViewModel() {

    private val taskId: String = route.taskId

    private val _error = MutableStateFlow<String?>(null)

    private val _closeEvents = MutableSharedFlow<Unit>()
    /** Emits once when the task is deleted; the screen navigates back. */
    val closeEvents: SharedFlow<Unit> = _closeEvents.asSharedFlow()

    val uiState: StateFlow<TaskDetailUiState> = combine(
        tasks.observe(taskId),
        tasks.observeAllGroups(),
        _error,
    ) { task, groups, err ->
        TaskDetailUiState(
            task = task,
            groupName = task?.let { t -> groups.firstOrNull { it.id == t.groupId }?.name.orEmpty() }
                .orEmpty(),
            loaded = true,
            error = err,
        )
    }.stateInUi(viewModelScope, TaskDetailUiState()) { error ->
        TaskDetailUiState(loaded = true, error = error.message ?: "Could not load this task.")
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { tasks.setHidden(taskId, !enabled) }
    }

    private var cloning = false

    /** Clone on the view-model scope (survives the page being replaced mid-flight) with an
     * in-flight guard so a double-tap can't create two clones. */
    fun cloneAndEdit(onCloned: (String) -> Unit) {
        if (cloning) return
        cloning = true
        viewModelScope.launch {
            try {
                onCloned(tasks.clone(taskId).id)
            } catch (t: Throwable) {
                _error.value = t.message ?: "Could not clone task."
            } finally {
                cloning = false
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            try {
                tasks.delete(taskId)
                _closeEvents.emit(Unit)
            } catch (t: Throwable) {
                _error.value = t.message ?: "Could not delete task."
            }
        }
    }

    fun clearError() { _error.value = null }
}
