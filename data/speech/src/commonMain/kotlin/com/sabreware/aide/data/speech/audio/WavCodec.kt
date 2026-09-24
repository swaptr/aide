package com.sabreware.aide.data.speech.audio

/**
 * 16-bit PCM WAV, both directions, and the raw s16le ↔ float conversion underneath it.
 *
 * Byte order is written and read by hand: WAV is little-endian and every target this module compiles
 * for has to agree on the bytes, which `java.nio` cannot promise from commonMain. The float scale is the
 * app's audio contract — mono samples in [-1, 1] — so the cloud speech adapters can hand a capturer's
 * frames to a transcription endpoint and a synthesis endpoint's answer to the players without either side
 * learning what the other speaks.
 */
object WavCodec {

    /** Decoded audio: mono float samples and the rate the container declared. */
    data class Decoded(val samples: FloatArray, val sampleRate: Int) {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Decoded && sampleRate == other.sampleRate && samples.contentEquals(other.samples))

        override fun hashCode(): Int = 31 * samples.contentHashCode() + sampleRate
    }

    /** A canonical 44-byte header followed by mono s16le samples. */
    fun encode(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataBytes = samples.size * BYTES_PER_SAMPLE
        val out = ByteArray(HEADER_BYTES + dataBytes)
        out.putAscii(0, "RIFF")
        out.putIntLe(RIFF_SIZE_OFFSET, RIFF_HEADER_REMAINDER + dataBytes)
        out.putAscii(RIFF_TYPE_OFFSET, "WAVE")
        out.putAscii(FMT_ID_OFFSET, "fmt ")
        out.putIntLe(FMT_SIZE_OFFSET, FMT_CHUNK_BYTES)
        out.putShortLe(FMT_FORMAT_OFFSET, PCM_FORMAT)
        out.putShortLe(FMT_CHANNELS_OFFSET, MONO)
        out.putIntLe(FMT_RATE_OFFSET, sampleRate)
        out.putIntLe(FMT_BYTE_RATE_OFFSET, sampleRate * BYTES_PER_SAMPLE)
        out.putShortLe(FMT_BLOCK_ALIGN_OFFSET, BYTES_PER_SAMPLE)
        out.putShortLe(FMT_BITS_OFFSET, BITS_PER_SAMPLE)
        out.putAscii(DATA_ID_OFFSET, "data")
        out.putIntLe(DATA_SIZE_OFFSET, dataBytes)
        var offset = HEADER_BYTES
        for (sample in samples) {
            out.putShortLe(offset, floatToPcm16(sample))
            offset += BYTES_PER_SAMPLE
        }
        return out
    }

    /**
     * Parses a PCM WAV. Walks the chunk list rather than assuming the canonical layout, because vendors
     * pad the header with `LIST` metadata; multi-channel input is averaged down to mono.
     *
     * @throws IllegalArgumentException for anything that is not 16-bit PCM — a float or compressed WAV
     *   would decode to noise, and the players make noise rather than an error out of bad samples.
     */
    fun decode(wav: ByteArray): Decoded {
        require(wav.size >= RIFF_TYPE_OFFSET + 4 && wav.ascii(0, 4) == "RIFF" && wav.ascii(RIFF_TYPE_OFFSET, 4) == "WAVE") {
            "Not a WAV container"
        }
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var data: ByteArray? = null
        var offset = FMT_ID_OFFSET
        while (offset + CHUNK_HEADER_BYTES <= wav.size && data == null) {
            val id = wav.ascii(offset, 4)
            val size = wav.intLe(offset + 4)
            val body = offset + CHUNK_HEADER_BYTES
            when (id) {
                "fmt " -> {
                    require(wav.shortLe(body) == PCM_FORMAT) { "Only PCM WAV is supported" }
                    channels = wav.shortLe(body + 2)
                    sampleRate = wav.intLe(body + 4)
                    bits = wav.shortLe(body + FMT_BITS_OFFSET - FMT_FORMAT_OFFSET)
                }
                "data" -> data = wav.copyOfRange(body, minOf(body + size, wav.size))
            }
            // Chunks are word-aligned: an odd-sized one is followed by a pad byte its size does not count.
            offset = body + size + (size and 1)
        }
        require(channels > 0 && sampleRate > 0) { "WAV has no fmt chunk" }
        require(bits == BITS_PER_SAMPLE) { "Only 16-bit PCM WAV is supported (got $bits-bit)" }
        val pcm = requireNotNull(data) { "WAV has no data chunk" }
        val interleaved = pcm16ToFloat(pcm)
        val mono = if (channels == 1) {
            interleaved
        } else {
            FloatArray(interleaved.size / channels) { frame ->
                var sum = 0f
                for (channel in 0 until channels) sum += interleaved[frame * channels + channel]
                sum / channels
            }
        }
        return Decoded(mono, sampleRate)
    }

    /** Raw s16le → floats in [-1, 1]. A trailing odd byte is ignored. */
    fun pcm16ToFloat(bytes: ByteArray): FloatArray = pcm16ToFloat(bytes, 0, bytes.size)

    /**
     * One window of raw s16le → floats, for a caller that hands a long recording to a player in pieces
     * and does not want the whole thing materialised as floats first. [fromByte] is rounded down and
     * [toByte] clamped to the array, so a window that straddles the end is simply shorter.
     */
    fun pcm16ToFloat(bytes: ByteArray, fromByte: Int, toByte: Int): FloatArray {
        val start = fromByte.coerceIn(0, bytes.size)
        val end = toByte.coerceIn(start, bytes.size)
        return FloatArray((end - start) / BYTES_PER_SAMPLE) { i ->
            bytes.shortLe(start + i * BYTES_PER_SAMPLE).toShort() / PCM16_SCALE
        }
    }

    /** Bytes per mono 16-bit sample — what a caller windowing raw PCM by sample count multiplies by. */
    const val PCM16_BYTES_PER_SAMPLE: Int = 2

    private fun floatToPcm16(sample: Float): Int =
        (sample * PCM16_MAX).toInt().coerceIn(PCM16_MIN, PCM16_MAX.toInt())

    private fun ByteArray.putAscii(offset: Int, text: String) {
        text.forEachIndexed { i, c -> this[offset + i] = c.code.toByte() }
    }

    private fun ByteArray.putIntLe(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
        this[offset + 2] = (value shr 16).toByte()
        this[offset + 3] = (value shr 24).toByte()
    }

    private fun ByteArray.putShortLe(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        buildString(length) { for (i in 0 until length) append(this@ascii[offset + i].toInt().toChar()) }

    private fun ByteArray.intLe(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    /** Unsigned 16-bit read; callers that want a signed sample call `.toShort()`. */
    private fun ByteArray.shortLe(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private const val HEADER_BYTES = 44
    private const val CHUNK_HEADER_BYTES = 8
    private const val RIFF_SIZE_OFFSET = 4
    private const val RIFF_HEADER_REMAINDER = 36
    private const val RIFF_TYPE_OFFSET = 8
    private const val FMT_ID_OFFSET = 12
    private const val FMT_SIZE_OFFSET = 16
    private const val FMT_CHUNK_BYTES = 16
    private const val FMT_FORMAT_OFFSET = 20
    private const val FMT_CHANNELS_OFFSET = 22
    private const val FMT_RATE_OFFSET = 24
    private const val FMT_BYTE_RATE_OFFSET = 28
    private const val FMT_BLOCK_ALIGN_OFFSET = 32
    private const val FMT_BITS_OFFSET = 34
    private const val DATA_ID_OFFSET = 36
    private const val DATA_SIZE_OFFSET = 40
    private const val PCM_FORMAT = 1
    private const val MONO = 1
    private const val BYTES_PER_SAMPLE = 2
    private const val BITS_PER_SAMPLE = 16
    private const val PCM16_MAX = 32767f
    private const val PCM16_MIN = -32768
    private const val PCM16_SCALE = 32768f
}
