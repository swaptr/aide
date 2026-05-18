package com.swaptr.aide.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swaptr.aide.data.task.TaskGroupEntity
import com.swaptr.aide.data.task.TaskRepository
import com.swaptr.aide.domain.task.toDomain
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TaskEditUiState(
    val editingId: String? = null,
    val name: String = "",
    val description: String = "",
    val promptTemplate: String = "Rewrite the following text. Keep the response " +
        "short and to the point. Return a single answer — no alternatives, no " +
        "options, no preamble, no commentary.\n\nText:\n{text}\n\nResult:",
    val groupId: String = "",
    val error: String? = null,
    val saved: Boolean = false,
    val loading: Boolean = true,
)

@HiltViewModel
class TaskEditViewModel @Inject constructor(
    private val tasks: TaskRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TaskEditUiState())
    val state: StateFlow<TaskEditUiState> = _state.asStateFlow()

    val availableGroups: StateFlow<List<TaskGroupEntity>> = tasks.observeGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var loaded = false

    fun load(taskId: String?, prefilledGroupId: String?) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            if (taskId == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    groupId = prefilledGroupId.orEmpty(),
                )
                return@launch
            }
            val existing = tasks.getById(taskId)?.toDomain()
            if (existing == null) {
                _state.value = _state.value.copy(loading = false, error = "Task not found.")
                return@launch
            }
            _state.value = TaskEditUiState(
                editingId = existing.id,
                name = existing.name,
                description = existing.description,
                promptTemplate = existing.promptTemplate,
                groupId = existing.groupId,
                loading = false,
            )
        }
    }

    fun setName(value: String) { _state.value = _state.value.copy(name = value, error = null) }
    fun setDescription(value: String) { _state.value = _state.value.copy(description = value) }
    fun setPromptTemplate(value: String) {
        _state.value = _state.value.copy(promptTemplate = value, error = null)
    }
    fun setGroupId(value: String) { _state.value = _state.value.copy(groupId = value, error = null) }

    fun createGroupAndSelect(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                val group = tasks.createGroup(name)
                _state.value = _state.value.copy(groupId = group.id, error = null)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = t.message ?: "Could not create group.")
            }
        }
    }

    fun save() {
        val s = _state.value
        if (s.name.isBlank()) {
            _state.value = s.copy(error = "Name can't be empty.")
            return
        }
        if (s.groupId.isBlank()) {
            _state.value = s.copy(error = "Pick a group for this task.")
            return
        }
        if (!s.promptTemplate.contains(TaskRepository.PLACEHOLDER)) {
            _state.value = s.copy(error = "Prompt template must contain ${TaskRepository.PLACEHOLDER}.")
            return
        }
        viewModelScope.launch {
            try {
                if (s.editingId == null) {
                    tasks.createCustom(s.name, s.description, s.promptTemplate, s.groupId)
                } else {
                    tasks.updateTask(
                        s.editingId,
                        s.name,
                        s.description,
                        s.promptTemplate,
                        s.groupId,
                    )
                }
                _state.value = _state.value.copy(saved = true)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = t.message ?: "Save failed.")
            }
        }
    }
}
