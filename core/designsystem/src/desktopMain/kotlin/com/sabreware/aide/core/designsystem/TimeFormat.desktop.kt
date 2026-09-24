package com.sabreware.aide.core.designsystem

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun relativeTimeLabel(timestampMs: Long, nowMs: Long): String {
    val delta = nowMs - timestampMs
    val min = 60_000L
    val hour = 60 * min
    val day = 24 * hour
    return when {
        delta < min -> "Just now"
        delta < hour -> "${delta / min} min ago"
        delta < day -> "${delta / hour} hr ago"
        delta < 2 * day -> "Yesterday"
        else -> "${delta / day} days ago"
    }
}
