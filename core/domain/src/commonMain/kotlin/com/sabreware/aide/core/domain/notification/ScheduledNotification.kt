package com.sabreware.aide.core.domain.notification

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A user reminder the assistant scheduled — fired by AlarmManager, persisted so it survives reboot. */
@Serializable
data class ScheduledNotification(
    val id: String,
    val triggerAtMs: Long,
    val title: String,
    val body: String = "",
)

/** Persists pending reminders so a boot receiver can re-arm them (alarms don't survive reboot). */
interface ScheduledNotificationStore {
    fun observe(): Flow<List<ScheduledNotification>>
    suspend fun list(): List<ScheduledNotification>
    suspend fun add(notification: ScheduledNotification)
    suspend fun remove(id: String)
}

/** (De)serializes the persisted reminder list; a bad blob decodes to empty rather than throwing. */
object ScheduledNotificationCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(ScheduledNotification.serializer())

    fun parse(blob: String): List<ScheduledNotification> =
        runCatching { json.decodeFromString(serializer, blob) }.getOrDefault(emptyList())

    fun encode(list: List<ScheduledNotification>): String = json.encodeToString(serializer, list)
}
