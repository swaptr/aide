package com.sabreware.aide.app.speech.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.sabreware.aide.core.domain.speech.TtsStreamEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Streaming PCM playback for synthesized speech. Extracted to an interface so the voice output
 * channel can be unit-tested without a real `AudioTrack`; the impl owns the track + audio focus.
 */
interface AudioPlayer {
    val isPlaying: StateFlow<Boolean>
    suspend fun play(chunks: Flow<TtsStreamEvent.AudioChunk>)
    fun stop()
}

class AudioPlayerImpl(
    private val focusGate: AudioFocusGate,
    private val routes: AudioRouteMonitor,
    private val ioDispatcher: CoroutineDispatcher,
) : AudioPlayer {

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    @Volatile private var track: AudioTrack? = null
    @Volatile private var currentSampleRate: Int = 0
    @Volatile private var stopped: Boolean = false

    override suspend fun play(chunks: Flow<TtsStreamEvent.AudioChunk>) = withContext(ioDispatcher) {
        _isPlaying.value = true
        stopped = false
        // Honour the answer. A denied request means something with a stronger claim owns the output — a
        // call, an alarm — and talking over it is worse than staying quiet.
        if (!focusGate.acquire(AudioFocusGate.Purpose.Speak)) {
            Log.i(TAG, "audio focus denied — not speaking")
            _isPlaying.value = false
            return@withContext
        }
        // Focus loss (an incoming call, a navigation prompt taking over) and the platform's
        // becoming-noisy broadcast (headphones unplugged) both mean: stop talking, now.
        val interruptions = launch {
            launch {
                focusGate.held(AudioFocusGate.Purpose.Speak).collect { held -> if (!held) stop() }
            }
            launch {
                routes.becomingNoisy.collect {
                    Log.i(TAG, "output route became noisy — stopping playback")
                    stop()
                }
            }
        }
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
            interruptions.cancel()
            releaseTrack()
            _isPlaying.value = false
            focusGate.release(AudioFocusGate.Purpose.Speak)
        }
        Unit // pin the withContext result to Unit so it matches AudioPlayer.play(): Unit
    }

    override fun stop() {
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
