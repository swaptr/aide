package com.sabreware.aide.core.domain.speech

import com.sabreware.aide.core.common.prefs.PrefSnapshot
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.prefs.select
import com.sabreware.aide.core.common.prefs.boolKey
import com.sabreware.aide.core.common.prefs.enumKey
import com.sabreware.aide.core.common.prefs.longKey
import com.sabreware.aide.core.common.prefs.nullableStringKey
import com.sabreware.aide.core.domain.llm.Provider
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.prefs.VoiceTurnPolicyMode
import kotlinx.coroutines.flow.Flow

/** Speech preferences. Key names and defaults match what `UserPreferencesRepository` stored. */
object SpeechPrefs {
    /** null = "auto": the resolver prefers the vendor of the active cloud model, then the platform ladder.
     *  Stored as the provider KEY, not an enum name — [ProviderId] is an open value class. */
    val Provider = nullableStringKey("speech_provider")

    val ImeDictationEnabled = boolKey("ime_dictation_enabled", default = true)
    val MainChatDictationEnabled = boolKey("main_chat_dictation_enabled", default = true)
    val SpeakAssistantReplies = boolKey("speak_assistant_replies", default = true)
    val AnnounceToolCalls = boolKey("announce_tool_calls", default = false)

    /** Backend-defined voice id for the active TTS engine. Sherpa: speaker id; System: voice name. */
    val PreferredTtsVoiceId = nullableStringKey("preferred_tts_voice_id")

    val VoiceTurnPolicyMode =
        enumKey("voice_turn_policy_mode", default = com.sabreware.aide.core.domain.prefs.VoiceTurnPolicyMode.OFF_AFTER_IDLE_SILENCE)

    /** Only consulted when the mode is OFF_AFTER_IDLE_SILENCE. */
    val VoiceTurnPolicyIdleMs = longKey("voice_turn_policy_idle_ms", default = 15_000L)
}

/**
 * The speech provider preference as a [ProviderId]; blank or absent reads as null ("auto").
 *
 * Any stored key is handed back, not only the two on-device engines: cloud vendors contribute
 * [SpeechProvider]s too, and the id set is whatever the running application bound. A key naming a provider
 * that is not registered is harmless — [SpeechEngineRepository] looks the pin up in the registry and walks
 * its ladder when nothing answers — so filtering here would only turn a stale pin into a silent "auto".
 */
fun PreferenceStore.speechProviderPreference(): Flow<ProviderId?> = select { it.speechProvider }

/** The picked speech provider; null (or a blank stored value) = Auto. */
val PrefSnapshot.speechProvider: ProviderId?
    get() = this[SpeechPrefs.Provider]?.takeIf { it.isNotBlank() }?.let(::ProviderId)

suspend fun PreferenceStore.setSpeechProviderPreference(id: ProviderId?) {
    set(SpeechPrefs.Provider, id?.value)
}

/** Writes the mode and, when given, the idle threshold it uses. */
suspend fun PreferenceStore.setVoiceTurnPolicy(mode: VoiceTurnPolicyMode, idleMs: Long? = null) {
    set(SpeechPrefs.VoiceTurnPolicyMode, mode)
    if (idleMs != null) set(SpeechPrefs.VoiceTurnPolicyIdleMs, idleMs)
}
