package com.swaptr.aide.domain.tools.calendar

import java.time.DayOfWeek
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

// CalendarContract.Events.RRULE column wants RFC 5545 RRULE value-half only
// (e.g. "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE") — no iCal "RRULE:" prefix.
object RRuleBuilder {

    enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    data class Recurrence(
        val freq: Freq,
        val interval: Int = 1,
        val count: Int? = null,
        val until: OffsetDateTime? = null,
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
            val utc = r.until.toInstant().atOffset(java.time.ZoneOffset.UTC)
            parts += "UNTIL=" + utc.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
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
