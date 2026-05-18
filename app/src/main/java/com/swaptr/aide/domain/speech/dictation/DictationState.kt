package com.swaptr.aide.domain.speech.dictation

data class DictationState(
    val isDictating: Boolean = false,
    val errorMessage: String? = null,
    val permanentlyDenied: Boolean = false,
) {
    companion object {
        val Idle = DictationState()
    }
}
