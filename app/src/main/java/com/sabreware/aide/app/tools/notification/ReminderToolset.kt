package com.sabreware.aide.app.tools.notification

import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.llm.ToolEnvelope
import com.sabreware.aide.core.domain.notification.ScheduledNotification
import com.sabreware.aide.core.domain.notification.ScheduledNotificationStore
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.app.data.notification.NotificationScheduler
import java.util.UUID
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Provides the `ScheduleNotification` tool — a local reminder fired by AlarmManager (see data/notification). */
class ReminderToolset(
    private val store: ScheduledNotificationStore,
    private val scheduler: NotificationScheduler,
) {
    fun asAideTools(): List<AideTool> = listOf(scheduleNotificationTool())

    private fun scheduleNotificationTool(): AideTool = AideTool.Function(
        name = "ScheduleNotification",
        description = "Schedule a local reminder notification to fire at a future time. Use for reminders " +
            "like 'remind me in 10 minutes' or 'remind me at 6pm'. Provide delay_seconds for a relative time, " +
            "OR at_epoch_ms for an absolute time (Unix epoch milliseconds). The reminder survives reboot.",
        parametersSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string"); put("description", "Short reminder title.") }
                putJsonObject("body") { put("type", "string"); put("description", "Optional longer reminder text.") }
                putJsonObject("delay_seconds") {
                    put("type", "integer"); put("description", "Fire this many seconds from now.")
                }
                putJsonObject("at_epoch_ms") {
                    put("type", "integer"); put("description", "Absolute fire time, Unix epoch milliseconds.")
                }
            }
            putJsonArray("required") { add("title") }
        },
        handler = { args ->
            val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (title.isEmpty()) return@Function ToolEnvelope.failure("INVALID_ARGS", "title is required")
            val body = args["body"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

            val now = System.currentTimeMillis()
            val triggerAt = args["at_epoch_ms"]?.jsonPrimitive?.longOrNull
                ?: args["delay_seconds"]?.jsonPrimitive?.longOrNull?.let { now + it * 1000 }
                ?: return@Function ToolEnvelope.failure("INVALID_ARGS", "provide delay_seconds or at_epoch_ms")
            if (triggerAt <= now) {
                return@Function ToolEnvelope.failure("INVALID_ARGS", "the reminder time must be in the future")
            }

            val reminder = ScheduledNotification(
                id = UUID.randomUUID().toString(),
                triggerAtMs = triggerAt,
                title = title,
                body = body,
            )
            store.add(reminder)
            scheduler.schedule(reminder)
            // The title is whatever the user asked to be reminded of — the id and the time identify the
            // reminder for debugging just as well.
            AideLog.i("Reminder", "scheduled ${reminder.id} at $triggerAt")
            ToolEnvelope.success {
                put("id", reminder.id)
                put("fires_at_epoch_ms", triggerAt)
            }
        },
        surfaces = setOf(Surface.CHAT, Surface.VOICE),
        errorCodes = setOf("INVALID_ARGS"),
    )
}
