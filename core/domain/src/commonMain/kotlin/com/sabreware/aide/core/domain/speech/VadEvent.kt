package com.sabreware.aide.core.domain.speech

sealed interface VadEvent {
    data object SpeechStart : VadEvent
    data object SpeechContinues : VadEvent
    data object SpeechEnd : VadEvent
    data object SilenceOnly : VadEvent
}
