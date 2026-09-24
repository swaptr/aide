package com.sabreware.aide.app.tools

import com.sabreware.aide.app.tools.calendar.calendarScanWindow
import com.sabreware.aide.app.tools.calendar.clampMaxResults
import com.sabreware.aide.app.tools.contacts.clampContactMatches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bounds every provider query is held to.
 *
 * This module had no tests at all, and the unbounded ranges were a real finding: the calendar's
 * `days_before` / `days_after` went to `CalendarContract.Instances` un-clamped, and that API expands
 * recurring events provider-side — so a model asking for a twenty-year window made the provider materialise
 * every occurrence in it before returning anything. The contacts cap became a SQL `LIMIT` for the same
 * reason: without one the provider sorts the whole match set of a leading-wildcard `LIKE`.
 *
 * These are pure functions precisely so the bound is assertable — the arithmetic used to live inside a tool
 * handler that needs a `Context`, which is the same as not being testable.
 */
class ProviderQueryBoundsTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    @Test
    fun `an unspecified window uses the documented defaults`() {
        val (start, end) = calendarScanWindow(daysBefore = null, daysAfter = null, now = now)

        assertEquals(now - 7 * day, start, "default is 7 days back")
        assertEquals(now + 60 * day, end, "default is 60 days ahead")
    }

    @Test
    fun `a requested window is honoured when it is within bounds`() {
        val (start, end) = calendarScanWindow(daysBefore = 3, daysAfter = 14, now = now)

        assertEquals(now - 3 * day, start)
        assertEquals(now + 14 * day, end)
    }

    @Test
    fun `an enormous window is clamped to a year either side`() {
        val (start, end) = calendarScanWindow(daysBefore = 20 * 365, daysAfter = 20 * 365, now = now)

        assertEquals(now - 365 * day, start)
        assertEquals(now + 365 * day, end)
    }

    @Test
    fun `a negative window collapses to now rather than inverting`() {
        val (start, end) = calendarScanWindow(daysBefore = -30, daysAfter = -30, now = now)

        assertEquals(now, start)
        assertEquals(now, end)
        assertTrue(start <= end, "the window must never be inverted, whatever was asked for")
    }

    @Test
    fun `the calendar result cap has a floor of one and a ceiling of a hundred`() {
        assertEquals(10, clampMaxResults(null), "the default when the caller does not ask")
        assertEquals(25, clampMaxResults(25))
        assertEquals(1, clampMaxResults(0), "never zero rows — that reads as 'no events' rather than 'no cap'")
        assertEquals(1, clampMaxResults(-5))
        assertEquals(100, clampMaxResults(100_000))
    }

    @Test
    fun `the contact match cap has a floor of one and a ceiling of twenty`() {
        assertEquals(5, clampContactMatches(null))
        assertEquals(12, clampContactMatches(12))
        assertEquals(1, clampContactMatches(0))
        assertEquals(1, clampContactMatches(-1))
        assertEquals(20, clampContactMatches(Int.MAX_VALUE), "this becomes a SQL LIMIT; it cannot be unbounded")
    }
}
