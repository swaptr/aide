package com.sabreware.aide.app.tools.calendar

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log
import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.llm.gates.WriteConfirmGate
import com.sabreware.aide.core.domain.llm.verify.runWithVerify
import com.sabreware.aide.core.domain.permission.AppPermission
import com.sabreware.aide.core.domain.permission.CategoryRequirement
import com.sabreware.aide.core.domain.permission.RuntimePermissionGate
import com.sabreware.aide.core.domain.tools.ToolCategory
import com.sabreware.aide.core.domain.tools.Toolset
import com.sabreware.aide.core.domain.tools.ToolsetScope
import com.sabreware.aide.core.domain.tools.boolProp
import com.sabreware.aide.core.domain.tools.intProp
import com.sabreware.aide.core.domain.tools.objectSchema
import com.sabreware.aide.core.domain.tools.stringArrayProp
import com.sabreware.aide.core.domain.tools.stringProp
import com.sabreware.aide.app.data.notification.Reminders
import com.sabreware.aide.platform.android.intent.IntentDispatcher
import com.sabreware.aide.platform.android.intent.IntentDispatchers
import com.sabreware.aide.app.tools.verify.CalendarEventEvidence
import com.sabreware.aide.app.tools.verify.CalendarEventVerifier
import java.time.DayOfWeek
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val TAG = "CalendarToolset"

/** A year either side. Beyond that the provider's recurrence expansion, not our result cap, is the cost. */
private const val MAX_SCAN_DAYS = 365

/** Widest explicit `[start, end)` window — the same two years a full relative scan may cover. */
private const val MAX_RANGE_DAYS = 2 * MAX_SCAN_DAYS
private const val DEFAULT_DAYS_BEFORE = 7
private const val DEFAULT_DAYS_AFTER = 60
private const val DEFAULT_MAX_RESULTS = 10
private const val MAX_MAX_RESULTS = 100
private const val MILLIS_PER_DAY = 86_400_000L

/**
 * The `[start, end)` millisecond window a calendar scan may cover, from the model's requested day counts.
 *
 * Extracted so the clamp is a fact that can be asserted rather than a line inside a handler that needs a
 * `Context`. Both counts are clamped: unclamped they fed `CalendarContract.Instances`, which expands
 * recurring events PROVIDER-SIDE, so "the next twenty years" made the provider materialise every occurrence
 * in that window before anything came back. A null count means the caller did not ask, and gets the default.
 */
internal fun calendarScanWindow(daysBefore: Int?, daysAfter: Int?, now: Long): Pair<Long, Long> {
    val before = (daysBefore ?: DEFAULT_DAYS_BEFORE).coerceIn(0, MAX_SCAN_DAYS)
    val after = (daysAfter ?: DEFAULT_DAYS_AFTER).coerceIn(0, MAX_SCAN_DAYS)
    return (now - before * MILLIS_PER_DAY) to (now + after * MILLIS_PER_DAY)
}

/** The result cap a calendar query may use. Always at least one row, never more than [MAX_MAX_RESULTS]. */
internal fun clampMaxResults(requested: Int?): Int =
    (requested ?: DEFAULT_MAX_RESULTS).coerceIn(1, MAX_MAX_RESULTS)

/**
 * A revoked permission is not a missing row.
 *
 * `SecurityException` used to fold into IO_ERROR (and, on the delete path, into EVENT_NOT_FOUND), so a
 * permission revoked mid-turn read to the model as "no such event" — and its response to that is to try
 * another query, not to tell the user to open Settings.
 */
private fun Throwable.toCalendarErrorCode(): String =
    if (this is SecurityException) CalErr.PERMISSION_DENIED else CalErr.IO_ERROR
private val BOTH_SURFACES: Set<Surface> = setOf(Surface.CHAT, Surface.IME, Surface.VOICE)
private val CHAT_ONLY: Set<Surface> = setOf(Surface.CHAT)

object CalErr {
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val INVALID_ARGS = "INVALID_ARGS"
    const val INVALID_TIME_RANGE = "INVALID_TIME_RANGE"
    const val EVENT_NOT_FOUND = "EVENT_NOT_FOUND"
    const val NO_WRITABLE_CALENDAR = "NO_WRITABLE_CALENDAR"
    const val LAUNCH_FAILED = "LAUNCH_FAILED"
    const val USER_CANCELLED = "USER_CANCELLED"
    const val CONFIRM_TIMEOUT = "CONFIRM_TIMEOUT"
    const val CANCELLED_BY_USER = "CANCELLED_BY_USER"
    const val IO_ERROR = "IO_ERROR"
}

private val CAL_ERROR_CODES = setOf(
    CalErr.PERMISSION_DENIED,
    CalErr.INVALID_ARGS,
    CalErr.INVALID_TIME_RANGE,
    CalErr.EVENT_NOT_FOUND,
    CalErr.NO_WRITABLE_CALENDAR,
    CalErr.LAUNCH_FAILED,
    CalErr.USER_CANCELLED,
    CalErr.CONFIRM_TIMEOUT,
    CalErr.CANCELLED_BY_USER,
    CalErr.IO_ERROR,
)

class CalendarToolset(
    private val context: Context,
    private val writeGate: WriteConfirmGate,
    private val gate: RuntimePermissionGate,
    private val dispatchers: IntentDispatchers,
) : Toolset {

    override val category = ToolCategory.Calendar
    override val displayName = "Calendar"
    override val blurb = "Read, add, and edit calendar events."
    override val requirement = CategoryRequirement.Runtime(AppPermission.CALENDAR)

    /** A broad, occasional area — worth a RequestToolset round trip. */
    override val onDemand = true

    override fun tools(scope: ToolsetScope): List<AideTool> =
        asAideTools(dispatchers.forSurface(scope.surface))

    fun asAideTools(dispatcher: IntentDispatcher): List<AideTool> = listOf(
        listCalendarsTool(),
        getEventsTool(),
        findEventsTool(),
        addEventTool(dispatcher),
        editEventTool(dispatcher),
        deleteEventTool(),
    )

    private fun listCalendarsTool(): AideTool = AideTool.Function(
        name = "CalendarListCalendars",
        readOnly = true,
        description = "List the user's calendar accounts. Returns each calendar's id, " +
            "display name, account, primary flag, writability, and color. Use before " +
            "CalendarAddEvent when the user has multiple accounts to pick a target.",
        parametersSchema = objectSchema(requiredProps = emptyList()),
        handler = {
            denyIfMissing()?.let { return@Function it }
            runCatching { queryCalendars() }
                .fold(
                    onSuccess = { CalendarResult.Calendars(it).toEnvelope() },
                    onFailure = { CalendarResult.Err(it.toCalendarErrorCode(), it.message ?: "io").toEnvelope() },
                )
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CAL_ERROR_CODES,
    )

    private fun getEventsTool(): AideTool = AideTool.Function(
        name = "CalendarGetEvents",
        readOnly = true,
        description = "Return events that overlap a time window. ISO-8601 inputs with " +
            "timezone offset, e.g. '2026-05-20T00:00:00+05:30'. Optional filters: " +
            "specific calendar ids, attendees/reminders fan-out. Sorted by start time.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "start_iso" to stringProp("Window start (inclusive), ISO-8601 with offset."),
                "end_iso" to stringProp("Window end (exclusive), ISO-8601 with offset. Capped at 2 years after start."),
            ),
            optionalProps = listOf(
                "calendar_ids" to stringArrayProp("Restrict to these calendar ids (decimal strings)."),
                "include_attendees" to boolProp("Fetch attendees per event; default false."),
                "include_reminders" to boolProp("Fetch reminders per event; default false."),
                "max_results" to intProp("Cap; default 10, hard max 100."),
            ),
        ),
        handler = { args ->
            denyIfMissing()?.let { return@Function it }
            val (start, end) = parseRangeOrErr(args)
                ?: return@Function CalendarResult.Err(CalErr.INVALID_TIME_RANGE, "bad time range").toEnvelope()
            val calendarIds = (args["calendar_ids"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.content.toLongOrNull() }
                ?: emptyList()
            val includeAttendees = args["include_attendees"]?.jsonPrimitive?.boolean() ?: false
            val includeReminders = args["include_reminders"]?.jsonPrimitive?.boolean() ?: false
            val maxResults = clampMaxResults(args["max_results"]?.jsonPrimitive?.intOrNull)
            runCatching {
                queryInstances(start, end, calendarIds, maxResults, includeAttendees, includeReminders)
            }.fold(
                onSuccess = {
                    CalendarResult.Events(it, truncated = it.size >= maxResults).toEnvelope()
                },
                onFailure = {
                    CalendarResult.Err(it.toCalendarErrorCode(), it.message ?: "io").toEnvelope()
                },
            )
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CAL_ERROR_CODES,
    )

    private fun findEventsTool(): AideTool = AideTool.Function(
        name = "CalendarFindEvents",
        readOnly = true,
        description = "Find events by title-substring across a wide time window (default " +
            "−7 days … +60 days). Returns up to `max_results` matches sorted by start.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "query" to stringProp("Title fragment to match (case-insensitive)."),
            ),
            optionalProps = listOf(
                "days_before" to intProp("How many days back to scan; default 7, hard max 365."),
                "days_after" to intProp("How many days ahead to scan; default 60, hard max 365."),
                "max_results" to intProp("Cap; default 10, hard max 100."),
            ),
        ),
        handler = { args ->
            denyIfMissing()?.let { return@Function it }
            val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (query.isEmpty()) {
                return@Function CalendarResult.Err(CalErr.INVALID_ARGS, "query required").toEnvelope()
            }
            val window = calendarScanWindow(
                daysBefore = args["days_before"]?.jsonPrimitive?.intOrNull,
                daysAfter = args["days_after"]?.jsonPrimitive?.intOrNull,
                now = System.currentTimeMillis(),
            )
            val maxResults = clampMaxResults(args["max_results"]?.jsonPrimitive?.intOrNull)
            val start = window.first
            val end = window.second
            runCatching {
                queryInstances(
                    startMs = start,
                    endMs = end,
                    calendarIds = emptyList(),
                    maxResults = maxResults,
                    includeAttendees = false,
                    includeReminders = false,
                    titleFilter = query,
                )
            }.fold(
                onSuccess = {
                    CalendarResult.Events(it, truncated = it.size >= maxResults).toEnvelope()
                },
                onFailure = {
                    CalendarResult.Err(it.toCalendarErrorCode(), it.message ?: "io").toEnvelope()
                },
            )
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CAL_ERROR_CODES,
    )

    private fun addEventTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "CalendarAddEvent",
        description = "Open the calendar app's Insert screen pre-filled with the event. " +
            "The user still has to tap Save. Time inputs are ISO-8601 with offset; " +
            "the chosen `calendar_id` (optional) defaults to the system default. " +
            "Recurrence is a structured object — do NOT pass a raw RRULE string.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "title" to stringProp("Event title."),
                "start_iso" to stringProp("Start time, ISO-8601 with offset."),
            ),
            optionalProps = listOf(
                "end_iso" to stringProp("End time, ISO-8601 with offset."),
                "duration_minutes" to intProp("Duration in minutes if end_iso not provided; default 60."),
                "all_day" to boolProp("Mark as an all-day event."),
                "location" to stringProp("Location."),
                "description" to stringProp("Free-form description."),
                "attendee_emails" to stringArrayProp("Email addresses to invite."),
                "calendar_id" to intProp("Target calendar id; omit for the system default."),
                "recurrence_freq" to stringProp("One of DAILY|WEEKLY|MONTHLY|YEARLY for repeating events."),
                "recurrence_interval" to intProp("Interval between repeats; default 1."),
                "recurrence_count" to intProp("Total occurrences (mutually exclusive with recurrence_until_iso)."),
                "recurrence_until_iso" to stringProp("Last occurrence (mutually exclusive with recurrence_count)."),
                "recurrence_by_day" to stringArrayProp("Day codes for weekly repeats: MO,TU,WE,TH,FR,SA,SU."),
            ),
        ),
        handler = { args ->
            val title = args["title"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (title.isEmpty()) {
                return@Function CalendarResult.Err(CalErr.INVALID_ARGS, "title required").toEnvelope()
            }
            val startIso = args["start_iso"]?.jsonPrimitive?.content.orEmpty()
            val start = runCatching { OffsetDateTime.parse(startIso) }.getOrNull()
                ?: return@Function CalendarResult.Err(CalErr.INVALID_ARGS, "start_iso unparseable").toEnvelope()
            val endIso = args["end_iso"]?.jsonPrimitive?.content
            val end = when {
                endIso != null -> runCatching { OffsetDateTime.parse(endIso) }.getOrNull()
                    ?: return@Function CalendarResult.Err(CalErr.INVALID_ARGS, "end_iso unparseable").toEnvelope()
                else -> start.plusMinutes(
                    (args["duration_minutes"]?.jsonPrimitive?.intOrNull ?: 60).toLong(),
                )
            }
            if (!end.isAfter(start)) {
                return@Function CalendarResult.Err(
                    CalErr.INVALID_TIME_RANGE, "end must be after start",
                ).toEnvelope()
            }
            val allDay = args["all_day"]?.jsonPrimitive?.boolean() ?: false
            val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(
                    CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                    start.toInstant().toEpochMilli(),
                )
                putExtra(
                    CalendarContract.EXTRA_EVENT_END_TIME,
                    end.toInstant().toEpochMilli(),
                )
                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
                args["location"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
                    ?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                args["description"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
                    ?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
                args["calendar_id"]?.jsonPrimitive?.longOrNull
                    ?.let { putExtra(CalendarContract.Events.CALENDAR_ID, it) }
                (args["attendee_emails"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { putExtra(Intent.EXTRA_EMAIL, it.joinToString(",")) }
                buildRecurrenceRule(args)?.let {
                    putExtra(CalendarContract.Events.RRULE, it)
                }
            }
            runWithVerify(
                op = { launchIntent(intent, dispatcher, "CalendarAddEvent") },
                verifier = CalendarEventVerifier(
                    context = context,
                    gate = gate,
                    expectedTitle = title,
                    startMillis = start.toInstant().toEpochMilli(),
                ),
                evidenceSerializer = { ev: CalendarEventEvidence ->
                    kotlinx.serialization.json.buildJsonObject {
                        put("event_id", JsonPrimitive(ev.eventId))
                        put("calendar_id", JsonPrimitive(ev.calendarId))
                    }
                },
            )
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CAL_ERROR_CODES,
    )

    private fun editEventTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "CalendarEditEvent",
        description = "Open the calendar app's Edit screen for an existing event. The " +
            "user must tap Save. Pass any subset of patch fields; unspecified fields " +
            "are left untouched.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "event_id" to intProp("Event id from calendar_get_events / calendar_find_events."),
            ),
            optionalProps = listOf(
                "title" to stringProp("New title."),
                "start_iso" to stringProp("New start, ISO-8601."),
                "end_iso" to stringProp("New end, ISO-8601."),
                "location" to stringProp("New location."),
                "description" to stringProp("New description."),
            ),
        ),
        handler = { args ->
            val eventId = args["event_id"]?.jsonPrimitive?.longOrNull
                ?: return@Function CalendarResult.Err(CalErr.INVALID_ARGS, "event_id required").toEnvelope()
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val intent = Intent(Intent.ACTION_EDIT, uri).apply {
                args["title"]?.jsonPrimitive?.content?.let {
                    putExtra(CalendarContract.Events.TITLE, it)
                }
                args["start_iso"]?.jsonPrimitive?.content
                    ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                    ?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it.toInstant().toEpochMilli()) }
                args["end_iso"]?.jsonPrimitive?.content
                    ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                    ?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it.toInstant().toEpochMilli()) }
                args["location"]?.jsonPrimitive?.content
                    ?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                args["description"]?.jsonPrimitive?.content
                    ?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            }
            launchIntent(intent, dispatcher, "CalendarEditEvent")
        },
        surfaces = CHAT_ONLY,
        errorCodes = CAL_ERROR_CODES,
    )

    private fun deleteEventTool(): AideTool = AideTool.Function(
        name = "CalendarDeleteEvent",
        description = "Delete an event. The user is prompted to confirm; deletion " +
            "happens via the content provider, not via an intent. Requires WRITE_CALENDAR.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "event_id" to intProp("Event id."),
            ),
        ),
        handler = { args ->
            denyIfMissing()?.let { return@Function it }
            val eventId = args["event_id"]?.jsonPrimitive?.longOrNull
                ?: return@Function CalendarResult.Err(CalErr.INVALID_ARGS, "event_id required").toEnvelope()
            // fold, not getOrNull: a THROW here (permission revoked, OEM provider) is a different answer
            // from "the row is not there", and collapsing the two told the model to go looking again.
            val event = runCatching { lookupEventForDelete(eventId) }.fold(
                onSuccess = { it },
                onFailure = {
                    return@Function CalendarResult.Err(it.toCalendarErrorCode(), it.message ?: "io").toEnvelope()
                },
            ) ?: return@Function CalendarResult.Err(CalErr.EVENT_NOT_FOUND, "event $eventId not found").toEnvelope()
            confirmAndDelete(eventId, event)
        },
        surfaces = CHAT_ONLY,
        errorCodes = CAL_ERROR_CODES,
    )

    private suspend fun confirmAndDelete(eventId: Long, event: DeleteCandidate): JsonObject {
        val opId = UUID.randomUUID().toString()
        val outcome = writeGate.await(
            WriteConfirmGate.Prompt(
                opId = opId,
                toolName = "CalendarDeleteEvent",
                summary = "Delete event \"${event.title}\"?",
                details = buildList {
                    add(WriteConfirmGate.KeyValue("Title", event.title))
                    add(WriteConfirmGate.KeyValue("When", formatWhen(event.startMillis, event.allDay)))
                    if (event.calendarName != null) {
                        add(WriteConfirmGate.KeyValue("Calendar", event.calendarName))
                    }
                },
                severity = WriteConfirmGate.Severity.DANGER,
            ),
        )
        when (outcome) {
            is WriteConfirmGate.Result.Approved -> Unit
            is WriteConfirmGate.Result.Denied -> {
                val code = when (outcome.reason) {
                    WriteConfirmGate.Result.Reason.USER_REJECTED -> CalErr.USER_CANCELLED
                    WriteConfirmGate.Result.Reason.TIMEOUT -> CalErr.CONFIRM_TIMEOUT
                    WriteConfirmGate.Result.Reason.CANCELLED_BY_STOP -> CalErr.CANCELLED_BY_USER
                    WriteConfirmGate.Result.Reason.NO_HOST -> CalErr.CONFIRM_TIMEOUT
                }
                return CalendarResult.Err(code, "delete denied").toEnvelope()
            }
        }
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val rows = runCatching { context.contentResolver.delete(uri, null, null) }
            .getOrElse {
                Log.w(TAG, "delete failed", it)
                return CalendarResult.Err(it.toCalendarErrorCode(), it.message ?: "io").toEnvelope()
            }
        return CalendarResult.Deleted(eventId, deleted = rows > 0).toEnvelope()
    }

    private fun launchIntent(intent: Intent, dispatcher: IntentDispatcher, action: String): JsonObject {
        return when (val outcome = dispatcher.launch(intent)) {
            is IntentDispatcher.LaunchOutcome.Launched ->
                CalendarResult.Launched(action = action).toEnvelope()
            is IntentDispatcher.LaunchOutcome.Failed ->
                CalendarResult.Err(
                    CalErr.LAUNCH_FAILED,
                    outcome.error.message ?: outcome.error.javaClass.simpleName,
                ).toEnvelope()
        }
    }

    private fun denyIfMissing(): JsonObject? {
        if (gate.isGranted(AppPermission.CALENDAR)) return null
        return CalendarResult.Err(
            CalErr.PERMISSION_DENIED,
            AppPermission.CALENDAR.toolDeniedMessage,
        ).toEnvelope()
    }

    private fun queryCalendars(): List<CalendarResult.CalendarInfo> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        val out = mutableListOf<CalendarResult.CalendarInfo>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val accessLevel = cursor.getInt(5)
                out += CalendarResult.CalendarInfo(
                    id = cursor.getLong(0),
                    displayName = cursor.getString(1) ?: "(unnamed)",
                    accountName = cursor.getString(2) ?: "",
                    accountType = cursor.getString(3) ?: "",
                    isPrimary = cursor.getInt(4) == 1,
                    canWrite = accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                    color = cursor.getInt(6),
                )
            }
        }
        return out
    }

    private fun queryInstances(
        startMs: Long,
        endMs: Long,
        calendarIds: List<Long>,
        maxResults: Int,
        includeAttendees: Boolean,
        includeReminders: Boolean,
        titleFilter: String? = null,
    ): List<CalendarResult.EventInfo> {
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_TIMEZONE,
        )
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .apply {
                ContentUris.appendId(this, startMs)
                ContentUris.appendId(this, endMs)
            }.build()
        val selection = buildString {
            val parts = mutableListOf<String>()
            if (calendarIds.isNotEmpty()) {
                parts += "${CalendarContract.Instances.CALENDAR_ID} IN (" +
                    calendarIds.joinToString(",") + ")"
            }
            if (titleFilter != null) parts += "${CalendarContract.Instances.TITLE} LIKE ?"
            append(parts.joinToString(" AND "))
        }.ifEmpty { null }
        val args = titleFilter?.let { arrayOf("%$it%") }
        val events = mutableListOf<CalendarResult.EventInfo>()
        context.contentResolver.query(
            uri, projection, selection, args,
            // LIMIT rides the sort order, as in ContactsToolset.resolve — without it the provider
            // materialises and sorts every expanded instance in the window before the client-side
            // cap ever applies.
            "${CalendarContract.Instances.BEGIN} ASC LIMIT $maxResults",
        )?.use { cursor ->
            while (cursor.moveToNext() && events.size < maxResults) {
                events += CalendarResult.EventInfo(
                    id = cursor.getLong(0),
                    calendarId = cursor.getLong(1),
                    title = cursor.getString(2) ?: "",
                    location = cursor.getString(3),
                    description = cursor.getString(4),
                    startMillis = cursor.getLong(5),
                    endMillis = cursor.getLong(6),
                    allDay = cursor.getInt(7) == 1,
                    timezone = cursor.getString(8),
                    attendees = null,
                    reminders = null,
                )
            }
        }
        if (events.isEmpty()) return events
        if (includeAttendees) attachAttendees(events)
        if (includeReminders) attachReminders(events)
        return events
    }

    private fun attachAttendees(events: MutableList<CalendarResult.EventInfo>) {
        val ids = events.map { it.id }
        val selection = "${CalendarContract.Attendees.EVENT_ID} IN (" +
            ids.joinToString(",") + ")"
        val byEvent = mutableMapOf<Long, MutableList<CalendarResult.Attendee>>()
        context.contentResolver.query(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf(
                CalendarContract.Attendees.EVENT_ID,
                CalendarContract.Attendees.ATTENDEE_NAME,
                CalendarContract.Attendees.ATTENDEE_EMAIL,
                CalendarContract.Attendees.ATTENDEE_STATUS,
            ),
            selection, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val evId = cursor.getLong(0)
                byEvent.getOrPut(evId) { mutableListOf() }.add(
                    CalendarResult.Attendee(
                        name = cursor.getString(1),
                        email = cursor.getString(2) ?: "",
                        status = attendeeStatusLabel(cursor.getInt(3)),
                    ),
                )
            }
        }
        events.replaceAll { it.copy(attendees = byEvent[it.id] ?: emptyList()) }
    }

    private fun attachReminders(events: MutableList<CalendarResult.EventInfo>) {
        val ids = events.map { it.id }
        val selection = "${CalendarContract.Reminders.EVENT_ID} IN (" +
            ids.joinToString(",") + ")"
        val byEvent = mutableMapOf<Long, MutableList<CalendarResult.Reminder>>()
        context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(
                CalendarContract.Reminders.EVENT_ID,
                CalendarContract.Reminders.MINUTES,
                CalendarContract.Reminders.METHOD,
            ),
            selection, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val evId = cursor.getLong(0)
                byEvent.getOrPut(evId) { mutableListOf() }.add(
                    CalendarResult.Reminder(
                        minutesBefore = cursor.getInt(1),
                        method = reminderMethodLabel(cursor.getInt(2)),
                    ),
                )
            }
        }
        events.replaceAll { it.copy(reminders = byEvent[it.id] ?: emptyList()) }
    }

    private fun parseRangeOrErr(args: JsonObject): Pair<Long, Long>? {
        val startIso = args["start_iso"]?.jsonPrimitive?.content ?: return null
        val endIso = args["end_iso"]?.jsonPrimitive?.content ?: return null
        val start = runCatching { OffsetDateTime.parse(startIso) }.getOrNull() ?: return null
        val end = runCatching { OffsetDateTime.parse(endIso) }.getOrNull() ?: return null
        val startMs = start.toInstant().toEpochMilli()
        val endMs = end.toInstant().toEpochMilli()
        if (endMs <= startMs) return null
        // Cap, don't reject: the span limit exists for the provider's sake (see MAX_SCAN_DAYS), and a
        // truncated window still answers the question.
        return startMs to endMs.coerceAtMost(startMs + MAX_RANGE_DAYS * MILLIS_PER_DAY)
    }

    private fun buildRecurrenceRule(args: JsonObject): String? {
        val freqRaw = args["recurrence_freq"]?.jsonPrimitive?.content ?: return null
        val freq = runCatching { RRuleBuilder.Freq.valueOf(freqRaw.uppercase()) }.getOrNull()
            ?: return null
        val interval = args["recurrence_interval"]?.jsonPrimitive?.intOrNull ?: 1
        val count = args["recurrence_count"]?.jsonPrimitive?.intOrNull
        // RRuleBuilder is commonMain now → takes a kotlinx-datetime Instant (this data-layer file stays JVM).
        val until = args["recurrence_until_iso"]?.jsonPrimitive?.content
            ?.let { runCatching { kotlinx.datetime.Instant.parse(it) }.getOrNull() }
        val byDay = (args["recurrence_by_day"] as? JsonArray)
            ?.mapNotNull { dayFromCode(it.jsonPrimitive.content) }
            ?: emptyList()
        if (count != null && until != null) return null
        return RRuleBuilder.build(
            RRuleBuilder.Recurrence(
                freq = freq,
                interval = interval,
                count = count,
                until = until,
                byDay = byDay,
            ),
        )
    }

    private data class DeleteCandidate(
        val title: String,
        val startMillis: Long,
        val allDay: Boolean,
        val calendarName: String?,
    )

    private fun lookupEventForDelete(eventId: Long): DeleteCandidate? {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.CALENDAR_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return DeleteCandidate(
                    title = cursor.getString(0) ?: "(untitled)",
                    startMillis = cursor.getLong(1),
                    allDay = cursor.getInt(2) == 1,
                    calendarName = cursor.getString(3),
                )
            }
        }
        return null
    }

    private fun formatWhen(epochMs: Long, allDay: Boolean): String {
        val instant = Instant.ofEpochMilli(epochMs)
        val zoned = instant.atZone(java.time.ZoneId.systemDefault())
        val fmt = if (allDay) "EEE, MMM d yyyy (all day)" else "EEE, MMM d yyyy 'at' h:mm a"
        return zoned.format(java.time.format.DateTimeFormatter.ofPattern(fmt))
    }

    private fun dayFromCode(code: String): DayOfWeek? = when (code.uppercase()) {
        "MO" -> DayOfWeek.MONDAY
        "TU" -> DayOfWeek.TUESDAY
        "WE" -> DayOfWeek.WEDNESDAY
        "TH" -> DayOfWeek.THURSDAY
        "FR" -> DayOfWeek.FRIDAY
        "SA" -> DayOfWeek.SATURDAY
        "SU" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun attendeeStatusLabel(value: Int): String = when (value) {
        CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> "accepted"
        CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> "declined"
        CalendarContract.Attendees.ATTENDEE_STATUS_INVITED -> "invited"
        CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> "tentative"
        CalendarContract.Attendees.ATTENDEE_STATUS_NONE -> "none"
        else -> "unknown"
    }

    private fun reminderMethodLabel(value: Int): String = when (value) {
        CalendarContract.Reminders.METHOD_ALERT -> "alert"
        CalendarContract.Reminders.METHOD_EMAIL -> "email"
        CalendarContract.Reminders.METHOD_SMS -> "sms"
        CalendarContract.Reminders.METHOD_DEFAULT -> "default"
        else -> "unknown"
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.boolean(): Boolean? = when (content) {
    "true" -> true
    "false" -> false
    else -> null
}
