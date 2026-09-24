package com.sabreware.aide.data.custom

import com.sabreware.aide.core.domain.custom.CustomInstructionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// One-shot Activity→IME instruction channel; MutableStateFlow (not SharedFlow) so a
// late observer (IME after re-attach) still sees the pending value.
class CustomInstructionRepositoryImpl : CustomInstructionRepository {

    private val _pending = MutableStateFlow<String?>(null)
    override val pending: StateFlow<String?> = _pending.asStateFlow()

    override fun submit(instruction: String) {
        val trimmed = instruction.trim()
        if (trimmed.isEmpty()) return
        _pending.value = trimmed
    }

    override fun consume() {
        _pending.value = null
    }
}
