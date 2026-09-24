package com.sabreware.aide.app.speech.loop

import com.sabreware.aide.app.speech.VoiceTurnPolicy

data class TurnSummary(
    val wasBlank: Boolean,
    val cumulativeBlankMs: Long,
)

enum class TurnDecision { Continue, Stop }

fun VoiceTurnPolicy.evaluate(summary: TurnSummary): TurnDecision = when (this) {
    VoiceTurnPolicy.OffAfterReply ->
        if (summary.wasBlank) TurnDecision.Stop else TurnDecision.Stop
    VoiceTurnPolicy.KeepListening -> TurnDecision.Continue
    is VoiceTurnPolicy.OffAfterIdleSilence ->
        if (summary.wasBlank && summary.cumulativeBlankMs >= silenceMs) TurnDecision.Stop
        else TurnDecision.Continue
}
