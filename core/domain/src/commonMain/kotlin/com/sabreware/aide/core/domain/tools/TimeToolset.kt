package com.sabreware.aide.core.domain.tools

import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.tools.results.TimeResult
import com.sabreware.aide.core.domain.util.AideLog
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private const val TAG = "AideTools"

class TimeToolset : Toolset {

    override val category = ToolCategory.Time
    override val displayName = "Time"
    override val blurb = "Read the current date and time."

    override fun tools(scope: ToolsetScope): List<AideTool> = listOf(asAideTool())

    fun asAideTool(): AideTool = AideTool.Function(
        name = "CurrentTime",
        readOnly = true,
        description = "Get the current local date and time on the user's device.",
        parametersSchema = objectSchema(requiredProps = emptyList()),
        handler = {
            AideLog.i(TAG, "CurrentTime called")
            val now = Clock.System.now()
            val tz = TimeZone.currentSystemDefault()
            val ldt = now.toLocalDateTime(tz)
            TimeResult.Now(
                iso = now.toString(),
                human = humanDateTime(ldt, tz.id),
                zone = tz.id,
            ).toEnvelope()
        },
        surfaces = BOTH_SURFACES,
    )

    // e.g. "Monday, July 21 2026 at 3:45 PM America/Los_Angeles" — readable local datetime + zone id.
    private fun humanDateTime(ldt: LocalDateTime, zoneId: String): String {
        val day = ldt.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val month = ldt.month.name.lowercase().replaceFirstChar { it.uppercase() }
        val hour12 = (ldt.hour % 12).let { if (it == 0) 12 else it }
        val amPm = if (ldt.hour < 12) "AM" else "PM"
        val minute = ldt.minute.toString().padStart(2, '0')
        return "$day, $month ${ldt.dayOfMonth} ${ldt.year} at $hour12:$minute $amPm $zoneId"
    }
}
