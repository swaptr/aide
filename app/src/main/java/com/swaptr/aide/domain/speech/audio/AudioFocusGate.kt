package com.swaptr.aide.domain.speech.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioFocusGate @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    enum class Purpose { Record, Speak }

    private val manager: AudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val activeRequests = mutableMapOf<Purpose, AudioFocusRequest>()

    @Synchronized
    fun acquire(purpose: Purpose): Boolean {
        if (activeRequests.containsKey(purpose)) return true
        val attrs = AudioAttributes.Builder()
            .setUsage(if (purpose == Purpose.Speak) AudioAttributes.USAGE_ASSISTANT
                      else AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val gain = when (purpose) {
            Purpose.Record -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            Purpose.Speak -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        val result = manager.requestAudioFocus(request)
        return if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            activeRequests[purpose] = request
            true
        } else false
    }

    @Synchronized
    fun release(purpose: Purpose) {
        activeRequests.remove(purpose)?.let { manager.abandonAudioFocusRequest(it) }
    }
}
