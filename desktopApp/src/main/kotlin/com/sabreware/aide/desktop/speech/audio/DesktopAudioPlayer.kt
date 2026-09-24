package com.sabreware.aide.desktop.speech.audio

import com.sabreware.aide.core.domain.speech.TtsStreamEvent
import com.sabreware.aide.core.domain.util.AideLog
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Streaming PCM playback for synthesized speech — the Java Sound (`javax.sound.sampled`) peer of `:app`'s
 * AudioPlayerImpl. Consumes the f32 mono chunks emitted by [DesktopSherpaTtsEngine] and writes them to a
 * [SourceDataLine], rebuilding the line if a chunk's sample rate changes (first chunk is authoritative).
 * This is the desktop output peer of the on-device TTS engine, ready for a desktop voice-output channel.
 */
class DesktopAudioPlayer(
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    @Volatile private var line: SourceDataLine? = null
    @Volatile private var currentSampleRate: Int = 0
    @Volatile private var stopped: Boolean = false

    suspend fun play(chunks: Flow<TtsStreamEvent.AudioChunk>) = withContext(ioDispatcher) {
        _isPlaying.value = true
        stopped = false
        try {
            chunks.collect { chunk ->
                if (stopped) return@collect
                ensureLine(chunk.sampleRate)
                val bytes = floatToLePcm16(chunk.pcm)
                val current = line ?: return@collect
                var offset = 0
                while (offset < bytes.size && !stopped) {
                    val written = current.write(bytes, offset, bytes.size - offset)
                    if (written <= 0) break
                    offset += written
                }
            }
            if (!stopped) line?.drain()
        } finally {
            releaseLine()
            _isPlaying.value = false
        }
    }

    fun stop() {
        stopped = true
        runCatching { line?.stop() }
        runCatching { line?.flush() }
    }

    private fun ensureLine(sampleRate: Int) {
        if (line != null && currentSampleRate == sampleRate) return
        releaseLine()
        currentSampleRate = sampleRate
        val format = AudioFormat(sampleRate.toFloat(), 16, 1, /* signed = */ true, /* bigEndian = */ false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        line = runCatching {
            (AudioSystem.getLine(info) as SourceDataLine).apply {
                open(format)
                start()
            }
        }.onFailure { AideLog.w(TAG, "SourceDataLine open failed", it) }.getOrNull()
    }

    private fun releaseLine() {
        line?.let {
            runCatching { it.stop() }
            runCatching { it.flush() }
            runCatching { it.close() }
        }
        line = null
        currentSampleRate = 0
    }

    private fun floatToLePcm16(pcm: FloatArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val s = (pcm[i] * 32767f).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    companion object {
        private const val TAG = "DesktopAudioPlayer"
    }
}
