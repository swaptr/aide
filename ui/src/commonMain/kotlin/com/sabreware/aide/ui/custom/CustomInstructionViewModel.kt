package com.sabreware.aide.ui.custom

import androidx.lifecycle.ViewModel
import com.sabreware.aide.core.domain.custom.CustomInstructionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CustomInstructionUiState(val text: String = "") {
    val canSubmit: Boolean get() = text.isNotBlank()
}

class CustomInstructionViewModel(
    private val repo: CustomInstructionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CustomInstructionUiState())
    val uiState: StateFlow<CustomInstructionUiState> = _uiState.asStateFlow()

    fun onTextChange(value: String) = _uiState.update { it.copy(text = value) }

    /** Submits the one-shot instruction; returns false if blank so the caller keeps the screen open. */
    fun submit(): Boolean {
        val instruction = _uiState.value.text.trim()
        if (instruction.isEmpty()) return false
        repo.submit(instruction)
        return true
    }
}
