package com.sabreware.aide.app.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sabreware.aide.core.domain.notification.ScheduledNotificationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Alarms don't survive reboot — re-arm every still-future reminder, prune the past ones. */
class ReminderBootReceiver : BroadcastReceiver(), KoinComponent {

    private val store: ScheduledNotificationStore by inject()

    private val scheduler: NotificationScheduler by inject()

    // Short-lived scope for the goAsync() re-arm work below.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                store.list().forEach { reminder ->
                    if (reminder.triggerAtMs > now) scheduler.schedule(reminder) else store.remove(reminder.id)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
