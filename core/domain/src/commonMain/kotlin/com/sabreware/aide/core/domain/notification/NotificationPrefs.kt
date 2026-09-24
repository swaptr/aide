package com.sabreware.aide.core.domain.notification

import com.sabreware.aide.core.common.prefs.stringKey

/** Pending assistant-scheduled reminders as a JSON list (see ScheduledNotificationCodec). */
object NotificationPrefs {
    val ScheduledJson = stringKey("scheduled_notifications_json", default = "[]")
}
