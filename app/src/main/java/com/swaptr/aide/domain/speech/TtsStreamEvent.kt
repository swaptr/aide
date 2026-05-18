package com.swaptr.aide.domain.speech

// PCM is f32 mono [-1,1]; first chunk's sampleRate is authoritative (player rebuilds AudioTrack if it changes).
sealed interface TtsStreamEvent {
    data class AudioChunk(val pcm: FloatArray, val sampleRate: Int) : TtsStreamEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AudioChunk
            return sampleRate == other.sampleRate && pcm.contentEquals(other.pcm)
        }
        override fun hashCode(): Int = 31 * pcm.contentHashCode() + sampleRate
    }
    data class Boundary(val kind: BoundaryKind, val text: String, val offsetChars: Int) : TtsStreamEvent
    data object Completed : TtsStreamEvent
    data class Error(val message: String, val cause: Throwable? = null) : TtsStreamEvent
    enum class BoundaryKind { WORD, SENTENCE, UTTERANCE }
}
