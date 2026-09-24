package com.sabreware.aide.core.domain.custom

import kotlinx.coroutines.flow.StateFlow

/**
 * One-shot Activity→IME custom-instruction channel. The implementation
 * ([com.sabreware.aide.data.custom.CustomInstructionRepositoryImpl]) backs [pending] with a
 * `MutableStateFlow` (not a `SharedFlow`) so a late observer — the IME re-attaching — still sees the
 * pending value.
 */
interface CustomInstructionRepository {

    val pending: StateFlow<String?>

    fun submit(instruction: String)

    fun consume()
}
