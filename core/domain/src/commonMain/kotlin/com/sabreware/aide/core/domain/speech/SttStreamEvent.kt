package com.sabreware.aide.core.domain.speech

// Partial.text is cumulative (not delta). [Final] is a committed utterance transcript. Exactly ONE
// terminal [End] per recognize() call, with success/failure/cancellation folded into its
// [SpeechStreamOutcome] (B1/E2). The former separate Endpoint boundary was 1:1 with Final and is gone (B2).
sealed interface SttStreamEvent {
    data class Partial(val text: String, val isStable: Boolean) : SttStreamEvent
    data class Final(val text: String, val confidence: Float? = null) : SttStreamEvent
    data class End(val outcome: SpeechStreamOutcome) : SttStreamEvent
}
