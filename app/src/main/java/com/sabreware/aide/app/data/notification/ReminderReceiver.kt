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

/** Fired by AlarmManager at the scheduled time: posts the reminder + drops it from the persisted list. */
class ReminderReceiver : BroadcastReceiver(), KoinComponent {

    private val store: ScheduledNotificationStore by inject()

    // Short-lived scope for the goAsync() work below (the receiver keeps the process alive until finish()).
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Reminders.ACTION_FIRE) return
        val id = intent.getStringExtra(Reminders.EXTRA_ID) ?: return
        val title = intent.getStringExtra(Reminders.EXTRA_TITLE) ?: "Reminder"
        val body = intent.getStringExtra(Reminders.EXTRA_BODY).orEmpty()

        Reminders.post(context, id, title, body)

        val pending = goAsync()
        scope.launch {
            try {
                store.remove(id)
            } finally {
                pending.finish()
            }
        }
    }
}
