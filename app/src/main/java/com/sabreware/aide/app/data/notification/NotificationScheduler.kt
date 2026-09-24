package com.sabreware.aide.app.data.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.sabreware.aide.core.domain.notification.ScheduledNotification

/** Arms/cancels AlarmManager alarms that fire [ReminderReceiver]. Exact when allowed, else inexact. */
class NotificationScheduler(
    private val context: Context,
) {
    fun schedule(notification: ScheduledNotification) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(notification.id, notification.title, notification.body)
        // Android 12+: exact alarms need a grant; gracefully fall back to inexact (Doze may delay slightly).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, notification.triggerAtMs, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, notification.triggerAtMs, pi)
        }
    }

    fun cancel(id: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingIntent(id, "", ""))
    }

    private fun pendingIntent(id: String, title: String, body: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = Reminders.ACTION_FIRE
            putExtra(Reminders.EXTRA_ID, id)
            putExtra(Reminders.EXTRA_TITLE, title)
            putExtra(Reminders.EXTRA_BODY, body)
        }
        // requestCode = id.hashCode() so cancel() matches the armed alarm (extras don't affect matching).
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
