package com.sabreware.aide.core.domain.speech

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * The live microphone signal a level meter renders: [level] is a 0..1 envelope, [speaking] the voice
 * activity verdict. Meters must animate on [speaking], not merely on "the mic is open" — otherwise the
 * animation is a lie that plays over silence.
 */
data class MicActivity(
    val listening: Boolean = false,
    val speaking: Boolean = false,
    val level: Float = 0f,
    val rmsDb: Float = SilentDb,
) {
    companion object {
        const val SilentDb = -60f
        val Idle = MicActivity()
    }
}

/**
 * One place every mic source publishes its live signal, and one place every meter reads it.
 *
 * Needed because the app has *three* kinds of source and only one of them is our own capturer:
 * `AudioRecordCapturer`/`DesktopAudioCapturer` push raw frame RMS ([onRms], which runs the built-in
 * [VoiceActivityDetector]); an engine that owns the mic (Android `SpeechRecognizer`) pushes its own
 * callbacks ([onLevel] + [onVoiceActivity]); and Sherpa's Silero VAD overrides the energy verdict with a
 * neural one ([onVoiceActivity]) whenever it is running. Meters just collect [state] and never learn
 * which one is live.
 */
class MicActivityMonitor {

    private val _state = MutableStateFlow(MicActivity.Idle)
    val state: StateFlow<MicActivity> = _state.asStateFlow()

    private val detector = VoiceActivityDetector()

    // Set once a real VAD reports in; from then on the energy detector only supplies the level, so the
    // two verdicts can't fight each other frame by frame.
    @Volatile
    private var externalVad = false

    // Refcounted. Two sources can be open at once — the voice loop's capturer and a recogniser that owns
    // the mic — and an unbalanced pair used to leave the meter wrong in both directions: the first `end()`
    // blanked a still-live meter, and a `begin()` whose `end()` never ran (a cancelled capture) left
    // `listening = true` on for the life of the process.
    private val openSources = MutableStateFlow(0)

    fun begin() {
        if (openSources.updateAndGet { it + 1 } != 1) return
        detector.reset()
        externalVad = false
        _state.value = MicActivity(listening = true)
    }

    fun end() {
        if (openSources.updateAndGet { (it - 1).coerceAtLeast(0) } != 0) return
        _state.value = MicActivity.Idle
    }

    /** A frame of our own capture, [db] in dBFS. Called from the capture thread. */
    fun onRms(db: Float, frameMs: Int) {
        val reading = detector.accept(db, frameMs)
        _state.update {
            it.copy(
                listening = true,
                level = reading.level,
                rmsDb = db,
                speaking = if (externalVad) it.speaking else reading.speaking,
            )
        }
    }

    /**
     * A level reading from an engine that owns the mic, on a dB scale the platform does not document —
     * Android's `onRmsChanged` promises neither a range nor that it fires at all. Runs the same
     * scale-free detector, but for the envelope ONLY: the verdict comes from that engine's own speech
     * callbacks via [onVoiceActivity], which is a better VAD than anything we'd infer from its levels.
     */
    fun onEngineRms(db: Float, frameMs: Int) {
        val reading = detector.accept(db, frameMs)
        _state.update { it.copy(listening = true, level = reading.level) }
    }

    /** An authoritative verdict — Silero, or a recogniser's own speech-boundary callbacks. */
    fun onVoiceActivity(speaking: Boolean) {
        externalVad = true
        _state.update { it.copy(listening = true, speaking = speaking) }
    }
}
