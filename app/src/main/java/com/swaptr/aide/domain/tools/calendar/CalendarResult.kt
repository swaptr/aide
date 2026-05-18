package com.swaptr.aide.domain.tools.calendar

import com.swaptr.aide.domain.llm.ToolEnvelope
import com.swaptr.aide.domain.llm.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

sealed class CalendarResult : ToolResult {

    data class CalendarInfo(
        val id: Long,
        val displayName: String,
        val accountName: String,
        val accountType: String,
        val isPrimary: Boolean,
        val canWrite: Boolean,
        val color: Int,
    )

    data class Attendee(val name: String?, val email: String, val status: String?)
    data class Reminder(val minutesBefore: Int, val method: String)

    data class EventInfo(
        val id: Long,
        val calendarId: Long,
        val title: String,
        val location: String?,
        val description: String?,
        val startMillis: Long,
        val endMillis: Long,
        val allDay: Boolean,
        val timezone: String?,
        val attendees: List<Attendee>?,
        val reminders: List<Reminder>?,
    )

    data class Calendars(val calendars: List<CalendarInfo>) : CalendarResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("calendars", buildJsonArray {
                calendars.forEach { c ->
                    add(buildJsonObject {
                        put("id", JsonPrimitive(c.id))
                        put("displayName", JsonPrimitive(c.displayName))
                        put("accountName", JsonPrimitive(c.accountName))
                        put("accountType", JsonPrimitive(c.accountType))
                        put("isPrimary", JsonPrimitive(c.isPrimary))
                        put("canWrite", JsonPrimitive(c.canWrite))
                        put("color", JsonPrimitive(c.color))
                    })
                }
            })
        }
    }

    data class Events(val events: List<EventInfo>, val truncated: Boolean) : CalendarResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("events", buildJsonArray {
                events.forEach { e -> add(e.toJsonObject()) }
            })
            put("truncated", JsonPrimitive(truncated))
        }
    }

    data class Launched(
        val action: String,
        val verified: Boolean = false,
        val reason: String = "verifier_not_implemented",
        val eventId: Long? = null,
    ) : CalendarResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("action", JsonPrimitive(action))
            put("intent_dispatched", JsonPrimitive(true))
            put("verified", JsonPrimitive(verified))
            put("reason", JsonPrimitive(reason))
            if (eventId != null) put("event_id", JsonPrimitive(eventId))
        }
    }

    data class Deleted(val eventId: Long, val deleted: Boolean) : CalendarResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("event_id", JsonPrimitive(eventId))
            put("deleted", JsonPrimitive(deleted))
        }
    }

    data class Err(val code: String, val message: String) : CalendarResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.failure(code, message)
    }
}

private fun CalendarResult.EventInfo.toJsonObject(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("calendar_id", JsonPrimitive(calendarId))
    put("title", JsonPrimitive(title))
    if (location != null) put("location", JsonPrimitive(location))
    if (description != null) put("description", JsonPrimitive(description))
    put("start_millis", JsonPrimitive(startMillis))
    put("end_millis", JsonPrimitive(endMillis))
    put("all_day", JsonPrimitive(allDay))
    if (timezone != null) put("timezone", JsonPrimitive(timezone))
    val a = attendees
    if (a != null) put("attendees", buildJsonArray {
        a.forEach { att ->
            add(buildJsonObject {
                if (att.name != null) put("name", JsonPrimitive(att.name))
                put("email", JsonPrimitive(att.email))
                if (att.status != null) put("status", JsonPrimitive(att.status))
            })
        }
    })
    val r = reminders
    if (r != null) put("reminders", buildJsonArray {
        r.forEach { rem ->
            add(buildJsonObject {
                put("minutes_before", JsonPrimitive(rem.minutesBefore))
                put("method", JsonPrimitive(rem.method))
            })
        }
    })
}
