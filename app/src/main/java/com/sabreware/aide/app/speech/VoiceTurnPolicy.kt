package com.sabreware.aide.app.speech

/**
 * How the assistant's voice turn loop decides whether to keep listening after each turn. Pure config
 * (no platform/data deps): the [VoiceTurnLoop] evaluates it; the controller maps the user's pref to it.
 */
sealed interface VoiceTurnPolicy {
    data object OffAfterReply : VoiceTurnPolicy
    data object KeepListening : VoiceTurnPolicy
    data class OffAfterIdleSilence(val silenceMs: Long) : VoiceTurnPolicy

    companion object {
        const val DEFAULT_ASSISTANT_SILENCE_MS: Long = 15_000L
    }
}
