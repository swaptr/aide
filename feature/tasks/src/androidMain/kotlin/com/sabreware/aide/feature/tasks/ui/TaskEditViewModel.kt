package com.sabreware.aide.feature.tasks.ui

import com.sabreware.aide.core.designsystem.state.stateInUi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabreware.aide.core.designsystem.form.Rules
import com.sabreware.aide.core.designsystem.form.validate
import com.sabreware.aide.feature.tasks.domain.Task
import com.sabreware.aide.feature.tasks.domain.TaskGroup
import com.sabreware.aide.feature.tasks.domain.TaskRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TaskEditUiState(
    val editingId: String? = null,
    val name: String = "",
    val description: String = "",
    val promptTemplate: String = "Rewrite the following text. Keep the response " +
        "short and to the point. Return a single answer — no alternatives, no " +
        "options, no preamble, no commentary.\n\nText:\n{text}\n\nResult:",
    val groupId: String = "",
    val nameError: String? = null,
    val groupError: String? = null,
    val promptError: String? = null,
    val error: String? = null,
    val loading: Boolean = true,
    val isEditing: Boolean = false,
)

/**
 * Create/edit one task. The target id + prefilled group arrive with its [TaskRoute.Edit] route (the
 * app norm); a successful save is a one-shot [savedEvents] effect (not a folded `saved` flag), so the
 * screen closes exactly once even though this view-model outlives the page in the back stack.
 */
class TaskEditViewModel(
    private val tasks: TaskRepository,
    private val route: TaskRoute.Edit,
) : ViewModel() {

    // isEditing is known from the route immediately (before the DB read) so the title never flashes.
    private val _state = MutableStateFlow(TaskEditUiState(isEditing = route.taskId != null))
    val state: StateFlow<TaskEditUiState> = _state.asStateFlow()

    private val _savedEvents = MutableSharedFlow<Unit>()
    /** Emits once when the task is saved; the screen navigates back. */
    val savedEvents: SharedFlow<Unit> = _savedEvents.asSharedFlow()

    private val nameRules = listOf(Rules.required("Name can't be empty."))
    private val groupRules = listOf(Rules.required("Pick a group for this task."))
    private val promptRules = listOf(
        Rules.mustContain(Task.PLACEHOLDER, "Prompt template must contain ${Task.PLACEHOLDER}."),
    )

    // The group picker's options. An unreadable list means "no groups to choose from" — the form's own
    // required-group rule then blocks the save, which is the right outcome; a frozen flow was not.
    val availableGroups: StateFlow<List<TaskGroup>> = tasks.observeGroups()
        .stateInUi(viewModelScope, emptyList()) { emptyList() }

    init {
        viewModelScope.launch {
            val taskId = route.taskId
            if (taskId == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    groupId = route.groupId.orEmpty(),
                )
                return@launch
            }
            val existing = tasks.getById(taskId)
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
                isEditing = true,
            )
        }
    }

    fun setName(value: String) {
        _state.value = _state.value.copy(name = value, nameError = nameRules.validate(value))
    }
    fun setDescription(value: String) { _state.value = _state.value.copy(description = value) }
    fun setPromptTemplate(value: String) {
        _state.value = _state.value.copy(promptTemplate = value, promptError = promptRules.validate(value))
    }
    fun setGroupId(value: String) {
        _state.value = _state.value.copy(groupId = value, groupError = groupRules.validate(value))
    }

    fun createGroupAndSelect(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                val group = tasks.createGroup(name)
                _state.value = _state.value.copy(groupId = group.id, groupError = null)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = t.message ?: "Could not create group.")
            }
        }
    }

    fun save() {
        val s = _state.value
        val nameError = nameRules.validate(s.name)
        val groupError = groupRules.validate(s.groupId)
        val promptError = promptRules.validate(s.promptTemplate)
        if (nameError != null || groupError != null || promptError != null) {
            _state.value = s.copy(
                nameError = nameError,
                groupError = groupError,
                promptError = promptError,
            )
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
                _savedEvents.emit(Unit)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = t.message ?: "Save failed.")
            }
        }
    }
}
