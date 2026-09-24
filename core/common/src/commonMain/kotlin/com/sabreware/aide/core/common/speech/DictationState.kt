package com.sabreware.aide.core.common.speech

data class DictationState(
    val isDictating: Boolean = false,
    val errorMessage: String? = null,
    val permanentlyDenied: Boolean = false,
) {
    companion object {
        val Idle = DictationState()
    }
}
