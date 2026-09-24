package com.sabreware.aide.core.domain.audio

/**
 * Turns text into one finished audio recording.
 *
 * Transport-free: no HTTP types, no vendor fields. [voice] and [outputFormat] stay opaque strings for the
 * same reason image size does — a vendor that adds a voice must not require a code change here, and the
 * set of voices is the vendor's to know.
 */
interface AudioSynthesisEngine {
    suspend fun synthesize(
        modelName: String,
        text: String,
        options: AudioSynthesisOptions = AudioSynthesisOptions(),
    ): SynthesizedAudio
}

data class AudioSynthesisOptions(
    val voice: String? = null,
    /** Container the vendor should return, e.g. `mp3`. Null takes the vendor's default. */
    val outputFormat: String? = null,
    /** Free-text delivery direction, where the vendor accepts one. */
    val instructions: String? = null,
    val speed: Double? = null,
    val language: String? = null,
)

/**
 * A finished recording, with the media type needed to play or store it.
 *
 * The media type is not optional and not inferred from an extension: the same vendor returns MP3 or PCM
 * depending on what was asked for, and a player handed the wrong type produces noise rather than an error.
 */
data class SynthesizedAudio(val bytes: ByteArray, val mediaType: String) {
    // ByteArray equality is identity by default, which would make two equal recordings compare unequal.
    override fun equals(other: Any?): Boolean = this === other ||
        (other is SynthesizedAudio && mediaType == other.mediaType && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + mediaType.hashCode()
}
