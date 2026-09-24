package com.sabreware.aide.desktop.speech.audio

import com.sabreware.aide.core.domain.speech.AudioCapturer
import com.sabreware.aide.core.domain.speech.AudioSession
import com.sabreware.aide.core.domain.speech.MicActivityMonitor
import com.sabreware.aide.core.domain.speech.MicUnavailableException
import com.sabreware.aide.core.domain.util.AideLog
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Desktop microphone source — the Java Sound (`javax.sound.sampled`) peer of `:app`'s AudioRecordCapturer.
 * Emits the SAME contract the Sherpa engines expect: 16 kHz mono float frames in [-1, 1], 512 samples/frame
 * (Silero VAD's hop). Capture starts eagerly in [openSession] and pushes into a replay [SharedFlow] so a late
 * subscriber (ASR after JNI model load) still receives the preroll — mirroring the Android capturer.
 */
class DesktopAudioCapturer(
    private val micActivity: MicActivityMonitor,
    private val ioDispatcher: CoroutineDispatcher,
) : AudioCapturer {

    override fun openSession(scope: CoroutineScope, prerollMs: Long): AudioSession {
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, /* signed = */ true, /* bigEndian = */ false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            throw MicUnavailableException("No 16 kHz mono PCM16 capture line available")
        }
        val line = try {
            (AudioSystem.getLine(info) as TargetDataLine).also {
                it.open(format, FRAME_SAMPLES * BYTES_PER_SAMPLE * 8)
            }
        } catch (t: LineUnavailableException) {
            // Recoverable: another app usually holds the mic.
            throw MicUnavailableException("Microphone in use by another app: ${t.message}")
        }

        val replayFrames = ((prerollMs * SAMPLE_RATE / 1000L) / FRAME_SAMPLES)
            .toInt().coerceAtLeast(1)
        val flow = MutableSharedFlow<FloatArray>(
            replay = replayFrames,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        line.start()
        micActivity.begin()

        val pump = scope.launch(ioDispatcher) {
            val byteBuf = ByteArray(FRAME_SAMPLES * BYTES_PER_SAMPLE)
            try {
                while (isActive) {
                    val read = line.read(byteBuf, 0, byteBuf.size)
                    if (read <= 0) continue
                    val samples = read / BYTES_PER_SAMPLE
                    val floatBuf = FloatArray(samples)
                    var sumSq = 0.0
                    for (i in 0 until samples) {
                        // Little-endian signed PCM16 → float in [-1, 1].
                        val lo = byteBuf[i * 2].toInt() and 0xFF
                        val hi = byteBuf[i * 2 + 1].toInt()
                        val f = ((hi shl 8) or lo) / 32768f
                        floatBuf[i] = f
                        sumSq += f.toDouble() * f
                    }
                    val rms = kotlin.math.sqrt(sumSq / samples).toFloat()
                    val db = if (rms > 1e-6f) 20f * kotlin.math.log10(rms) else -60f
                    micActivity.onRms(db, frameMs = samples * 1000 / SAMPLE_RATE)
                    flow.tryEmit(floatBuf)
                }
            } catch (t: Throwable) {
                AideLog.w(TAG, "capture pump failed", t)
            } finally {
                runCatching { line.stop() }
                runCatching { line.flush() }
                runCatching { line.close() }
                micActivity.end()
            }
        }

        return Session(flow.asSharedFlow(), pump)
    }

    private class Session(
        override val frames: SharedFlow<FloatArray>,
        private val pumpJob: Job,
    ) : AudioSession {
        override fun stop() { pumpJob.cancel() }
    }

    companion object {
        private const val TAG = "DesktopAudioCapturer"
        const val SAMPLE_RATE = 16_000
        // 32 ms @ 16 kHz — matches Silero VAD's expected hop.
        const val FRAME_SAMPLES = 512
        private const val BYTES_PER_SAMPLE = 2
    }
}
