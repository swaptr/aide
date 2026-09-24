package com.sabreware.aide.data.speech.cloud

/** How a vendor packages the samples it returns for a given `outputFormat`. */
enum class PcmContainer {
    /** Headerless little-endian 16-bit mono at the rate the vendor documents for the format. */
    Pcm16,

    /** A RIFF/WAVE container; the rate comes from its header. */
    Wav,
}

/**
 * What one vendor returns when asked for [outputFormat] — decided per vendor, never sniffed.
 *
 * A synthesis endpoint's `content-type` for raw PCM is whatever the vendor felt like (`audio/pcm`,
 * `application/octet-stream`, `audio/L16;rate=24000`) and usually carries no sample rate, and a player
 * handed the wrong rate plays chipmunks rather than an error. So the rate is part of the request the
 * caller chose, alongside the format string that produced it.
 */
data class CloudTtsFormat(
    /** The vendor's own format name, sent as the request's output format. */
    val outputFormat: String,
    val sampleRate: Int,
    val container: PcmContainer,
) {
    companion object {
        private const val RATE_24K = 24_000

        /** OpenAI's `/audio/speech`: `pcm` is documented as 24 kHz signed 16-bit mono. */
        val OpenAi: CloudTtsFormat = CloudTtsFormat("pcm", RATE_24K, PcmContainer.Pcm16)

        /** ElevenLabs' `output_format=pcm_24000` — the rate is spelled in the name. */
        val ElevenLabs: CloudTtsFormat = CloudTtsFormat("pcm_24000", RATE_24K, PcmContainer.Pcm16)

        /** Gemini TTS answers `audio/L16;rate=24000`; `pcm` asks for the naked bytes. */
        val Google: CloudTtsFormat = CloudTtsFormat("pcm", RATE_24K, PcmContainer.Pcm16)
    }
}
