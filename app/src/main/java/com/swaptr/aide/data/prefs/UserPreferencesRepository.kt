package com.swaptr.aide.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.swaptr.aide.domain.search.WebSearchProviderId
import com.swaptr.aide.domain.speech.SpeechProviderId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {

    val defaultModelIdFlow: Flow<String?> =
        appContext.dataStore.data.map { prefs: Preferences -> prefs[KEY_DEFAULT_MODEL] }

    val webSearchEnabledFlow: Flow<Boolean> =
        appContext.dataStore.data.map { prefs -> prefs[KEY_WEB_SEARCH_ENABLED] ?: false }

    // null = "auto" (resolver walks priority list); enum.name storage forces deliberate rename migrations.
    val webSearchProviderOverrideFlow: Flow<WebSearchProviderId?> =
        appContext.dataStore.data.map { prefs ->
            prefs[KEY_WEB_SEARCH_PROVIDER_OVERRIDE]
                ?.let { name -> runCatching { WebSearchProviderId.valueOf(name) }.getOrNull() }
        }

    val filesystemToolEnabledFlow: Flow<Boolean> =
        appContext.dataStore.data.map { prefs -> prefs[KEY_FS_TOOL_ENABLED] ?: false }

    // JSON blob lets the schema evolve via kotlinx.serialization defaults without DataStore migration.
    val registeredFsRootsJsonFlow: Flow<String> =
        appContext.dataStore.data.map { prefs -> prefs[KEY_FS_ROOTS_JSON] ?: "[]" }

    // Per-tier last-used; flipping tiers restores right pick instead of falling to global default.
    val lastUsedByTierJsonFlow: Flow<String> =
        appContext.dataStore.data.map { prefs -> prefs[KEY_LAST_USED_TIER_JSON] ?: "{}" }

    // Sticky across process death; consulted before defaultModelIdFlow in draft auto-pick.
    val lastUsedModelIdFlow: Flow<String?> =
        appContext.dataStore.data.map { prefs -> prefs[KEY_LAST_USED_MODEL] }

    // Stored as enum.name for forward compat.
    val imePageFlow: Flow<ImePage> =
        appContext.dataStore.data.map { prefs ->
            val raw = prefs[KEY_IME_PAGE] ?: return@map ImePage.KEYBOARD
            runCatching { ImePage.valueOf(raw) }.getOrDefault(ImePage.KEYBOARD)
        }

    // Default empty = nothing on; users must opt-in per category (and grant any runtime perm).
    val enabledToolCategoriesFlow: Flow<Set<ToolCategory>> =
        appContext.dataStore.data.map { prefs ->
            (prefs[KEY_ENABLED_TOOL_CATEGORIES] ?: emptySet())
                .mapNotNull { runCatching { ToolCategory.valueOf(it) }.getOrNull() }
                .toSet()
        }

    // IME bypasses this gate (no dialog host).
    val askBeforeEachToolFlow: Flow<Boolean> =
        appContext.dataStore.data.map { prefs -> prefs[KEY_ASK_BEFORE_EACH_TOOL] ?: false }

    // null = "auto" (resolver walks Sherpa → System); enum.name forces deliberate rename migrations.
    val speechProviderPreferenceFlow: Flow<SpeechProviderId?> =
        appContext.dataStore.data.map { prefs ->
            prefs[KEY_SPEECH_PROVIDER]
                ?.let { name -> runCatching { SpeechProviderId.valueOf(name) }.getOrNull() }
        }

    val voiceLoopEnabledFlow: Flow<Boolean> =
        appContext.dataStore.data.map { prefs -> prefs[KEY_VOICE_LOOP_ENABLED] ?: true }

    val imeDictationEnabledFlow: Flow<Boolean> =
        appContext.dataStore.data.map { prefs -> prefs[KEY_IME_DICTATION_ENABLED] ?: true }

    val mainChatDictationEnabledFlow: Flow<Boolean> =
        appContext.dataStore.data.map { prefs -> prefs[KEY_MAIN_CHAT_DICTATION_ENABLED] ?: true }

    val speakAssistantRepliesFlow: Flow<Boolean> =
        appContext.dataStore.data.map { prefs -> prefs[KEY_SPEAK_ASSISTANT_REPLIES] ?: true }

    val announceToolCallsFlow: Flow<Boolean> =
        appContext.dataStore.data.map { prefs -> prefs[KEY_ANNOUNCE_TOOL_CALLS] ?: false }

    /** Backend-defined voice id for the active TTS engine. Sherpa: speaker id; System: voice name. */
    val preferredTtsVoiceIdFlow: Flow<String?> =
        appContext.dataStore.data.map { prefs -> prefs[KEY_PREFERRED_TTS_VOICE_ID] }

    // null = engine falls back to first-on-disk / catalog default.
    val activeSttModelIdFlow: Flow<String?> =
        appContext.dataStore.data.map { prefs -> prefs[KEY_ACTIVE_STT_MODEL] }

    /** Same as [activeSttModelIdFlow] but for the Sherpa TTS engine. */
    val activeTtsModelIdFlow: Flow<String?> =
        appContext.dataStore.data.map { prefs -> prefs[KEY_ACTIVE_TTS_MODEL] }

    // Idle-ms field only used when mode is OFF_AFTER_IDLE_SILENCE.
    val voiceTurnPolicyModeFlow: Flow<VoiceTurnPolicyMode> =
        appContext.dataStore.data.map { prefs ->
            val raw = prefs[KEY_VOICE_TURN_POLICY_MODE] ?: return@map VoiceTurnPolicyMode.OFF_AFTER_IDLE_SILENCE
            runCatching { VoiceTurnPolicyMode.valueOf(raw) }
                .getOrDefault(VoiceTurnPolicyMode.OFF_AFTER_IDLE_SILENCE)
        }

    val voiceTurnPolicyIdleMsFlow: Flow<Long> =
        appContext.dataStore.data.map { prefs -> prefs[KEY_VOICE_TURN_POLICY_IDLE_MS] ?: 15_000L }

    suspend fun setDefaultModelId(id: String) {
        appContext.dataStore.edit { it[KEY_DEFAULT_MODEL] = id }
    }

    suspend fun clearDefaultModelId() {
        appContext.dataStore.edit { it.remove(KEY_DEFAULT_MODEL) }
    }

    suspend fun setWebSearchEnabled(enabled: Boolean) {
        appContext.dataStore.edit { it[KEY_WEB_SEARCH_ENABLED] = enabled }
    }

    suspend fun setWebSearchProviderOverride(id: WebSearchProviderId?) {
        appContext.dataStore.edit {
            if (id == null) it.remove(KEY_WEB_SEARCH_PROVIDER_OVERRIDE)
            else it[KEY_WEB_SEARCH_PROVIDER_OVERRIDE] = id.name
        }
    }

    suspend fun setFilesystemToolEnabled(enabled: Boolean) {
        appContext.dataStore.edit { it[KEY_FS_TOOL_ENABLED] = enabled }
    }

    suspend fun setRegisteredFsRootsJson(json: String) {
        appContext.dataStore.edit { it[KEY_FS_ROOTS_JSON] = json }
    }

    suspend fun setLastUsedByTierJson(json: String) {
        appContext.dataStore.edit { it[KEY_LAST_USED_TIER_JSON] = json }
    }

    suspend fun setLastUsedModelId(id: String) {
        appContext.dataStore.edit { it[KEY_LAST_USED_MODEL] = id }
    }

    suspend fun clearLastUsedModelId() {
        appContext.dataStore.edit { it.remove(KEY_LAST_USED_MODEL) }
    }

    suspend fun setImePage(page: ImePage) {
        appContext.dataStore.edit { it[KEY_IME_PAGE] = page.name }
    }

    suspend fun setToolCategoryEnabled(cat: ToolCategory, enabled: Boolean) {
        appContext.dataStore.edit { prefs ->
            val cur = prefs[KEY_ENABLED_TOOL_CATEGORIES] ?: emptySet()
            prefs[KEY_ENABLED_TOOL_CATEGORIES] =
                if (enabled) cur + cat.name else cur - cat.name
        }
    }

    suspend fun setAskBeforeEachTool(enabled: Boolean) {
        appContext.dataStore.edit { it[KEY_ASK_BEFORE_EACH_TOOL] = enabled }
    }

    suspend fun setSpeechProviderPreference(id: SpeechProviderId?) {
        appContext.dataStore.edit {
            if (id == null) it.remove(KEY_SPEECH_PROVIDER)
            else it[KEY_SPEECH_PROVIDER] = id.name
        }
    }

    suspend fun setVoiceLoopEnabled(enabled: Boolean) {
        appContext.dataStore.edit { it[KEY_VOICE_LOOP_ENABLED] = enabled }
    }

    suspend fun setImeDictationEnabled(enabled: Boolean) {
        appContext.dataStore.edit { it[KEY_IME_DICTATION_ENABLED] = enabled }
    }

    suspend fun setMainChatDictationEnabled(enabled: Boolean) {
        appContext.dataStore.edit { it[KEY_MAIN_CHAT_DICTATION_ENABLED] = enabled }
    }

    suspend fun setSpeakAssistantReplies(enabled: Boolean) {
        appContext.dataStore.edit { it[KEY_SPEAK_ASSISTANT_REPLIES] = enabled }
    }

    suspend fun setAnnounceToolCalls(enabled: Boolean) {
        appContext.dataStore.edit { it[KEY_ANNOUNCE_TOOL_CALLS] = enabled }
    }

    suspend fun setPreferredTtsVoiceId(voiceId: String?) {
        appContext.dataStore.edit {
            if (voiceId == null) it.remove(KEY_PREFERRED_TTS_VOICE_ID)
            else it[KEY_PREFERRED_TTS_VOICE_ID] = voiceId
        }
    }

    suspend fun setActiveSttModelId(id: String?) {
        appContext.dataStore.edit {
            if (id == null) it.remove(KEY_ACTIVE_STT_MODEL)
            else it[KEY_ACTIVE_STT_MODEL] = id
        }
    }

    suspend fun setActiveTtsModelId(id: String?) {
        appContext.dataStore.edit {
            if (id == null) it.remove(KEY_ACTIVE_TTS_MODEL)
            else it[KEY_ACTIVE_TTS_MODEL] = id
        }
    }

    suspend fun setVoiceTurnPolicy(mode: VoiceTurnPolicyMode, idleMs: Long? = null) {
        appContext.dataStore.edit {
            it[KEY_VOICE_TURN_POLICY_MODE] = mode.name
            if (idleMs != null) it[KEY_VOICE_TURN_POLICY_IDLE_MS] = idleMs
        }
    }

    companion object {
        private val KEY_DEFAULT_MODEL = stringPreferencesKey("default_model_id")
        private val KEY_WEB_SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")
        private val KEY_WEB_SEARCH_PROVIDER_OVERRIDE = stringPreferencesKey("web_search_provider_override")
        private val KEY_FS_TOOL_ENABLED = booleanPreferencesKey("fs_tool_enabled")
        private val KEY_FS_ROOTS_JSON = stringPreferencesKey("fs_roots_json")
        private val KEY_LAST_USED_TIER_JSON = stringPreferencesKey("last_used_by_tier_json")
        private val KEY_LAST_USED_MODEL = stringPreferencesKey("last_used_model_id")
        private val KEY_IME_PAGE = stringPreferencesKey("ime_page")
        private val KEY_ENABLED_TOOL_CATEGORIES = stringSetPreferencesKey("enabled_tool_categories")
        private val KEY_ASK_BEFORE_EACH_TOOL = booleanPreferencesKey("ask_before_each_tool")
        private val KEY_SPEECH_PROVIDER = stringPreferencesKey("speech_provider")
        private val KEY_VOICE_LOOP_ENABLED = booleanPreferencesKey("voice_loop_enabled")
        private val KEY_IME_DICTATION_ENABLED = booleanPreferencesKey("ime_dictation_enabled")
        private val KEY_MAIN_CHAT_DICTATION_ENABLED = booleanPreferencesKey("main_chat_dictation_enabled")
        private val KEY_SPEAK_ASSISTANT_REPLIES = booleanPreferencesKey("speak_assistant_replies")
        private val KEY_ANNOUNCE_TOOL_CALLS = booleanPreferencesKey("announce_tool_calls")
        private val KEY_PREFERRED_TTS_VOICE_ID = stringPreferencesKey("preferred_tts_voice_id")
        private val KEY_ACTIVE_STT_MODEL = stringPreferencesKey("active_stt_model_id")
        private val KEY_ACTIVE_TTS_MODEL = stringPreferencesKey("active_tts_model_id")
        private val KEY_VOICE_TURN_POLICY_MODE = stringPreferencesKey("voice_turn_policy_mode")
        private val KEY_VOICE_TURN_POLICY_IDLE_MS = longPreferencesKey("voice_turn_policy_idle_ms")
    }
}

// Lives in prefs package to break the domain<->data circular dep.
enum class VoiceTurnPolicyMode {
    OFF_AFTER_REPLY,
    KEEP_LISTENING,
    OFF_AFTER_IDLE_SILENCE,
}

// promptLabel matches SystemPromptBuilder.categoryOf so the registry can filter on it.
enum class ToolCategory(val promptLabel: String, val displayName: String, val blurb: String) {
    TIME("Time", "Time", "Read the current date and time."),
    MATH("Math", "Math", "Run arithmetic and math expressions."),
    CLOCK("Clock", "Clock", "Set alarms and timers; list and dismiss them."),
    CALENDAR("Calendar", "Calendar", "Read, add, and edit calendar events."),
    PHONE("Phone", "Phone", "Dial, SMS, email, and open contacts/call log."),
    CONTACTS("Contacts", "Contacts", "Resolve and delete contacts."),
    CLIPBOARD("Clipboard", "Clipboard", "Read and set the system clipboard."),
    WEB("Web", "Web", "Search the web and fetch URLs."),
    FILESYSTEM("Filesystem", "Filesystem", "List, read, and modify files in granted folders."),
}

// Single value today; kept as a future hook for additional pages.
enum class ImePage { KEYBOARD }
