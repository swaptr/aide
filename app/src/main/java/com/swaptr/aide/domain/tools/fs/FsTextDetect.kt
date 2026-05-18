package com.swaptr.aide.domain.tools.fs

object FsTextDetect {

    fun isLikelyTextFromMime(mime: String?): Boolean? {
        if (mime == null) return null
        val lower = mime.lowercase()
        if (lower.startsWith("text/")) return true
        if (lower.startsWith("image/") || lower.startsWith("video/") ||
            lower.startsWith("audio/") || lower == "application/pdf" ||
            lower == "application/zip" || lower == "application/octet-stream" ||
            lower.startsWith("application/x-")
        ) return false
        if (lower == "application/json" || lower == "application/xml" ||
            lower.endsWith("+json") || lower.endsWith("+xml") ||
            lower == "application/javascript"
        ) return true
        return null
    }

    // NUL bytes or >10% unprintable → binary.
    fun isProbablyText(probe: ByteArray): Boolean {
        if (probe.isEmpty()) return true
        var unprintable = 0
        for (b in probe) {
            val v = b.toInt() and 0xff
            if (v == 0) return false
            if (v < 0x09 || (v in 0x0E..0x1F)) unprintable++
        }
        return unprintable * 10 < probe.size
    }
}
