package com.sabreware.aide.core.domain.audio

/**
 * Turns one finished audio recording into text.
 *
 * The peer of [AudioSynthesisEngine], and deliberately not
 * [com.sabreware.aide.core.domain.speech.SpeechRecognizerEngine]: that one consumes a live
 * `Flow<FloatArray>` from the microphone and emits partial results a UI revises in place. This one is
 * handed a file that already exists.
 */
interface AudioTranscriptionEngine {
    suspend fun transcribe(
        modelName: String,
        audio: ByteArray,
        mediaType: String,
        options: TranscriptionOptions = TranscriptionOptions(),
    ): Transcript
}

data class TranscriptionOptions(
    /** ISO-639-1. Null lets the vendor detect it, which most charge for. */
    val language: String? = null,
)

/**
 * A transcript, with the timings a caller needs to seek within the recording.
 *
 * [segments] is empty rather than null when a vendor returns none, because "this vendor does not do
 * timings" and "this recording had no speech" are both legitimately empty and a caller treats them the
 * same way — it has no timings either way.
 */
data class Transcript(
    val text: String,
    val segments: List<Segment> = emptyList(),
    val language: String? = null,
    val durationInSeconds: Double? = null,
) {
    data class Segment(val text: String, val startSecond: Double, val endSecond: Double)
}
