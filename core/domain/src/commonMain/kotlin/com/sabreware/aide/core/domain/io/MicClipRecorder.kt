package com.sabreware.aide.core.domain.io

import kotlinx.coroutines.flow.StateFlow

/**
 * Audio-as-model-input producer (Layer D): records a short microphone clip the user can attach to a
 * chat turn as an [com.sabreware.aide.core.domain.chat.AidePart.AudioFile], for models whose
 * `capabilities.audioIn` is true (e.g. Gemma-3n). Tap-to-stop, so it's a stateful start/stop recorder
 * rather than the streaming [VoiceInputChannel] (which transcribes). 16 kHz mono — the app's audio
 * currency. Single-active: [start] is a no-op while already recording.
 */
interface MicClipRecorder {
    val isRecording: StateFlow<Boolean>

    /**
     * Why the last attempt failed, or null. A recorder that cannot open the mic — another app holds it —
     * used to log and go quiet, leaving the UI saying "not recording" with no explanation and no way to
     * tell that from "the user has not started yet". Cleared on the next [start].
     */
    val error: StateFlow<String?>

    /** Begin capturing. No-op if already recording. Needs RECORD_AUDIO — gate before calling. */
    fun start()

    /** Stop and finalize; returns the WAV file path, or null if nothing usable was captured. */
    suspend fun stop(): String?

    /** Abort without producing a file. */
    fun cancel()
}
