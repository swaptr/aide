package com.sabreware.aide.app.speech.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.sabreware.aide.core.domain.permission.AppPermission
import com.sabreware.aide.core.domain.permission.RuntimePermissionGate
import com.sabreware.aide.core.domain.speech.AudioCapturer
import com.sabreware.aide.core.domain.speech.AudioSession
import com.sabreware.aide.core.domain.speech.MicActivityMonitor
import com.sabreware.aide.core.domain.speech.MicUnavailableException
import com.sabreware.aide.core.domain.speech.MissingMicPermissionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Capture starts eagerly inside openSession() — the SharedFlow has replay so a late
// subscriber (ASR after JNI model load) still gets the preroll frames.
class AudioRecordCapturer(
    private val focusGate: AudioFocusGate,
    private val routes: AudioRouteMonitor,
    private val gate: RuntimePermissionGate,
    private val micActivity: MicActivityMonitor,
    private val ioDispatcher: CoroutineDispatcher,
) : AudioCapturer {

    // Lint can't see through the gate to the underlying RECORD_AUDIO check, so suppress.
    @SuppressLint("MissingPermission")
    override fun openSession(scope: CoroutineScope, prerollMs: Long): AudioSession {
        if (!gate.isGranted(AppPermission.MICROPHONE)) throw MissingMicPermissionException()

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

        // The return value is the point: a denied request means something else owns the mic exclusively
        // (a call, another recorder). Recording anyway produced a silent stream and a recogniser that
        // heard nothing, reported as a generic failure.
        if (!focusGate.acquire(AudioFocusGate.Purpose.Record)) {
            runCatching { record.release() }
            throw MicUnavailableException("Another app is using the microphone.")
        }
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            // startRecording() returns void; we only know it failed if the state never transitioned.
            runCatching { record.release() }
            focusGate.release(AudioFocusGate.Purpose.Record)
            throw MicUnavailableException(
                "Microphone in use by another app (recordingState=${record.recordingState})",
            )
        }

        micActivity.begin()
        val pump = scope.launch(ioDispatcher) {
            val self = coroutineContext.job
            // Focus loss and route changes both end the capture: a mic held through an incoming call is
            // wrong, and an AudioRecord does not follow a headset that arrives after it was opened, so the
            // only honest response is to end this session and let the caller open a new one.
            //
            // These never complete on their own, so they are cancelled in the `finally` below — a watcher
            // left running would keep this coroutine alive after the read loop ended, and the teardown
            // hanging off completion would never fire.
            val watchers = launch {
                launch {
                    focusGate.held(AudioFocusGate.Purpose.Record).collect { held ->
                        if (!held) {
                            Log.i(TAG, "audio focus lost — ending capture")
                            self.cancel()
                        }
                    }
                }
                launch {
                    routes.inputDevicesChanged.collect {
                        Log.i(TAG, "input route changed — ending capture so it can reopen on the new device")
                        self.cancel()
                    }
                }
            }

            val shortBuf = ShortArray(FRAME_SAMPLES)
            var emptyReads = 0
            try {
                while (isActive) {
                    val read = record.read(shortBuf, 0, shortBuf.size)
                    if (read < 0) {
                        // NEGATIVE IS AN ERROR CODE, not "no data yet". `continue` here meant that once the
                        // audio HAL restarted — routine on a route change or an OEM audio crash — a
                        // permanent ERROR_DEAD_OBJECT (-6) spun this loop on an IO thread at 100% of a core
                        // for the life of the session, emitting no frames while the recogniser heard silence.
                        Log.w(TAG, "AudioRecord.read failed (code=$read) — ending capture")
                        break
                    }
                    if (read == 0) {
                        // Should not happen for a blocking read, but back off rather than spin if it does.
                        if (++emptyReads > MAX_EMPTY_READS) {
                            Log.w(TAG, "AudioRecord returned no data $emptyReads times — ending capture")
                            break
                        }
                        delay(EMPTY_READ_BACKOFF_MS)
                        continue
                    }
                    emptyReads = 0
                    val floatBuf = FloatArray(read)
                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val f = shortBuf[i] / 32768f
                        floatBuf[i] = f
                        sumSq += f * f
                    }
                    val rms = kotlin.math.sqrt(sumSq / read).toFloat()
                    val db = if (rms > 1e-6f) 20f * kotlin.math.log10(rms) else -60f
                    micActivity.onRms(db, frameMs = read * 1000 / SAMPLE_RATE)
                    flow.tryEmit(floatBuf)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "AudioRecord pump failed", t)
            } finally {
                watchers.cancel()
            }
        }

        // Teardown hangs off completion, NOT off the body's `finally`.
        //
        // The device is acquired before `launch` so that a busy microphone can still be reported as a thrown
        // MicUnavailableException — but that left a hole: if the scope was already cancelled when `launch`
        // ran, the body never executed, so the `finally` never ran either and the AudioRecord was never
        // released, focus never abandoned and the privacy indicator stuck on until the process died.
        // `invokeOnCompletion` runs exactly once for every terminal state, including "cancelled before it
        // started".
        pump.invokeOnCompletion {
            runCatching { record.stop() }
            runCatching { record.release() }
            focusGate.release(AudioFocusGate.Purpose.Record)
            micActivity.end()
        }

        return Session(flow.asSharedFlow(), pump)
    }

    class Session internal constructor(
        override val frames: SharedFlow<FloatArray>,
        private val pumpJob: Job,
    ) : AudioSession {
        override fun stop() { pumpJob.cancel() }
    }

    companion object {
        private const val TAG = "AudioRecordCapturer"
        const val SAMPLE_RATE = 16_000
        // 32 ms @ 16 kHz — matches Silero VAD's expected hop.
        const val FRAME_SAMPLES = 512
        private const val BYTES_PER_SAMPLE = 2
        // A blocking read returning zero is anomalous; tolerate a few frames' worth, then give up.
        private const val EMPTY_READ_BACKOFF_MS = 10L
        private const val MAX_EMPTY_READS = 50
    }
}
