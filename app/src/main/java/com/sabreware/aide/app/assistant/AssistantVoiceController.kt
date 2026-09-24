package com.sabreware.aide.app.assistant

import android.content.Intent
import android.util.Log
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.domain.chat.ChatRepository
import com.sabreware.aide.core.domain.chat.ChatTranscriptFactory
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.model.ModelGateState
import com.sabreware.aide.core.domain.model.ModelRegistryRepository
import com.sabreware.aide.core.domain.model.ResidencyHandle
import com.sabreware.aide.core.domain.permission.AppPermission
import com.sabreware.aide.core.domain.prefs.VoiceTurnPolicyMode
import com.sabreware.aide.core.domain.speech.SpeechEngineRepository
import com.sabreware.aide.core.domain.speech.SpeechPrefs
import com.sabreware.aide.app.speech.VoiceLoopState
import com.sabreware.aide.app.speech.VoiceTurnLoop
import com.sabreware.aide.app.speech.VoiceTurnPolicy
import com.sabreware.aide.core.domain.usecase.ResidencyDurations
import com.sabreware.aide.platform.android.permission.AndroidRuntimePermissionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Lets the controller drive the assistant's session window during the mic permission round-trip.
 * The system permission dialog renders under the TYPE_VOICE_INTERACTION overlay, so we hide the
 * overlay ([setUiEnabled] false) to put the dialog on top and make it clickable, and launch the
 * trampoline as an assistant activity ([startAssistantActivity]) so it foregrounds correctly. Both
 * are official VoiceInteractionSession APIs; setUiEnabled(false) hides only the window — the session
 * stays alive, so the assistant doesn't exit.
 */
interface AssistantWindowHost {
    fun setUiEnabled(enabled: Boolean)
    fun startAssistantActivity(intent: Intent)
}

// Process-singleton — survives overlay hide/show so re-summoning resumes the
// same conversation while guards keep models warm under their idle timers.
class AssistantVoiceController(
    private val voiceTurnLoop: VoiceTurnLoop,
    private val chatRepository: ChatRepository,
    private val transcriptFactory: ChatTranscriptFactory,
    private val speechEngine: SpeechEngineRepository,
    private val prefs: PreferenceStore,
    private val gate: AndroidRuntimePermissionGate,
    private val mainImmediateDispatcher: CoroutineDispatcher,
    modelRegistry: ModelRegistryRepository,
) {
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + mainImmediateDispatcher)
    private val voiceSession = voiceTurnLoop.newSession()

    private val _state = MutableStateFlow<VoiceLoopState>(VoiceLoopState.Idle)
    val state: StateFlow<VoiceLoopState> = _state.asStateFlow()

    // WhileSubscribed so we stop collecting modelRegistry when the overlay is hidden
    // (no subscribers) instead of for the whole process; 5s grace covers quick re-summons.
    // Per-modality readiness: voice needs a chat model AND a usable STT engine. STT readiness uses
    // "an engine can serve the role" semantics (the system recognizer counts) — never gated on an
    // installed on-disk model, so system-STT users aren't walled behind a download.
    // The chip is an OFFER, not a lock, so an unfinished registry must not draw it — and the seed is
    // false for the same reason. Offering to set up a model the user already has, for the second or so a
    // cold start takes to read its providers, is exactly the flash this gate state exists to end.
    val needsModelSetup: StateFlow<Boolean> = modelRegistry.gateStateFlow
        .map { gate ->
            when (gate) {
                ModelGateState.Unresolved -> false
                else -> gate !is ModelGateState.Ready ||
                    !speechEngine.availability(SpeechEngineRepository.Role.STT).canStt
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

    private val _voiceChatId = MutableStateFlow<String?>(null)
    val voiceChatId: StateFlow<String?> = _voiceChatId.asStateFlow()

    // Set by the live session so the mic permission round-trip can hide/restore the overlay and
    // launch the trampoline as an assistant activity. Null when no session window is attached.
    @Volatile
    var windowHost: AssistantWindowHost? = null

    private var loopJob: Job? = null

    fun onTapMic() {
        if (loopJob?.isActive == true) stop()
        else start()
    }

    // Idempotent — re-show mid-turn must not restart an active loop.
    fun startIfIdle() {
        if (loopJob?.isActive != true) start()
    }

    // Preload STT + Silero VAD so the first mic tap doesn't pay JNI init latency.
    fun warmUpStt() {
        scope.launch { runCatching { speechEngine.warmUpStt() } }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        _state.value = VoiceLoopState.Idle
    }

    private fun start() {
        Log.i(TAG, "start() — cancelling prior job=$loopJob")
        loopJob?.cancel()
        loopJob = scope.launch {
            try {
                // Surface the system mic dialog from the assistant. Without this the pipeline would
                // just fail with a missing-mic error.
                if (!ensureMicPermission()) return@launch
                val chatId = ensureVoiceChat()
                if (chatId == null) {
                    Log.w(TAG, "no model picked — surfacing recoverable error")
                    _state.value = VoiceLoopState.Error(
                        "Pick a default model in the main Aide app first.",
                        recoverable = true,
                    )
                    return@launch
                }
                voiceSession.transcript = transcriptFactory.createPersistent(chatId)
                val policy = resolvePolicy()
                Log.i(TAG, "policy=$policy chatId=$chatId — acquiring speech models")
                // The LLM is acquired per-turn inside SendChatMessageUseCase; here we hold the three
                // speech models for the whole session so they stay warm across turns, releasing them
                // on the voice keepAlive so quick re-summons skip a cold reload. Fixed order asr→vad→tts.
                // Nullable + acquire-inside-try so a mid-acquire failure still releases what was taken.
                var asr: ResidencyHandle? = null
                var vad: ResidencyHandle? = null
                var tts: ResidencyHandle? = null
                try {
                    asr = speechEngine.acquire(SpeechEngineRepository.Role.STT)
                    vad = speechEngine.acquire(SpeechEngineRepository.Role.VAD)
                    tts = speechEngine.acquire(SpeechEngineRepository.Role.TTS)
                    Log.i(TAG, "speech acquired — entering pipeline")
                    voiceTurnLoop.run(voiceSession, policy)
                        .collect { state ->
                            if (state !is VoiceLoopState.Listening) {
                                Log.d(TAG, "state=$state")
                            }
                            _state.value = state
                        }
                    Log.i(TAG, "pipeline flow completed normally")
                } finally {
                    runCatching { tts?.release(ResidencyDurations.VOICE_KEEPALIVE_MS) }
                    runCatching { vad?.release(ResidencyDurations.VOICE_KEEPALIVE_MS) }
                    runCatching { asr?.release(ResidencyDurations.VOICE_KEEPALIVE_MS) }
                    Log.i(TAG, "speech released")
                }
            } catch (ce: CancellationException) {
                Log.i(TAG, "loop cancelled")
                _state.value = VoiceLoopState.Idle
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "voice loop ended with throwable", t)
                _state.value = VoiceLoopState.Error(
                    t.message ?: "Voice loop failed",
                    recoverable = true,
                )
            }
        }
    }

    /**
     * Ensures RECORD_AUDIO from the assistant surface. The in-app rationale is skipped — it would
     * draw under the overlay — so we go straight to the system dialog. When a session window is
     * attached we hide it ([AssistantWindowHost.setUiEnabled] false) so the dialog is topmost and
     * clickable, launch the trampoline as an assistant activity, then restore the overlay; the
     * session stays alive so the assistant never exits. Returns true when granted; otherwise sets a
     * recoverable error and returns false.
     */
    private suspend fun ensureMicPermission(): Boolean {
        if (gate.isGranted(AppPermission.MICROPHONE)) return true
        val host = windowHost
        val result = if (host != null) {
            host.setUiEnabled(false)
            try {
                gate.ensure(
                    AppPermission.MICROPHONE,
                    showRationale = false,
                    launchActivity = host::startAssistantActivity,
                )
            } finally {
                host.setUiEnabled(true)
            }
        } else {
            gate.ensure(AppPermission.MICROPHONE, showRationale = false)
        }
        result.deniedMessage?.let { message ->
            _state.value = VoiceLoopState.Error(message, recoverable = true)
            return false
        }
        return true
    }

    private suspend fun resolvePolicy(): VoiceTurnPolicy {
        val mode = prefs.flow(SpeechPrefs.VoiceTurnPolicyMode).firstOrNull()
            ?: VoiceTurnPolicyMode.OFF_AFTER_IDLE_SILENCE
        val idleMs = prefs.flow(SpeechPrefs.VoiceTurnPolicyIdleMs).firstOrNull() ?: 15_000L
        return when (mode) {
            VoiceTurnPolicyMode.OFF_AFTER_REPLY -> VoiceTurnPolicy.OffAfterReply
            VoiceTurnPolicyMode.KEEP_LISTENING -> VoiceTurnPolicy.KeepListening
            VoiceTurnPolicyMode.OFF_AFTER_IDLE_SILENCE ->
                VoiceTurnPolicy.OffAfterIdleSilence(idleMs)
        }
    }

    // Returns `null` when no model is configured — caller surfaces an error state.
    private suspend fun ensureVoiceChat(): String? {
        _voiceChatId.value?.let { return it }
        // Avoid creating an orphan chat row when there's no active model — the loop
        // can't run without one and the row would just clutter the drawer.
        voiceTurnLoop.resolveActiveModelId() ?: return null
        val chat = chatRepository.createChat(
            title = "Voice chat",
            surface = Surface.VOICE,
        )
        _voiceChatId.value = chat.id
        return chat.id
    }

    fun release() {
        stop()
        scope.cancel()
        runCatching { voiceSession.session?.close() }
        voiceSession.session = null
        _voiceChatId.value = null
    }

    companion object {
        private const val TAG = "AssistantVoiceCtl"
    }
}
