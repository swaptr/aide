package com.swaptr.aide.domain.speech.loop

sealed interface VoiceTurnPolicy {

    data object OffAfterReply : VoiceTurnPolicy

    data object KeepListening : VoiceTurnPolicy

    data class OffAfterIdleSilence(val silenceMs: Long) : VoiceTurnPolicy

    companion object {
        const val DEFAULT_ASSISTANT_SILENCE_MS: Long = 15_000L
    }
}

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
