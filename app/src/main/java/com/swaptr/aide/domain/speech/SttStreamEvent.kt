package com.swaptr.aide.domain.speech

// Partial.text is cumulative (not delta); exactly one Completed per recognize() call.
sealed interface SttStreamEvent {
    data class Partial(val text: String, val isStable: Boolean) : SttStreamEvent
    data class Final(val text: String, val confidence: Float? = null) : SttStreamEvent
    data object Endpoint : SttStreamEvent
    data class Error(val message: String, val cause: Throwable? = null) : SttStreamEvent
    data object Completed : SttStreamEvent
}
