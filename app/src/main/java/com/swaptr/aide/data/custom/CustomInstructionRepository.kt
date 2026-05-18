package com.swaptr.aide.data.custom

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

// One-shot Activity→IME instruction channel; MutableStateFlow (not SharedFlow) so a
// late observer (IME after re-attach) still sees the pending value.
@Singleton
class CustomInstructionRepository @Inject constructor() {

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun submit(instruction: String) {
        val trimmed = instruction.trim()
        if (trimmed.isEmpty()) return
        _pending.value = trimmed
    }

    fun consume() {
        _pending.value = null
    }
}
