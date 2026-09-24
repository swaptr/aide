package com.sabreware.aide.app.speech.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Audio focus, requested **and honoured**.
 *
 * Asking for focus is only half the contract: the system answers by calling back, and an app that ignores
 * the callback keeps recording through an incoming call and keeps talking over a navigation prompt. The
 * listener here used to be `{ }`, so every loss was dropped on the floor and both call sites discarded the
 * grant/deny result too — a *denied* request went straight on to record silence.
 *
 * [held] is the signal consumers act on: false the moment focus is lost (transiently or permanently), true
 * again on regain. Capture ends on loss (a microphone that stays open during a call is both wrong and
 * usually muted by the platform anyway); playback stops.
 */
class AudioFocusGate(
    private val appContext: Context,
) {
    enum class Purpose { Record, Speak }

    private val manager: AudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private class Holding(
        val request: AudioFocusRequest,
        val held: MutableStateFlow<Boolean>,
    )

    private val active = mutableMapOf<Purpose, Holding>()

    // Survives release so a late collector still reads a sane value rather than crashing on a missing key.
    private val heldFlows: Map<Purpose, MutableStateFlow<Boolean>> =
        Purpose.entries.associateWith { MutableStateFlow(false) }

    /**
     * Whether we currently hold usable focus for [purpose]. Flips false on `AUDIOFOCUS_LOSS` and
     * `AUDIOFOCUS_LOSS_TRANSIENT`, and back to true on `AUDIOFOCUS_GAIN`. `LOSS_TRANSIENT_CAN_DUCK` does
     * NOT flip it — ducking is the system's job and interrupting speech for it would be worse than quiet.
     */
    fun held(purpose: Purpose): StateFlow<Boolean> = heldFlows.getValue(purpose).asStateFlow()

    /** @return true if focus was granted. Callers must not proceed to touch the audio device on false. */
    @Synchronized
    fun acquire(purpose: Purpose): Boolean {
        if (active.containsKey(purpose)) return true
        val attrs = AudioAttributes.Builder()
            .setUsage(
                if (purpose == Purpose.Speak) {
                    AudioAttributes.USAGE_ASSISTANT
                } else {
                    AudioAttributes.USAGE_VOICE_COMMUNICATION
                },
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val gain = when (purpose) {
            Purpose.Record -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            Purpose.Speak -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        val flow = heldFlows.getValue(purpose)
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change -> onFocusChange(purpose, change) }
            .build()
        val result = manager.requestAudioFocus(request)
        return if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            active[purpose] = Holding(request, flow)
            flow.value = true
            true
        } else {
            Log.w(TAG, "audio focus denied for $purpose (result=$result)")
            flow.value = false
            false
        }
    }

    @Synchronized
    fun release(purpose: Purpose) {
        active.remove(purpose)?.let { manager.abandonAudioFocusRequest(it.request) }
        heldFlows.getValue(purpose).value = false
    }

    private fun onFocusChange(purpose: Purpose, change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> heldFlows.getValue(purpose).value = true

            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> {
                Log.i(TAG, "audio focus lost for $purpose (change=$change)")
                heldFlows.getValue(purpose).value = false
            }

            // Ducking: the platform lowers our volume for us. Nothing to do — and stopping would be worse.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
        }
    }

    private companion object {
        const val TAG = "AudioFocusGate"
    }
}
