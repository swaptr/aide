package com.sabreware.aide.app.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.sabreware.aide.app.R

/** Reminder notification channel + the broadcast contract shared by the scheduler and receivers. */
object Reminders {
    const val CHANNEL_ID = "reminders"
    private const val CHANNEL_NAME = "Reminders"

    const val ACTION_FIRE = "com.sabreware.aide.REMINDER_FIRE"
    const val EXTRA_ID = "reminder_id"
    const val EXTRA_TITLE = "reminder_title"
    const val EXTRA_BODY = "reminder_body"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Assistant-scheduled reminders"
            },
        )
    }

    /** Posts the reminder now. No-op (system-side) if POST_NOTIFICATIONS isn't granted. */
    fun post(context: Context, id: String, title: String, body: String) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_lc_clock)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(id.hashCode(), notification)
    }
}
