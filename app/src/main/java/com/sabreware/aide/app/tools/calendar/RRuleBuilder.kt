package com.sabreware.aide.app.tools.calendar

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// CalendarContract.Events.RRULE column wants RFC 5545 RRULE value-half only
// (e.g. "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE") — no iCal "RRULE:" prefix.
object RRuleBuilder {

    enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    data class Recurrence(
        val freq: Freq,
        val interval: Int = 1,
        val count: Int? = null,
        val until: Instant? = null,
        val byDay: List<DayOfWeek> = emptyList(),
    )

    fun build(r: Recurrence): String {
        require(r.interval > 0) { "interval must be > 0" }
        require(r.count == null || r.until == null) {
            "recurrence cannot have both count and until"
        }
        val parts = mutableListOf<String>()
        parts += "FREQ=${r.freq.name}"
        if (r.interval > 1) parts += "INTERVAL=${r.interval}"
        if (r.count != null) parts += "COUNT=${r.count}"
        if (r.until != null) {
            // RFC 5545 UNTIL is UTC basic ISO: 20260520T150000Z.
            val ldt = r.until.toLocalDateTime(TimeZone.UTC)
            val y = ldt.year.toString().padStart(4, '0')
            val mm = ldt.monthNumber.toString().padStart(2, '0')
            val dd = ldt.dayOfMonth.toString().padStart(2, '0')
            val hh = ldt.hour.toString().padStart(2, '0')
            val min = ldt.minute.toString().padStart(2, '0')
            val ss = ldt.second.toString().padStart(2, '0')
            parts += "UNTIL=${y}${mm}${dd}T${hh}${min}${ss}Z"
        }
        if (r.byDay.isNotEmpty()) {
            parts += "BYDAY=" + r.byDay.joinToString(",") { dayCode(it) }
        }
        return parts.joinToString(";")
    }

    private fun dayCode(d: DayOfWeek): String = when (d) {
        DayOfWeek.MONDAY -> "MO"
        DayOfWeek.TUESDAY -> "TU"
        DayOfWeek.WEDNESDAY -> "WE"
        DayOfWeek.THURSDAY -> "TH"
        DayOfWeek.FRIDAY -> "FR"
        DayOfWeek.SATURDAY -> "SA"
        DayOfWeek.SUNDAY -> "SU"
    }
}
