package com.sabreware.aide.core.designsystem

import android.text.format.DateUtils

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun relativeTimeLabel(timestampMs: Long, nowMs: Long): String {
    if (nowMs - timestampMs < DateUtils.MINUTE_IN_MILLIS) return "Just now"
    return DateUtils.getRelativeTimeSpanString(timestampMs, nowMs, DateUtils.MINUTE_IN_MILLIS).toString()
}
