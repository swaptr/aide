package com.sabreware.aide.core.domain.speech

// voiceId is opaque/backend-defined: Sherpa = stringified speaker id ("0","1",...);
// System = Voice.getName() from TextToSpeech.getVoices(). pitch: Sherpa Piper ignores.
data class TtsOptions(
    val locale: String = "en-US",
    val voiceId: String? = null,
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
)
