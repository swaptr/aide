package com.swaptr.aide.domain.speech.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.swaptr.aide.domain.speech.MicUnavailableException
import com.swaptr.aide.domain.speech.MissingMicPermissionException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// Capture starts eagerly inside openSession() — the SharedFlow has replay so a late
// subscriber (ASR after JNI model load) still gets the preroll frames.
@Singleton
class AudioCapturer @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val focusGate: AudioFocusGate,
) {
    // Volatile because the capture pump and UI thread race for this.
    @Volatile
    var lastRmsDb: Float = -60f
        private set

    @SuppressLint("MissingPermission")
    fun openSession(scope: CoroutineScope, prerollMs: Long = DEFAULT_PREROLL_MS): Session {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            throw MissingMicPermissionException()
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufBytes = maxOf(minBuf, FRAME_SAMPLES * BYTES_PER_SAMPLE * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufBytes,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            // Recoverable: usually another foreground app (Recorder, Phone, Meet) holds the mic.
            throw MicUnavailableException("AudioRecord init failed (state=${record.state})")
        }

        val replayFrames = ((prerollMs * SAMPLE_RATE / 1000L) / FRAME_SAMPLES)
            .toInt().coerceAtLeast(1)
        val flow = MutableSharedFlow<FloatArray>(
            replay = replayFrames,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        focusGate.acquire(AudioFocusGate.Purpose.Record)
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            // startRecording() returns void; we only know it failed if the state never transitioned.
            runCatching { record.release() }
            focusGate.release(AudioFocusGate.Purpose.Record)
            throw MicUnavailableException(
                "Microphone in use by another app (recordingState=${record.recordingState})",
            )
        }

        val pump = scope.launch(Dispatchers.IO) {
            val shortBuf = ShortArray(FRAME_SAMPLES)
            try {
                while (isActive) {
                    val read = record.read(shortBuf, 0, shortBuf.size)
                    if (read <= 0) continue
                    val floatBuf = FloatArray(read)
                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val f = shortBuf[i] / 32768f
                        floatBuf[i] = f
                        sumSq += f * f
                    }
                    val rms = kotlin.math.sqrt(sumSq / read).toFloat()
                    lastRmsDb = if (rms > 1e-6f) 20f * kotlin.math.log10(rms) else -60f
                    flow.tryEmit(floatBuf)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "AudioRecord pump failed", t)
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                focusGate.release(AudioFocusGate.Purpose.Record)
                lastRmsDb = -60f
            }
        }

        return Session(flow.asSharedFlow(), pump)
    }

    class Session internal constructor(
        val frames: SharedFlow<FloatArray>,
        private val pumpJob: Job,
    ) {
        fun stop() { pumpJob.cancel() }
    }

    companion object {
        private const val TAG = "AudioCapturer"
        const val SAMPLE_RATE = 16_000
        // 32 ms @ 16 kHz — matches Silero VAD's expected hop.
        const val FRAME_SAMPLES = 512
        private const val BYTES_PER_SAMPLE = 2
        // 500 ms covers Sherpa STT + VAD JNI-init latency for late subscribers.
        const val DEFAULT_PREROLL_MS = 500L
    }
}
