package com.swaptr.aide.domain.speech.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.swaptr.aide.domain.speech.TtsStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlayer @Inject constructor(
    private val focusGate: AudioFocusGate,
) {

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    @Volatile private var track: AudioTrack? = null
    @Volatile private var currentSampleRate: Int = 0
    @Volatile private var stopped: Boolean = false

    suspend fun play(chunks: Flow<TtsStreamEvent.AudioChunk>) = withContext(Dispatchers.IO) {
        _isPlaying.value = true
        stopped = false
        focusGate.acquire(AudioFocusGate.Purpose.Speak)
        try {
            chunks.collect { chunk ->
                if (stopped) return@collect
                ensureTrack(chunk.sampleRate)
                val short = floatToShort(chunk.pcm)
                val current = track ?: return@collect
                var offset = 0
                while (offset < short.size && !stopped) {
                    val written = current.write(short, offset, short.size - offset)
                    if (written <= 0) break
                    offset += written
                }
            }
            track?.let { it.flush(); runCatching { it.stop() } }
        } finally {
            releaseTrack()
            _isPlaying.value = false
            focusGate.release(AudioFocusGate.Purpose.Speak)
        }
    }

    fun stop() {
        stopped = true
        runCatching { track?.pause() }
        runCatching { track?.flush() }
    }

    private fun ensureTrack(sampleRate: Int) {
        if (track != null && currentSampleRate == sampleRate) return
        releaseTrack()
        currentSampleRate = sampleRate
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufBytes = maxOf(minBuf, sampleRate / 4 * 2)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { runCatching { it.play() }.onFailure { e -> Log.w(TAG, "AudioTrack.play()", e) } }
    }

    private fun releaseTrack() {
        track?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
        currentSampleRate = 0
    }

    private fun floatToShort(pcm: FloatArray): ShortArray {
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            val s = (pcm[i] * 32767f).toInt().coerceIn(-32768, 32767)
            out[i] = s.toShort()
        }
        return out
    }

    companion object {
        private const val TAG = "AudioPlayer"
    }
}
