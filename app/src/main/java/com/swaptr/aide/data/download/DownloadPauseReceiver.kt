package com.swaptr.aide.data.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

// Notification "Pause" action target; leaves .part intact for resume.
class DownloadPauseReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PauseEntryPoint {
        fun downloadController(): DownloadController
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PAUSE) return
        val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: return
        val controller = EntryPointAccessors
            .fromApplication(context.applicationContext, PauseEntryPoint::class.java)
            .downloadController()
        controller.pause(modelId)
    }

    companion object {
        const val ACTION_PAUSE = "com.swaptr.aide.action.PAUSE_DOWNLOAD"
        const val EXTRA_MODEL_ID = "model_id"
    }
}
