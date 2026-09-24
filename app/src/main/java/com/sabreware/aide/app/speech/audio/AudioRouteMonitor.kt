package com.sabreware.aide.app.speech.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * The two ways the audio route changes under a session that is already running.
 *
 * Neither existed anywhere in the tree, and both are audible failures rather than crashes: unplugging
 * headphones mid-reply used to blast the reply out of the speaker, and a Bluetooth headset connecting
 * mid-capture left `AudioRecord` reading from the built-in mic it was opened on.
 *
 * Both flows register their platform callback only while collected and tear it down in `awaitClose`, so
 * nothing here is a long-lived receiver registration — the leak class the rest of this module avoids.
 */
class AudioRouteMonitor(
    private val appContext: Context,
) {
    private val manager: AudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Emits when the output route changed in a way that would push audio to the speaker — the platform's
     * `ACTION_AUDIO_BECOMING_NOISY` contract, which says an app "should" pause. For an assistant reading a
     * reply aloud, "should" is "must": the alternative is broadcasting it to the room.
     */
    val becomingNoisy: Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) trySend(Unit)
            }
        }
        appContext.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        awaitClose { runCatching { appContext.unregisterReceiver(receiver) } }
    }.conflate()

    /**
     * Emits when the set of *input* devices changes — a headset or SCO link arriving or leaving. An open
     * `AudioRecord` does not follow the new route, so a capture in flight has to be restarted to pick it up.
     */
    val inputDevicesChanged: Flow<Unit> = callbackFlow {
        val handler = Handler(Looper.getMainLooper())
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                if (addedDevices?.any { it.isSource } == true) trySend(Unit)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                if (removedDevices?.any { it.isSource } == true) trySend(Unit)
            }
        }
        manager.registerAudioDeviceCallback(callback, handler)
        awaitClose { runCatching { manager.unregisterAudioDeviceCallback(callback) } }
    }.conflate()
}
