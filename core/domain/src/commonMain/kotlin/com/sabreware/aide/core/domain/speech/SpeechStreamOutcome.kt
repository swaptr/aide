package com.sabreware.aide.core.domain.speech

/**
 * The uniform terminal outcome shared by the speech streams (B1/E2). One terminal — [SttStreamEvent.End]
 * / [TtsStreamEvent.End] — folds normal completion, failure, and cancellation into a single event, so
 * every consumer has ONE "is-terminal / did-it-fail" branch instead of the old per-stream mix (STT:
 * standalone Error then Completed; TTS: Error OR Completed). Mirrors how the chat stream already folds
 * its failure into the terminal `ChatStreamEvent.Completed(StopReason.Error/Cancelled)`.
 */
sealed interface SpeechStreamOutcome {
    data object Done : SpeechStreamOutcome
    data class Error(val message: String, val cause: Throwable? = null) : SpeechStreamOutcome
    data object Cancelled : SpeechStreamOutcome
}
