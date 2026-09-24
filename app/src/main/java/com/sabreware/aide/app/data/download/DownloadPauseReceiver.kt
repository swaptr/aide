package com.sabreware.aide.app.data.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sabreware.aide.core.domain.download.AssetHandle
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

// Notification "Pause" action target; leaves .part intact for resume.
class DownloadPauseReceiver : BroadcastReceiver(), KoinComponent {

    private val downloadController: DownloadController by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PAUSE) return
        val assetId = intent.getStringExtra(EXTRA_ASSET_ID) ?: return
        // Kind + id rebuild the exact AssetHandle, so pause targets the right unique work for ANY
        // asset (model OR speech) — not just the model kind.
        val assetKind = intent.getStringExtra(EXTRA_ASSET_KIND) ?: return
        downloadController.pause(AssetHandle(assetKind, assetId))
    }

    companion object {
        const val ACTION_PAUSE = "com.sabreware.aide.action.PAUSE_DOWNLOAD"
        const val EXTRA_ASSET_ID = "asset_id"
        const val EXTRA_ASSET_KIND = "asset_kind"
    }
}
