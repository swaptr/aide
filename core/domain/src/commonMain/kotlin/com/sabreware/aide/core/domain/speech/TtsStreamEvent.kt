package com.sabreware.aide.core.domain.speech

// PCM is f32 mono [-1,1]; first chunk's sampleRate is authoritative (player rebuilds AudioTrack if it
// changes). Exactly ONE terminal [End] per synthesize() call, success/failure/cancellation folded into
// its [SpeechStreamOutcome] (B1/E2). The former System-TTS word Boundary was unused and is gone (B2).
sealed interface TtsStreamEvent {
    data class AudioChunk(val pcm: FloatArray, val sampleRate: Int) : TtsStreamEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as AudioChunk
            return sampleRate == other.sampleRate && pcm.contentEquals(other.pcm)
        }
        override fun hashCode(): Int = 31 * pcm.contentHashCode() + sampleRate
    }
    data class End(val outcome: SpeechStreamOutcome) : TtsStreamEvent
}
