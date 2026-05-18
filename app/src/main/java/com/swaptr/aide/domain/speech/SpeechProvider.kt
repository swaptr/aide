package com.swaptr.aide.domain.speech

interface SpeechProvider {
    val id: SpeechProviderId
    val stt: SpeechRecognizerEngine?
    val tts: SpeechSynthesizerEngine?
    val vad: VadEngine?

    suspend fun availability(): SpeechAvailability
}
