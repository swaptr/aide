package com.sabreware.aide.data.speech.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The container the cloud speech adapters speak in both directions. A header field off by one is a
 * transcription of noise on one side and a chipmunk voice on the other, so the bytes are pinned, not
 * just the round trip.
 */
class WavCodecTest {

    private val samples = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f)

    @Test
    fun `encode writes the canonical 44-byte header`() {
        val wav = WavCodec.encode(samples, 16_000)

        assertEquals(44 + samples.size * 2, wav.size)
        assertEquals("RIFF", wav.ascii(0, 4))
        assertEquals("WAVE", wav.ascii(8, 4))
        assertEquals("fmt ", wav.ascii(12, 4))
        assertEquals(16, wav.intLe(16))
        assertEquals(1, wav.shortLe(20), "PCM format tag")
        assertEquals(1, wav.shortLe(22), "mono")
        assertEquals(16_000, wav.intLe(24))
        assertEquals(32_000, wav.intLe(28), "byte rate")
        assertEquals(2, wav.shortLe(32), "block align")
        assertEquals(16, wav.shortLe(34), "bits per sample")
        assertEquals("data", wav.ascii(36, 4))
        assertEquals(samples.size * 2, wav.intLe(40))
        assertEquals(36 + samples.size * 2, wav.intLe(4))
    }

    @Test
    fun `encode then decode round-trips samples and rate`() {
        val decoded = WavCodec.decode(WavCodec.encode(samples, 24_000))

        assertEquals(24_000, decoded.sampleRate)
        assertEquals(samples.size, decoded.samples.size)
        samples.zip(decoded.samples.toList()).forEach { (expected, actual) ->
            assertTrue(kotlin.math.abs(expected - actual) < 1e-4f, "$expected != $actual")
        }
    }

    @Test
    fun `pcm16 extremes map to the float scale`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0x7F, 0x00, 0x80.toByte(), 0x00, 0x00)

        val floats = WavCodec.pcm16ToFloat(bytes)

        assertEquals(3, floats.size)
        assertTrue(floats[0] > 0.999f, "0x7FFF is full scale")
        assertEquals(-1f, floats[1], "0x8000 is negative full scale")
        assertEquals(0f, floats[2])
    }

    @Test
    fun `a trailing odd byte is ignored`() {
        assertEquals(1, WavCodec.pcm16ToFloat(byteArrayOf(0, 0, 1)).size)
    }

    @Test
    fun `decode walks past a metadata chunk and downmixes stereo`() {
        // Hand-built: fmt (stereo, 8 kHz) → LIST (3 bytes + pad) → data with two frames of (L, R).
        val left = 0x4000 // 0.5
        val right = 0x0000
        val wav = ByteArray(12 + 24 + 8 + 4 + 8 + 8).also { b ->
            b.putAscii(0, "RIFF"); b.putIntLe(4, b.size - 8); b.putAscii(8, "WAVE")
            b.putAscii(12, "fmt "); b.putIntLe(16, 16); b.putShortLe(20, 1); b.putShortLe(22, 2)
            b.putIntLe(24, 8_000); b.putIntLe(28, 32_000); b.putShortLe(32, 4); b.putShortLe(34, 16)
            b.putAscii(36, "LIST"); b.putIntLe(40, 3); b[44] = 1; b[45] = 2; b[46] = 3 // b[47] pad
            b.putAscii(48, "data"); b.putIntLe(52, 8)
            b.putShortLe(56, left); b.putShortLe(58, right); b.putShortLe(60, left); b.putShortLe(62, right)
        }

        val decoded = WavCodec.decode(wav)

        assertEquals(8_000, decoded.sampleRate)
        assertContentEquals(floatArrayOf(0.25f, 0.25f), decoded.samples)
    }

    @Test
    fun `decode refuses what is not 16-bit PCM`() {
        assertFailsWith<IllegalArgumentException> { WavCodec.decode(ByteArray(12)) }
        val float32 = WavCodec.encode(samples, 8_000).also { it.putShortLe(20, 3) }
        assertFailsWith<IllegalArgumentException> { WavCodec.decode(float32) }
    }

    private fun ByteArray.ascii(offset: Int, length: Int) =
        String(CharArray(length) { this[offset + it].toInt().toChar() })

    private fun ByteArray.intLe(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or ((this[offset + 3].toInt() and 0xff) shl 24)

    private fun ByteArray.shortLe(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.putAscii(offset: Int, text: String) {
        text.forEachIndexed { i, c -> this[offset + i] = c.code.toByte() }
    }

    private fun ByteArray.putIntLe(offset: Int, value: Int) {
        for (i in 0 until 4) this[offset + i] = (value shr (8 * i)).toByte()
    }

    private fun ByteArray.putShortLe(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
    }
}
