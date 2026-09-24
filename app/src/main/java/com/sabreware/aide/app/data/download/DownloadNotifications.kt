package com.sabreware.aide.app.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.sabreware.aide.app.R
import java.util.UUID

object DownloadNotifications {
    const val CHANNEL_ID = "asset_downloads"
    private const val CHANNEL_NAME = "Asset downloads"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Background downloads of on-device assets"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    fun foregroundInfo(
        context: Context,
        notificationId: Int,
        workId: UUID,
        title: String,
        text: String,
        progress: Int,
        total: Int,
        indeterminate: Boolean,
        pauseAssetId: String? = null,
        pauseAssetKind: String? = null,
    ): ForegroundInfo {
        ensureChannel(context)

        val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(workId)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, progress, indeterminate)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(com.sabreware.aide.platform.android.R.drawable.ic_lc_x, "Cancel", cancelIntent)

        // Both id AND kind are required to rebuild the AssetHandle on pause; without the kind the
        // receiver can't target the right unique work, so no pause action is offered.
        if (pauseAssetId != null && pauseAssetKind != null) {
            val pauseIntent = Intent(context, DownloadPauseReceiver::class.java).apply {
                action = DownloadPauseReceiver.ACTION_PAUSE
                putExtra(DownloadPauseReceiver.EXTRA_ASSET_ID, pauseAssetId)
                putExtra(DownloadPauseReceiver.EXTRA_ASSET_KIND, pauseAssetKind)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pausePending = PendingIntent.getBroadcast(context, pauseAssetId.hashCode(), pauseIntent, flags)
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePending)
        }

        val notification = builder.build()
        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }
}
