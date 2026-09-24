package com.sabreware.aide.core.domain.speech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * Microphone audio source. The implementation
 * ([com.sabreware.aide.app.speech.audio.AudioRecordCapturer]) is AudioRecord-backed and bound via Hilt;
 * domain/use-cases depend only on this port.
 *
 * Level metering is NOT here: implementations publish frame RMS to [MicActivityMonitor], which is also
 * where the sources that bypass this port (a recogniser owning the mic) publish theirs.
 */
interface AudioCapturer {
    /** Open a capture session; [prerollMs] of buffered audio precedes the first emitted frame. */
    fun openSession(scope: CoroutineScope, prerollMs: Long = DEFAULT_PREROLL_MS): AudioSession

    companion object {
        const val DEFAULT_PREROLL_MS = 500L
    }
}

/** A live mic session emitting mono 16 kHz frames until [stop]. */
interface AudioSession {
    val frames: Flow<FloatArray>
    fun stop()
}
