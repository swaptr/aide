package com.sabreware.aide.app.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.verify.runWithVerify
import com.sabreware.aide.core.domain.tools.BOTH_SURFACES
import com.sabreware.aide.core.domain.tools.ToolCategory
import com.sabreware.aide.core.domain.tools.Toolset
import com.sabreware.aide.core.domain.tools.ToolsetScope
import com.sabreware.aide.core.domain.tools.boolProp
import com.sabreware.aide.core.domain.tools.intProp
import com.sabreware.aide.core.domain.tools.objectSchema
import com.sabreware.aide.app.tools.results.ClockResult
import com.sabreware.aide.core.domain.tools.stringArrayProp
import com.sabreware.aide.core.domain.tools.stringProp
import com.sabreware.aide.platform.android.intent.IntentDispatcher
import com.sabreware.aide.platform.android.intent.IntentDispatchers
import com.sabreware.aide.app.tools.notification.ReminderToolset
import com.sabreware.aide.app.tools.verify.NextAlarmEvidence
import com.sabreware.aide.app.tools.verify.NextAlarmVerifier
import java.util.Calendar
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val TAG = "AideTools"
private val CLOCK_ERROR_CODES = setOf("NO_CLOCK_APP", "INVALID_ARGS", "LAUNCH_FAILED")

// EXTRA_SKIP_UI is a request, not a guarantee — some OEM clock apps ignore it and still
// pop their editor. We send it anyway since Google Clock honours it.
class ClockToolset(
    private val context: Context,
    private val dispatchers: IntentDispatchers,
    private val reminders: ReminderToolset,
) : Toolset {

    override val category = ToolCategory.Clock
    override val displayName = "Clock"
    override val blurb = "Set alarms, timers and reminders; list and dismiss them."

    /** Alarms and timers are a broad, occasional area — worth a RequestToolset round trip. */
    override val onDemand = true

    // Scheduling a reminder is the same user intent as setting an alarm, so it rides this category
    // rather than adding a Settings row of its own.
    override fun tools(scope: ToolsetScope): List<AideTool> =
        asAideTools(dispatchers.forSurface(scope.surface)) + reminders.asAideTools()

    fun asAideTools(dispatcher: IntentDispatcher): List<AideTool> = listOf(
        setAlarmTool(dispatcher),
        setTimerTool(dispatcher),
        showAlarmsTool(dispatcher),
        showTimersTool(dispatcher),
        dismissAlarmTool(dispatcher),
        snoozeAlarmTool(dispatcher),
        dismissTimerTool(dispatcher),
    )

    private fun setAlarmTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "SetAlarm",
        description = "Set a new alarm on the user's device clock app. Hour is 24-hour " +
            "(0-23). `daysOfWeek` makes the alarm repeat on the given weekdays; omit for " +
            "a one-shot alarm. `vibrate` defaults to true. The clock app applies the " +
            "alarm silently when possible.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "hour" to intProp("Hour in 24-hour format, 0-23."),
                "minutes" to intProp("Minutes, 0-59."),
            ),
            optionalProps = listOf(
                "message" to stringProp("Label for the alarm, e.g. 'Standup'."),
                "daysOfWeek" to stringArrayProp(
                    "Repeat days as 3-letter names: 'mon','tue','wed','thu','fri','sat','sun'.",
                ),
                "vibrate" to boolProp("Vibrate on alarm; defaults true."),
            ),
        ),
        handler = { args ->
            // NOT `$args` — that object carries the alarm's label, which is free text the user wrote.
            Log.i(TAG, "SetAlarm called: ${args.keys.sorted()}")
            val hour = args["hour"]?.jsonPrimitive?.intOrNull
            val minutes = args["minutes"]?.jsonPrimitive?.intOrNull
            if (hour == null || hour !in 0..23) return@Function clockInvalidArgs("hour must be 0-23")
            if (minutes == null || minutes !in 0..59) return@Function clockInvalidArgs("minutes must be 0-59")
            val message = args["message"]?.jsonPrimitive?.content
            val vibrate = args["vibrate"]?.jsonPrimitive?.booleanOrNull ?: true
            val days = (args["daysOfWeek"] as? JsonArray)
                ?.let { runCatching { parseDays(it) }.getOrNull() }
            if (args["daysOfWeek"] != null && days == null) {
                return@Function clockInvalidArgs("daysOfWeek must be 3-letter names")
            }
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                if (!message.isNullOrEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_VIBRATE, vibrate)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (days != null && days.isNotEmpty()) {
                    putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
                }
            }
            runWithVerify(
                op = { dispatch(intent, dispatcher) },
                verifier = NextAlarmVerifier(context, hour, minutes),
                evidenceSerializer = { ev: NextAlarmEvidence ->
                    buildJsonObject {
                        put("trigger_epoch_ms", JsonPrimitive(ev.triggerEpochMs))
                        if (ev.label != null) put("label", JsonPrimitive(ev.label))
                    }
                },
            )
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CLOCK_ERROR_CODES,
    )

    private fun setTimerTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "SetTimer",
        description = "Start a countdown timer on the user's device clock app. `lengthSeconds` " +
            "is the timer duration, 1 second to 24 hours. The clock app applies the timer " +
            "silently when possible.",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "lengthSeconds" to intProp("Timer duration in seconds, 1-86400."),
            ),
            optionalProps = listOf(
                "message" to stringProp("Label for the timer, e.g. 'Tea'."),
            ),
        ),
        handler = { args ->
            Log.i(TAG, "SetTimer called: ${args.keys.sorted()}")
            val length = args["lengthSeconds"]?.jsonPrimitive?.intOrNull
            if (length == null || length !in 1..86_400) {
                return@Function clockInvalidArgs("lengthSeconds must be 1-86400")
            }
            val message = args["message"]?.jsonPrimitive?.content
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, length)
                if (!message.isNullOrEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            dispatch(intent, dispatcher)
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CLOCK_ERROR_CODES,
    )

    private fun showAlarmsTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "ShowAlarms",
        description = "Open the clock app's alarms list. Use when the user asks to see, " +
            "review, or manage their alarms. Cannot read alarms back into the chat — the " +
            "Android API does not expose alarm contents.",
        parametersSchema = objectSchema(requiredProps = emptyList()),
        handler = {
            Log.i(TAG, "ShowAlarms called")
            dispatch(Intent(AlarmClock.ACTION_SHOW_ALARMS), dispatcher)
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CLOCK_ERROR_CODES,
    )

    private fun showTimersTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "ShowTimers",
        description = "Open the clock app's timers list. Use when the user asks to see " +
            "or manage their timers.",
        parametersSchema = objectSchema(requiredProps = emptyList()),
        handler = {
            Log.i(TAG, "ShowTimers called")
            dispatch(Intent(AlarmClock.ACTION_SHOW_TIMERS), dispatcher)
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CLOCK_ERROR_CODES,
    )

    private fun dismissAlarmTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "DismissAlarm",
        description = "Dismiss a scheduled or firing alarm. `searchMode` selects which " +
            "alarm: 'next' = the next scheduled one, 'all' = every firing alarm, 'label' = " +
            "match by message text (provide `message`), 'time' = match by clock face " +
            "(provide `hour`, `minutes`, and `isPm` if using 12-hour).",
        parametersSchema = objectSchema(
            requiredProps = listOf(
                "searchMode" to stringProp("One of: 'next','all','label','time'."),
            ),
            optionalProps = listOf(
                "message" to stringProp("Label to match when searchMode='label'."),
                "hour" to intProp("Hour to match when searchMode='time' (12 or 24-hour)."),
                "minutes" to intProp("Minutes to match when searchMode='time'."),
                "isPm" to boolProp("Set true if hour is PM in 12-hour form; ignored for 24-hour."),
            ),
        ),
        handler = { args ->
            Log.i(TAG, "DismissAlarm called: ${args.keys.sorted()}")
            val mode = args["searchMode"]?.jsonPrimitive?.content
            val modeExtra = when (mode) {
                "next" -> AlarmClock.ALARM_SEARCH_MODE_NEXT
                "all" -> AlarmClock.ALARM_SEARCH_MODE_ALL
                "label" -> AlarmClock.ALARM_SEARCH_MODE_LABEL
                "time" -> AlarmClock.ALARM_SEARCH_MODE_TIME
                else -> return@Function clockInvalidArgs("searchMode must be next|all|label|time")
            }
            val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, modeExtra)
                if (mode == "label") {
                    val msg = args["message"]?.jsonPrimitive?.content
                    if (msg.isNullOrEmpty()) return@Function clockInvalidArgs("label mode needs message")
                    putExtra(AlarmClock.EXTRA_MESSAGE, msg)
                }
                if (mode == "time") {
                    val h = args["hour"]?.jsonPrimitive?.intOrNull
                    val m = args["minutes"]?.jsonPrimitive?.intOrNull
                    if (h == null || h !in 0..23) return@Function clockInvalidArgs("time mode needs hour 0-23")
                    if (m == null || m !in 0..59) return@Function clockInvalidArgs("time mode needs minutes 0-59")
                    putExtra(AlarmClock.EXTRA_HOUR, h)
                    putExtra(AlarmClock.EXTRA_MINUTES, m)
                    args["isPm"]?.jsonPrimitive?.booleanOrNull?.let {
                        putExtra(AlarmClock.EXTRA_IS_PM, it)
                    }
                }
            }
            dispatch(intent, dispatcher)
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CLOCK_ERROR_CODES,
    )

    private fun snoozeAlarmTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "SnoozeAlarm",
        description = "Snooze the currently firing alarm. Has no effect if no alarm is " +
            "actively ringing.",
        parametersSchema = objectSchema(requiredProps = emptyList()),
        handler = {
            Log.i(TAG, "SnoozeAlarm called")
            dispatch(Intent(AlarmClock.ACTION_SNOOZE_ALARM), dispatcher)
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CLOCK_ERROR_CODES,
    )

    private fun dismissTimerTool(dispatcher: IntentDispatcher): AideTool = AideTool.Function(
        name = "DismissTimer",
        description = "Dismiss the currently firing timer.",
        parametersSchema = objectSchema(requiredProps = emptyList()),
        handler = {
            Log.i(TAG, "DismissTimer called")
            dispatch(Intent(AlarmClock.ACTION_DISMISS_TIMER), dispatcher)
        },
        surfaces = BOTH_SURFACES,
        errorCodes = CLOCK_ERROR_CODES,
    )

    private fun dispatch(intent: Intent, dispatcher: IntentDispatcher): JsonObject {
        if (intent.resolveActivity(context.packageManager) == null) {
            Log.w(TAG, "no clock app handler for ${intent.action}")
            return ClockResult.Err(
                "NO_CLOCK_APP",
                "no installed clock app handles ${intent.action}",
            ).toEnvelope()
        }
        return when (val outcome = dispatcher.launch(intent)) {
            is IntentDispatcher.LaunchOutcome.Launched ->
                ClockResult.Launched(intent.action.orEmpty()).toEnvelope()
            is IntentDispatcher.LaunchOutcome.Failed -> {
                Log.w(TAG, "dispatcher launch failed for ${intent.action}", outcome.error)
                ClockResult.Err(
                    "LAUNCH_FAILED",
                    outcome.error.message ?: outcome.error.javaClass.simpleName,
                ).toEnvelope()
            }
        }
    }

    private fun clockInvalidArgs(reason: String): JsonObject =
        ClockResult.Err("INVALID_ARGS", reason).toEnvelope()

    private fun parseDays(arr: JsonArray): List<Int> = arr.map { el ->
        when (el.jsonPrimitive.content.lowercase().take(3)) {
            "mon" -> Calendar.MONDAY
            "tue" -> Calendar.TUESDAY
            "wed" -> Calendar.WEDNESDAY
            "thu" -> Calendar.THURSDAY
            "fri" -> Calendar.FRIDAY
            "sat" -> Calendar.SATURDAY
            "sun" -> Calendar.SUNDAY
            else -> throw IllegalArgumentException("bad day '${el.jsonPrimitive.content}'")
        }
    }
}
