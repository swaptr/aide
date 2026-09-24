package com.sabreware.aide.data.notification

import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.domain.notification.NotificationPrefs
import com.sabreware.aide.core.domain.notification.ScheduledNotification
import com.sabreware.aide.core.domain.notification.ScheduledNotificationCodec
import com.sabreware.aide.core.domain.notification.ScheduledNotificationStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class ScheduledNotificationStoreImpl(
    private val prefs: PreferenceStore,
) : ScheduledNotificationStore {

    override fun observe(): Flow<List<ScheduledNotification>> =
        prefs.flow(NotificationPrefs.ScheduledJson).map { ScheduledNotificationCodec.parse(it) }

    override suspend fun list(): List<ScheduledNotification> =
        ScheduledNotificationCodec.parse(prefs.flow(NotificationPrefs.ScheduledJson).first())

    override suspend fun add(notification: ScheduledNotification) {
        val next = list().filterNot { it.id == notification.id } + notification
        prefs.set(NotificationPrefs.ScheduledJson, ScheduledNotificationCodec.encode(next))
    }

    override suspend fun remove(id: String) {
        prefs.set(NotificationPrefs.ScheduledJson, 
            ScheduledNotificationCodec.encode(list().filterNot { it.id == id }),
        )
    }
}
