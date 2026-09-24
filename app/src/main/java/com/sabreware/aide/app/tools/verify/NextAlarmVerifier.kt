package com.sabreware.aide.app.tools.verify

import android.app.AlarmManager
import android.content.Context
import com.sabreware.aide.core.common.di.IO
import com.sabreware.aide.core.domain.llm.verify.VerificationResult
import com.sabreware.aide.core.domain.llm.verify.Verifier
import java.util.Calendar
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// 6-min tolerance for Samsung stock clock's ~5 min drift; NotVerified expected when
// EXTRA_SKIP_UI is ignored and the editor still needs a save tap.
class NextAlarmVerifier(
    private val context: Context,
    private val expectedHour: Int,
    private val expectedMinutes: Int,
    private val toleranceMinutes: Int = 6,
    private val pollIntervalMs: Long = 250,
    private val maxPolls: Int = 6,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Verifier<NextAlarmEvidence> {

    override suspend fun verify(): VerificationResult<NextAlarmEvidence> {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return VerificationResult.VerificationImpossible("alarm_service_unavailable")
        return withContext(ioDispatcher) {
            verifyingQuery {
            repeat(maxPolls) {
                val info = am.nextAlarmClock
                if (info != null) {
                    val cal = Calendar.getInstance().apply { timeInMillis = info.triggerTime }
                    val hour = cal.get(Calendar.HOUR_OF_DAY)
                    val minute = cal.get(Calendar.MINUTE)
                    if (minutesMatch(hour, minute)) {
                        return@verifyingQuery VerificationResult.Verified(
                            NextAlarmEvidence(info.triggerTime, label = null),
                        )
                    }
                }
                delay(pollIntervalMs)
            }
            VerificationResult.NotVerified("no_match")
            }
        }
    }

    private fun minutesMatch(actualHour: Int, actualMinute: Int): Boolean {
        val want = expectedHour * 60 + expectedMinutes
        val got = actualHour * 60 + actualMinute
        val diff = abs(want - got)
        return diff <= toleranceMinutes || diff >= (24 * 60) - toleranceMinutes
    }
}

data class NextAlarmEvidence(val triggerEpochMs: Long, val label: String?)
