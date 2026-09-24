package com.sabreware.aide.app.speech

sealed interface VoiceLoopState {
    data object Idle : VoiceLoopState
    data class Listening(val partial: String, val rmsDb: Float) : VoiceLoopState
    data class Thinking(val assistantBuffer: String) : VoiceLoopState
    data class Speaking(val sentenceInFlight: String, val queuedSentences: Int) : VoiceLoopState
    data class ToolAnnouncing(val toolName: String, val summary: String) : VoiceLoopState
    data class Error(val message: String, val recoverable: Boolean) : VoiceLoopState
}
