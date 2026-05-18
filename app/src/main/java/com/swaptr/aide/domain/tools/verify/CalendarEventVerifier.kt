package com.swaptr.aide.domain.tools.verify

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.swaptr.aide.domain.llm.verify.VerificationResult
import com.swaptr.aide.domain.llm.verify.Verifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Token-Jaccard threshold 0.7 is generous because users often edit the title in the
// system editor before saving.
class CalendarEventVerifier(
    private val context: Context,
    private val expectedTitle: String,
    private val startMillis: Long,
    private val toleranceWindowMs: Long = 60 * 60 * 1000L,
    private val leadWindowMs: Long = 5 * 60 * 1000L,
    private val jaccardThreshold: Double = 0.7,
    private val pollIntervalMs: Long = 250,
    private val maxPolls: Int = 6,
) : Verifier<CalendarEventEvidence> {

    override suspend fun verify(): VerificationResult<CalendarEventEvidence> {
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return VerificationResult.VerificationImpossible("permission_denied")
        val wantedTokens = tokens(expectedTitle)
        if (wantedTokens.isEmpty()) return VerificationResult.NotVerified("empty_title")
        return withContext(Dispatchers.IO) {
            repeat(maxPolls) {
                val hit = scanWindow(wantedTokens)
                if (hit != null) return@withContext VerificationResult.Verified(hit)
                delay(pollIntervalMs)
            }
            VerificationResult.NotVerified("no_match")
        }
    }

    private fun scanWindow(wantedTokens: Set<String>): CalendarEventEvidence? {
        val begin = startMillis - leadWindowMs
        val end = startMillis + toleranceWindowMs
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, begin)
            ContentUris.appendId(this, end)
        }.build()
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.TITLE,
            ),
            null, null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val title = cursor.getString(2) ?: continue
                if (similarity(wantedTokens, tokens(title)) >= jaccardThreshold) {
                    return CalendarEventEvidence(
                        eventId = cursor.getLong(0),
                        calendarId = cursor.getLong(1),
                    )
                }
            }
        }
        return null
    }

    private fun tokens(text: String): Set<String> = text.lowercase()
        .split(WHITESPACE)
        .map { it.trim(*PUNCT) }
        .filter { it.length > 1 }
        .toSet()

    private fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersect = a.intersect(b).size
        val union = a.union(b).size
        if (union == 0) return 0.0
        return intersect.toDouble() / union.toDouble()
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val PUNCT = charArrayOf('.', ',', ';', ':', '!', '?', '"', '\'', '(', ')', '[', ']')
    }
}

data class CalendarEventEvidence(val eventId: Long, val calendarId: Long)
