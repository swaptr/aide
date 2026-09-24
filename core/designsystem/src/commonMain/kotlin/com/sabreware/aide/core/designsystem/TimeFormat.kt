package com.sabreware.aide.core.designsystem

/**
 * A human "27 minutes ago" / "Yesterday" / "2 days ago" label for a timestamp. Android backs this with the
 * platform `DateUtils.getRelativeTimeSpanString` so phrasing is localised and matches the rest of the
 * system; desktop uses a simple common approximation. Sub-minute ages read as "Just now".
 */
expect fun relativeTimeLabel(timestampMs: Long, nowMs: Long = currentTimeMillis()): String

/** Platform wall-clock millis (kotlin.time.Clock is experimental; keep a thin shim). */
expect fun currentTimeMillis(): Long
