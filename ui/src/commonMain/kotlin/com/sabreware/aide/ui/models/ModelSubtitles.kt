package com.sabreware.aide.ui.models

import com.sabreware.aide.core.domain.download.DownloadStatus
import kotlin.math.roundToInt

// Byte and download formatters, shared by every model row and sheet.

internal fun humanBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var b = bytes.toDouble() / 1024.0
    var i = 0
    while (b >= 1024.0 && i < units.lastIndex) { b /= 1024.0; i++ }
    // One-decimal, commonMain-safe (String.format(Locale) is JVM-only).
    val r = (b * 10).roundToInt()
    return "${r / 10}.${((r % 10) + 10) % 10} ${units[i]}"
}

/** Transfer speed, or null before the first sample, so the caller leaves it out rather than drawing a dash. */
internal fun humanBytesPerSec(bps: Long): String? = if (bps <= 0) null else "${humanBytes(bps)}/s"

/** The one live download line: "42% · 1.2 MB/s · 80.0 MB left", each part only when known. */
internal fun downloadProgressLabel(s: DownloadStatus.InProgress): String {
    val pct = (s.progress.coerceIn(0f, 1f) * 100).roundToInt()
    val remaining = if (s.totalBytes > 0) {
        "${humanBytes((s.totalBytes - s.downloadedBytes).coerceAtLeast(0))} left"
    } else null
    return listOfNotNull("$pct%", humanBytesPerSec(s.bytesPerSec), remaining).joinToString(" · ")
}
